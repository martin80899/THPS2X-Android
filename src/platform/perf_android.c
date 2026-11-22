/* THPS 2X Android: keeping the phone's CPU up to speed for the game.
 *
 * Android's scheduler sizes a thread's clock and core by how busy it was a moment ago. The game is busy
 * in bursts - one frame's work, then a wait for the next 60 Hz tick - so it often looks half idle and
 * runs on slow cores at low clocks, and a frame that does not fit in 16.7 ms costs a whole tick (60 ->
 * 30 fps). Two things against that, on the threads that make each frame (the game's swap and the
 * renderer's flip; perf_android_frame, every frame):
 *
 *  - Android's performance hint (ADPF, APerformanceHint, Android 13+): a session for those threads with
 *    the 16.7 ms target, and each frame's real work (their CPU time) reported, so the system raises the
 *    clocks before a frame runs late instead of after. libandroid's functions are looked up at run time
 *    (the app starts on Android 12 too, without them).
 *  - Fast cores: those threads on the faster cores only (cpu_capacity at least half the fastest), off
 *    the small efficiency cores. THPS2X_FAST_CORES=0 (env.txt) leaves the cores to the scheduler.
 */
#define _GNU_SOURCE 1
#include <dlfcn.h>
#include <pthread.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/syscall.h>

typedef struct APerformanceHintManager APerformanceHintManager;
typedef struct APerformanceHintSession APerformanceHintSession;

static APerformanceHintManager *(*p_get_manager)(void);
static APerformanceHintSession *(*p_create)(APerformanceHintManager *, const int32_t *, size_t, int64_t);
static int (*p_report)(APerformanceHintSession *, int64_t);
static void (*p_close)(APerformanceHintSession *);

#define MAX_T 4
static pthread_mutex_t s_mu = PTHREAD_MUTEX_INITIALIZER;
static int32_t s_tid[MAX_T];
static clockid_t s_clk[MAX_T];
static int64_t s_cpu_last[MAX_T];
static int s_n, s_init, s_fast = -1;
static APerformanceHintSession *s_session;
static cpu_set_t s_fast_set;
static int s_fast_count;

static const int64_t TARGET_NS = 16666667;

static int64_t clk_ns(clockid_t c)
{
    struct timespec ts;
    if (clock_gettime(c, &ts) != 0) return 0;
    return (int64_t)ts.tv_sec * 1000000000ll + ts.tv_nsec;
}

static void init_once(void)
{
    void *lib;
    const char *e = getenv("THPS2X_FAST_CORES");
    s_init = 1;
    if ((lib = dlopen("libandroid.so", RTLD_NOW))) {
        p_get_manager = (APerformanceHintManager *(*)(void))dlsym(lib, "APerformanceHint_getManager");
        p_create = (APerformanceHintSession *(*)(APerformanceHintManager *, const int32_t *, size_t, int64_t))
                   dlsym(lib, "APerformanceHint_createSession");
        p_report = (int (*)(APerformanceHintSession *, int64_t))dlsym(lib, "APerformanceHint_reportActualWorkDuration");
        p_close = (void (*)(APerformanceHintSession *))dlsym(lib, "APerformanceHint_closeSession");
    }
    s_fast = !(e && *e == '0');
    if (s_fast) {
        long cap[32], best = 0;
        int cpu;
        for (cpu = 0; cpu < 32; cpu++) {
            char p[96];
            FILE *f;
            cap[cpu] = -1;
            snprintf(p, sizeof p, "/sys/devices/system/cpu/cpu%d/cpu_capacity", cpu);
            if ((f = fopen(p, "r"))) {
                if (fscanf(f, "%ld", &cap[cpu]) != 1) cap[cpu] = -1;
                fclose(f);
            }
            if (cap[cpu] > best) best = cap[cpu];
        }
        CPU_ZERO(&s_fast_set);
        for (cpu = 0; cpu < 32; cpu++)
            if (best > 0 && cap[cpu] * 2 >= best) { CPU_SET(cpu, &s_fast_set); s_fast_count++; }
        if (!s_fast_count) s_fast = 0;
        fprintf(stderr, "  perf: fast cores %s (%d cores at half the fastest's capacity or more)\n",
                s_fast ? "on" : "off", s_fast_count);
    }
    fprintf(stderr, "  perf: Android performance hint %s\n", p_get_manager && p_create && p_report ? "available" : "not on this Android");
}

static void session_make(void)
{
    APerformanceHintManager *m;
    if (s_session && p_close) { p_close(s_session); s_session = NULL; }
    if (!p_get_manager || !p_create || !s_n) return;
    if (!(m = p_get_manager())) return;
    s_session = p_create(m, s_tid, (size_t)s_n, TARGET_NS);
    fprintf(stderr, "  perf: performance hint session for %d thread(s): %s\n", s_n, s_session ? "on" : "refused");
}

/* Once per frame, from each thread that makes frames; report = 1 on the one whose call ends a frame
 * (the renderer's flip): the frame's work goes to the session then. */
void perf_android_frame(int report)
{
    int32_t tid = (int32_t)syscall(SYS_gettid);
    int i, known = 0;
    pthread_mutex_lock(&s_mu);
    if (!s_init) init_once();
    for (i = 0; i < s_n; i++) if (s_tid[i] == tid) known = 1;
    if (!known && s_n < MAX_T) {
        if (pthread_getcpuclockid(pthread_self(), &s_clk[s_n]) != 0)
            s_clk[s_n] = CLOCK_THREAD_CPUTIME_ID;
        s_tid[s_n] = tid;
        s_cpu_last[s_n] = clk_ns(s_clk[s_n]);
        s_n++;
        if (s_fast)
            fprintf(stderr, "  perf: thread %d on the fast cores: %s\n", tid,
                    sched_setaffinity(tid, sizeof s_fast_set, &s_fast_set) == 0 ? "yes" : "refused");
        session_make();
    }
    if (report && s_session && p_report) {
        int64_t work = 0;
        for (i = 0; i < s_n; i++) {
            int64_t now = clk_ns(s_clk[i]), d = now - s_cpu_last[i];
            s_cpu_last[i] = now;
            if (d > work) work = d;
        }
        if (work > 0 && work < 1000000000ll)
            p_report(s_session, work);
    }
    pthread_mutex_unlock(&s_mu);
}
