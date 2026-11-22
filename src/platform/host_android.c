/* host_android.c - THPS 2X host platform layer for Android (arm64).
 *
 * The Android counterpart of host_win32.c behind platform/host.h. SDL3's
 * SDLActivity starts the process and calls SDL_main (main.c is built with
 * -Dmain=SDL_main) on its own thread.
 *
 * Layout (the Windows install layout, on shared storage so a player can reach it
 * with any file manager and send the log):
 *   /storage/emulated/0/THPS2X/game    default.xbe + the disc's folders, or one disc image (.iso)
 *   /storage/emulated/0/THPS2X/media   boot.mpg, soundtracks, shader packs (optional)
 *   /storage/emulated/0/THPS2X/saves   U: and settings.ini
 *   /storage/emulated/0/THPS2X/data    thps2x.log, caches, partition images
 * Shared storage needs "All files access" (MANAGE_EXTERNAL_STORAGE) on Android 11 and later;
 * the first start asks for it. THPS2X_ROOT overrides the folder (tests).
 *
 * Faults: one SIGSEGV/SIGBUS handler stands in for the vectored exception handler.
 * Page tracking (toolkit page_track.c) arms guest pages read-only and expects the
 * handler to forward the fault; an armed page is noted and the store retried.
 * Anything else is a crash: logged with the guest registers, then the default action.
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>
#include <pthread.h>
#include <dlfcn.h>
#include <jni.h>
#include <android/log.h>

#include <SDL3/SDL.h>
#include <SDL3/SDL_system.h>

#include "host.h"
#include "xbox_memory_layout.h"
#include "recomp_icall_feedback.h"

#define LOG_TAG "THPS2X"

extern ptrdiff_t g_xbox_mem_offset;
extern RECOMP_TLS uint32_t g_eax, g_ecx, g_edx, g_ebx, g_esi, g_edi, g_esp;

static char s_data_dir[HOST_PATH_MAX];

/* ── small helpers ─────────────────────────────────────────────── */

static void join(char *out, const char *dir, const char *name)
{
    snprintf(out, HOST_PATH_MAX, "%s/%s", dir, name);
}

static void mkdirs(const char *path)
{
    char tmp[HOST_PATH_MAX];
    size_t i;
    snprintf(tmp, sizeof tmp, "%s", path);
    for (i = 1; tmp[i]; i++) {
        if (tmp[i] == '/') {
            tmp[i] = '\0';
            mkdir(tmp, 0775);
            tmp[i] = '/';
        }
    }
    mkdir(tmp, 0775);
}

static int dir_writable(const char *dir)
{
    char probe[HOST_PATH_MAX];
    int fd;
    mkdirs(dir);
    join(probe, dir, ".thps2x_probe");
    fd = open(probe, O_CREAT | O_WRONLY | O_TRUNC, 0644);
    if (fd < 0) return 0;
    close(fd);
    unlink(probe);
    return 1;
}

/* ── All files access (Android 11+) through JNI ────────────────── */

static int jni_sdk_int(JNIEnv *env)
{
    jclass ver = (*env)->FindClass(env, "android/os/Build$VERSION");
    jfieldID f;
    int v = 0;
    if (!ver) { (*env)->ExceptionClear(env); return 0; }
    f = (*env)->GetStaticFieldID(env, ver, "SDK_INT", "I");
    if (f) v = (*env)->GetStaticIntField(env, ver, f);
    (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, ver);
    return v;
}

static int jni_is_storage_manager(JNIEnv *env)
{
    jclass envc;
    jmethodID m;
    int r = 0;
    if (jni_sdk_int(env) < 30) return 1;   /* before Android 11 the manifest's storage permission covers it */
    envc = (*env)->FindClass(env, "android/os/Environment");
    if (!envc) { (*env)->ExceptionClear(env); return 0; }
    m = (*env)->GetStaticMethodID(env, envc, "isExternalStorageManager", "()Z");
    if (m) r = (*env)->CallStaticBooleanMethod(env, envc, m) ? 1 : 0;
    (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, envc);
    return r;
}

/* Opens the system page "All files access" for this app. */
static void jni_open_storage_setting(JNIEnv *env, jobject activity)
{
    jclass actc = (*env)->GetObjectClass(env, activity);
    jmethodID getpkg = (*env)->GetMethodID(env, actc, "getPackageName", "()Ljava/lang/String;");
    jstring pkg = getpkg ? (jstring)(*env)->CallObjectMethod(env, activity, getpkg) : NULL;
    const char *p = pkg ? (*env)->GetStringUTFChars(env, pkg, NULL) : NULL;
    char uri[300];
    jclass uric = (*env)->FindClass(env, "android/net/Uri");
    jclass intc = (*env)->FindClass(env, "android/content/Intent");
    jmethodID parse, ctor, start;
    jobject u, in;
    jstring juri, jact;

    snprintf(uri, sizeof uri, "package:%s", p ? p : "");
    if (p) (*env)->ReleaseStringUTFChars(env, pkg, p);
    if (!uric || !intc) { (*env)->ExceptionClear(env); return; }
    parse = (*env)->GetStaticMethodID(env, uric, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
    ctor = (*env)->GetMethodID(env, intc, "<init>", "(Ljava/lang/String;Landroid/net/Uri;)V");
    start = (*env)->GetMethodID(env, actc, "startActivity", "(Landroid/content/Intent;)V");
    juri = (*env)->NewStringUTF(env, uri);
    jact = (*env)->NewStringUTF(env, "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
    u = (*env)->CallStaticObjectMethod(env, uric, parse, juri);
    in = (*env)->NewObject(env, intc, ctor, jact, u);
    if (in) (*env)->CallVoidMethod(env, activity, start, in);
    if ((*env)->ExceptionCheck(env)) {
        /* some phones have no per-app page: the general list */
        jobject in2;
        jmethodID ctor1 = (*env)->GetMethodID(env, intc, "<init>", "(Ljava/lang/String;)V");
        jstring a2 = (*env)->NewStringUTF(env, "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION");
        (*env)->ExceptionClear(env);
        in2 = (*env)->NewObject(env, intc, ctor1, a2);
        if (in2) (*env)->CallVoidMethod(env, activity, start, in2);
        (*env)->ExceptionClear(env);
    }
}

/* 1 when shared storage can be used, asking for the permission once if needed. */
static int ensure_storage_access(void)
{
    JNIEnv *env = (JNIEnv *)SDL_GetAndroidJNIEnv();
    jobject activity = (jobject)SDL_GetAndroidActivity();
    int i, ok;
    if (!env || !activity) return 0;
    if (jni_is_storage_manager(env)) { (*env)->DeleteLocalRef(env, activity); return 1; }
    SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_INFORMATION, "THPS 2X",
        "THPS 2X reads your game from the THPS2X folder on your phone's storage.\n\n"
        "On the next screen, turn on \"Allow access to manage all files\" for THPS 2X, then come back.", NULL);
    jni_open_storage_setting(env, activity);
    ok = 0;
    for (i = 0; i < 600 && !ok; i++) {       /* up to 5 minutes */
        SDL_Delay(500);
        ok = jni_is_storage_manager(env);
    }
    (*env)->DeleteLocalRef(env, activity);
    return ok;
}

/* ── log: stdout/stderr into data/thps2x.log, and logcat ───────── */

static int s_log_pipe[2] = { -1, -1 };
static FILE *s_log_file;
static pthread_mutex_t s_log_lock = PTHREAD_MUTEX_INITIALIZER;

static void *log_thread(void *arg)
{
    char buf[4096], line[4096];
    size_t ln = 0;
    ssize_t n, i;
    (void)arg;
    while ((n = read(s_log_pipe[0], buf, sizeof buf)) > 0) {
        pthread_mutex_lock(&s_log_lock);
        if (s_log_file) { fwrite(buf, 1, (size_t)n, s_log_file); fflush(s_log_file); }
        pthread_mutex_unlock(&s_log_lock);
        for (i = 0; i < n; i++) {               /* logcat line by line */
            if (buf[i] == '\n' || ln == sizeof line - 1) {
                line[ln] = '\0';
                __android_log_write(ANDROID_LOG_INFO, LOG_TAG, line);
                ln = 0;
            } else {
                line[ln++] = buf[i];
            }
        }
    }
    return NULL;
}

static void open_log(const char *data_dir)
{
    char path[HOST_PATH_MAX], prev[HOST_PATH_MAX];
    pthread_t t;
    join(path, data_dir, "thps2x.log");
    join(prev, data_dir, "thps2x.previous.log");
    rename(path, prev);
    s_log_file = fopen(path, "w");
    if (pipe(s_log_pipe) != 0) return;
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    dup2(s_log_pipe[1], STDOUT_FILENO);
    dup2(s_log_pipe[1], STDERR_FILENO);
    pthread_create(&t, NULL, log_thread, NULL);
    pthread_detach(t);
}

static void host_log_drain(void)
{
    fflush(stdout);
    fflush(stderr);
    usleep(150 * 1000);                       /* let the log thread write what is in the pipe */
    pthread_mutex_lock(&s_log_lock);
    if (s_log_file) fflush(s_log_file);
    pthread_mutex_unlock(&s_log_lock);
}

/* ── faults ────────────────────────────────────────────────────── */

static struct sigaction s_old_segv, s_old_bus, s_old_ill, s_old_fpe;

static void crash_write(const char *fmt, ...)
{
    /* the handler: no stdio locks (the faulting thread may hold them) -- straight to the file and logcat */
    char b[512];
    va_list ap;
    int n;
    va_start(ap, fmt);
    n = vsnprintf(b, sizeof b, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n > (int)sizeof b - 1) n = (int)sizeof b - 1;
    if (s_log_file) { write(fileno(s_log_file), b, (size_t)n); }
    __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, b);
}

static void fault_handler(int sig, siginfo_t *si, void *uc_)
{
    ucontext_t *uc = (ucontext_t *)uc_;
    uintptr_t addr = (uintptr_t)si->si_addr;
    uintptr_t pc = (uintptr_t)uc->uc_mcontext.pc;
    struct sigaction *old;

    if (sig == SIGSEGV || sig == SIGBUS) {
        /* page tracking: a guest store to a page a texture or vertex buffer was read from */
        if (xbox_PageTrackFault(addr))
            return;
        if (xbox_RtWatchFault(addr, 0, pc))
            return;
    }
    crash_write("[CRASH] signal %d (code %d) at pc=0x%llX, fault addr=0x%llX\n", sig, si->si_code,
                (unsigned long long)pc, (unsigned long long)addr);
    crash_write("  Xbox VA of fault: 0x%08X\n", (uint32_t)(addr - (uintptr_t)g_xbox_mem_offset));
    crash_write("  Xbox regs: eax=0x%08X ecx=0x%08X edx=0x%08X esp=0x%08X\n", g_eax, g_ecx, g_edx, g_esp);
    crash_write("  Xbox regs: ebx=0x%08X esi=0x%08X edi=0x%08X\n", g_ebx, g_esi, g_edi);
    crash_write("  host lr=0x%llX sp=0x%llX\n", (unsigned long long)uc->uc_mcontext.regs[30],
                (unsigned long long)uc->uc_mcontext.sp);
    {
        Dl_info di;
        if (dladdr((void *)pc, &di) && di.dli_fname)
            crash_write("  in %s+0x%llX (%s)\n", di.dli_fname,
                        (unsigned long long)(pc - (uintptr_t)di.dli_fbase), di.dli_sname ? di.dli_sname : "?");
    }
    RECOMP_ICALL_FEEDBACK_DUMP();
    if (s_log_file) fsync(fileno(s_log_file));

    old = sig == SIGSEGV ? &s_old_segv : sig == SIGBUS ? &s_old_bus : sig == SIGILL ? &s_old_ill : &s_old_fpe;
    sigaction(sig, old, NULL);                /* the system's handler: Android's tombstone */
    raise(sig);
}

static void install_fault_handler(void)
{
    struct sigaction sa;
    static char altstack[64 * 1024];
    stack_t ss;
    ss.ss_sp = altstack;
    ss.ss_size = sizeof altstack;
    ss.ss_flags = 0;
    sigaltstack(&ss, NULL);
    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = fault_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, &s_old_segv);
    sigaction(SIGBUS, &sa, &s_old_bus);
    sigaction(SIGILL, &sa, &s_old_ill);
    sigaction(SIGFPE, &sa, &s_old_fpe);
}

/* ── time limit (test runs) ────────────────────────────────────── */

static void *time_limit_thread(void *arg)
{
    double s = *(double *)arg;
    struct timespec ts = { (time_t)s, (long)((s - (double)(time_t)s) * 1e9) };
    nanosleep(&ts, NULL);
    printf("[HOST] --max-seconds %.1f reached, exiting\n", s);
    host_log_drain();
    _exit(0);
    return NULL;
}

/* ── host.h ────────────────────────────────────────────────────── */

int host_init(int argc, char **argv, HostOptions *opt)
{
    static double s_limit;
    const char *root_env = getenv("THPS2X_ROOT");
    char root[HOST_PATH_MAX];
    int i;

    memset(opt, 0, sizeof(*opt));
    if (root_env && *root_env) {
        snprintf(root, sizeof root, "%s", root_env);
    } else {
        snprintf(root, sizeof root, "/storage/emulated/0/THPS2X");
        if (!ensure_storage_access() || !dir_writable(root)) {
            /* no All files access: the app's own folder (Android/data/<package>/files) */
            const char *own = SDL_GetAndroidExternalStoragePath();
            if (own && *own) snprintf(root, sizeof root, "%s", own);
        }
    }
    {
        const char *own = SDL_GetAndroidInternalStoragePath();
        snprintf(opt->exe_dir, HOST_PATH_MAX, "%s", own ? own : root);
    }
    join(opt->game_dir, root, "game");
    join(opt->save_dir, root, "saves");
    join(opt->data_dir, root, "data");

    for (i = 1; i < argc; i++) {
        const char *a = argv[i], *v = (i + 1 < argc) ? argv[i + 1] : NULL;
        if (!v) break;
        if (!strcmp(a, "--max-seconds")) opt->max_seconds = atof(v);
        else if (!strcmp(a, "--game-dir")) snprintf(opt->game_dir, HOST_PATH_MAX, "%s", v);
        else if (!strcmp(a, "--save-dir")) snprintf(opt->save_dir, HOST_PATH_MAX, "%s", v);
        else if (!strcmp(a, "--data-dir")) snprintf(opt->data_dir, HOST_PATH_MAX, "%s", v);
        else continue;
        i++;
    }
    if (getenv("THPS2X_MAX_SECONDS")) opt->max_seconds = atof(getenv("THPS2X_MAX_SECONDS"));

    /* game/ without default.xbe: a folder of the THPS2X folder that has one (the Xbox ISO Extractor app
     * puts the disc into a folder named after the image) is taken as the game folder. */
    {
        char probe[HOST_PATH_MAX];
        struct stat st;
        join(probe, opt->game_dir, "default.xbe");
        if (stat(probe, &st) != 0) {
            DIR *d = opendir(root);
            struct dirent *de;
            while (d && (de = readdir(d)) != NULL) {
                char sub[HOST_PATH_MAX];
                if (de->d_name[0] == '.') continue;
                join(sub, root, de->d_name);
                join(probe, sub, "default.xbe");
                if (stat(probe, &st) == 0 && S_ISREG(st.st_mode)) {
                    snprintf(opt->game_dir, HOST_PATH_MAX, "%s", sub);
                    break;
                }
            }
            if (d) closedir(d);
        }
    }

    mkdirs(opt->game_dir);
    mkdirs(opt->save_dir);
    mkdirs(opt->data_dir);
    snprintf(s_data_dir, sizeof s_data_dir, "%s", opt->data_dir);
    open_log(opt->data_dir);
    if (chdir(opt->data_dir) != 0)
        fprintf(stderr, "[HOST] cannot enter %s: %s\n", opt->data_dir, strerror(errno));
    printf("[HOST] Android, folder %s\n", root);

    /* The Xbox has one CPU and titles rely on it (plain-store flags between threads). x86 hosts get
     * away with it through their strong memory order; an arm64 phone does not, so one guest thread
     * runs at a time here (toolkit kernel_bridge.c, the guest CPU lock). RECOMP_GUEST_LOCK=0 turns it off. */
    host_setenv_default("RECOMP_GUEST_LOCK", "1");

    RECOMP_ICALL_FEEDBACK_INIT();
    install_fault_handler();
    xbox_PageTrackEnable();

    if (opt->max_seconds > 0) {
        pthread_t t;
        s_limit = opt->max_seconds;
        pthread_create(&t, NULL, time_limit_thread, &s_limit);
        pthread_detach(t);
    }
    return 0;
}

void host_setenv_default(const char *name, const char *value)
{
    if (!getenv(name))
        setenv(name, value, 1);
}

void host_setenv(const char *name, const char *value)
{
    if (value && *value) setenv(name, value, 1);
    else unsetenv(name);
}

void *host_read_file(const char *path, size_t *out_size)
{
    FILE *f = fopen(path, "rb");
    long size;
    void *data;

    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    size = ftell(f);
    fseek(f, 0, SEEK_SET);
    data = size > 0 ? malloc((size_t)size) : NULL;
    if (data && fread(data, 1, (size_t)size, f) != (size_t)size) {
        free(data);
        data = NULL;
    }
    fclose(f);
    if (data) *out_size = (size_t)size;
    return data;
}

int host_write_file_atomic(const char *path, const char *pending_dir, const void *data, size_t n)
{
    char tmp[HOST_PATH_MAX];
    const char *base = path + strlen(path);
    FILE *f;
    int ok;
    while (base > path && base[-1] != '/' && base[-1] != '\\')
        base--;
    mkdirs(pending_dir);
    join(tmp, pending_dir, base);
    if (!(f = fopen(tmp, "wb")))
        return 0;
    ok = fwrite(data, 1, n, f) == n;
    ok = fflush(f) == 0 && ok;
    ok = fsync(fileno(f)) == 0 && ok;
    ok = fclose(f) == 0 && ok;
    if (!ok)
        return 0;
    return rename(tmp, path) == 0;
}

void host_debug_start(void)
{
    /* THPS_WATCH_WRITE (Windows debug aid) is not built for Android. */
}

void host_quit(void)
{
    printf("[HOST] window closed\n");
    host_log_drain();
    _exit(0);
}

void host_renderer_failed(void)
{
    host_log_drain();
    _exit(3);
}

void host_restart(void)
{
    /* An Android app cannot start itself again from native code without its activity's help:
     * the settings are saved already, the player opens the game again. */
    SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_INFORMATION, "THPS 2X",
                             "The new settings apply the next time you open THPS 2X. The game closes now.", NULL);
    host_quit();
}

void host_fatal(const char *msg)
{
    fprintf(stderr, "[FATAL] %s\n", msg);
    host_log_drain();
    if (getenv("RECOMP_NO_DIALOGS") && *getenv("RECOMP_NO_DIALOGS") == '1')
        return;
    SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "THPS 2X Recomp", msg, NULL);
}
