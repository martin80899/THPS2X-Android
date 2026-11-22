package io.github.martin80899.xisoextractor;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Xbox ISO Extractor: reads an original Xbox disc image (XDVDFS, a plain XISO or a full dump) through
 * Android's file picker and saves default.xbe, or every file into a folder the user picks. The image
 * is only read. No permissions: all files go through the system pickers.
 */
public class MainActivity extends Activity {
    private static final int PICK_ISO = 1, SAVE_XBE = 2, PICK_FOLDER = 3;
    private static final int SECTOR = 2048;
    private static final long[] BASES = { 0L, 0x18300000L, 0x0FD90000L, 0x02080000L };
    private static final String THPS2X_USA_SHA1 = "469818e015aed2730641c0bdcbd2557d8a6b8bb4";

    /** A file or folder in the image. */
    static final class Entry {
        final String path; final long sector, size; final boolean dir;
        Entry(String path, long sector, long size, boolean dir) {
            this.path = path; this.sector = sector; this.size = size; this.dir = dir;
        }
    }

    private Uri iso;
    private String isoName = "";
    private long base;
    private List<Entry> entries;
    private Entry xbe;

    private TextView status, log;
    private Button pickBtn, xbeBtn, allBtn;
    private ProgressBar progress;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy;

    // ---------------------------------------------------------------- UI

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(20);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Xbox ISO Extractor");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Pick an original Xbox disc image (.iso). Save its default.xbe, or extract every file "
                + "into a folder. The image itself is never changed.");
        sub.setPadding(0, dp(8), 0, dp(16));
        col.addView(sub);

        pickBtn = button("Pick disc image (.iso)", v -> pickIso());
        xbeBtn = button("Save default.xbe", v -> saveXbe());
        allBtn = button("Extract all files to a folder", v -> pickFolder());
        col.addView(pickBtn);
        col.addView(xbeBtn);
        col.addView(allBtn);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        pl.topMargin = dp(16);
        col.addView(progress, pl);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(12), 0, dp(8));
        col.addView(status);

        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setTextColor(Color.GRAY);
        col.addView(log);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
        refresh();
    }

    private Button button(String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        btn.setGravity(Gravity.CENTER);
        return btn;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void refresh() {
        pickBtn.setEnabled(!busy);
        xbeBtn.setEnabled(!busy && xbe != null);
        allBtn.setEnabled(!busy && entries != null);
        if (iso == null && !busy) status.setText("No disc image picked yet.");
    }

    private void setStatus(String s) { ui.post(() -> status.setText(s)); }
    private void addLog(String s) { ui.post(() -> log.append(s + "\n")); }
    private void setProgress(long done, long total) {
        int v = total > 0 ? (int) (done * 1000 / total) : 0;
        ui.post(() -> progress.setProgress(v));
    }

    private void run(String what, Job job) {
        busy = true;
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        refresh();
        status.setText(what + "…");
        new Thread(() -> {
            try {
                job.run();
            } catch (Exception e) {
                setStatus("Error: " + e.getMessage());
                addLog(e.toString());
            } finally {
                ui.post(() -> { busy = false; progress.setVisibility(View.GONE); refresh(); });
            }
        }, "xiso").start();
    }

    interface Job { void run() throws Exception; }

    // ---------------------------------------------------------------- pickers

    private void pickIso() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_ISO);
    }

    private void saveXbe() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, "default.xbe");
        startActivityForResult(i, SAVE_XBE);
    }

    private void pickFolder() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (req == PICK_ISO) {
            iso = u;
            isoName = displayName(u);
            entries = null; xbe = null;
            log.setText("");
            run("Reading " + isoName, this::scan);
        } else if (req == SAVE_XBE) {
            run("Saving default.xbe", () -> writeXbe(u));
        } else if (req == PICK_FOLDER) {
            run("Extracting", () -> extractAll(u));
        }
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return "disc image";
    }

    // ---------------------------------------------------------------- XDVDFS reading

    private static final class Image implements AutoCloseable {
        final ParcelFileDescriptor pfd; final FileInputStream in; final FileChannel ch;
        Image(ContentResolver cr, Uri u) throws IOException {
            pfd = cr.openFileDescriptor(u, "r");
            if (pfd == null) throw new IOException("cannot open the image");
            in = new FileInputStream(pfd.getFileDescriptor());
            ch = in.getChannel();
        }
        byte[] read(long pos, int n) throws IOException {
            ByteBuffer bb = ByteBuffer.allocate(n);
            while (bb.hasRemaining()) {
                int r = ch.read(bb, pos + bb.position());
                if (r < 0) break;
            }
            return bb.array();
        }
        @Override public void close() throws IOException { in.close(); pfd.close(); }
    }

    private void scan() throws Exception {
        try (Image img = new Image(getContentResolver(), iso)) {
            long rootSector = -1, rootSize = 0;
            for (long b : BASES) {
                byte[] d = img.read(b + 32L * SECTOR, 0x1C);
                if (new String(d, 0, 20, "ISO-8859-1").equals("MICROSOFT*XBOX*MEDIA")) {
                    ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
                    base = b;
                    rootSector = bb.getInt(20) & 0xFFFFFFFFL;
                    rootSize = bb.getInt(24) & 0xFFFFFFFFL;
                    break;
                }
            }
            if (rootSector < 0) {
                setStatus("This is not an Xbox disc image (no Xbox file system found).");
                return;
            }
            addLog(String.format("Xbox file system at 0x%X", base));
            List<Entry> list = new ArrayList<>();
            ArrayDeque<Object[]> dirs = new ArrayDeque<>();
            dirs.add(new Object[] { "", rootSector, rootSize });
            while (!dirs.isEmpty()) {
                Object[] d = dirs.poll();
                readDir(img, (String) d[0], (Long) d[1], (Long) d[2], list, dirs);
            }
            entries = list;
            long total = 0;
            int files = 0;
            for (Entry e : list) {
                if (!e.dir) { total += e.size; files++; }
                if (!e.dir && e.path.equalsIgnoreCase("default.xbe")) xbe = e;
            }
            addLog(files + " files, " + mb(total) + " in total");
            String s = isoName + ": " + files + " files.";
            if (xbe != null) {
                byte[] x = img.read(base + xbe.sector * SECTOR, (int) Math.min(xbe.size, Integer.MAX_VALUE));
                String sha = sha1(x);
                addLog("default.xbe: " + xbe.size + " bytes, SHA-1 " + sha);
                if (!new String(x, 0, 4, "ISO-8859-1").equals("XBEH")) s += " default.xbe does not look like an XBE.";
                else if (sha.equals(THPS2X_USA_SHA1)) s += " This is THPS 2X (USA): the version the Android port needs.";
                else s += " default.xbe found.";
            } else {
                s += " No default.xbe in the image.";
            }
            setStatus(s);
        }
    }

    private void readDir(Image img, String prefix, long sector, long size, List<Entry> out, ArrayDeque<Object[]> dirs)
            throws IOException {
        if (size <= 0 || size > (64 << 20)) return;
        byte[] t = img.read(base + sector * SECTOR, (int) size);
        ByteBuffer bb = ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN);
        ArrayDeque<Integer> todo = new ArrayDeque<>();
        HashSet<Integer> seen = new HashSet<>();
        todo.add(0);
        while (!todo.isEmpty()) {
            int off = todo.pop();
            if (!seen.add(off) || off + 14 > t.length) continue;
            int left = bb.getShort(off) & 0xFFFF, right = bb.getShort(off + 2) & 0xFFFF;
            if (left == 0xFFFF && right == 0xFFFF) continue;              // padding
            long fsec = bb.getInt(off + 4) & 0xFFFFFFFFL, fsize = bb.getInt(off + 8) & 0xFFFFFFFFL;
            int attr = t[off + 12] & 0xFF, nlen = t[off + 13] & 0xFF;
            if (off + 14 + nlen > t.length) continue;
            String name = new String(t, off + 14, nlen, "ISO-8859-1");
            if (!name.isEmpty() && !name.contains("/") && !name.equals(".") && !name.equals("..")) {
                String path = prefix.isEmpty() ? name : prefix + "/" + name;
                boolean dir = (attr & 0x10) != 0;
                out.add(new Entry(path, fsec, fsize, dir));
                if (dir) dirs.add(new Object[] { path, fsec, fsize });
            }
            if (left != 0) todo.push(left * 4);
            if (right != 0) todo.push(right * 4);
        }
    }

    // ---------------------------------------------------------------- writing

    private void writeXbe(Uri dst) throws Exception {
        try (Image img = new Image(getContentResolver(), iso);
             OutputStream os = getContentResolver().openOutputStream(dst, "wt")) {
            if (os == null) throw new IOException("cannot write the file");
            copy(img, xbe, os, new long[] { 0 }, xbe.size);
        }
        setStatus("Saved default.xbe (" + xbe.size + " bytes).");
    }

    private void extractAll(Uri tree) throws Exception {
        ContentResolver cr = getContentResolver();
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        String folder = isoName.replaceAll("(?i)\\.iso$", "").trim();
        if (folder.isEmpty()) folder = "Xbox game";
        Uri top = DocumentsContract.createDocument(cr, root, DocumentsContract.Document.MIME_TYPE_DIR, folder);
        if (top == null) throw new IOException("cannot create a folder there");
        addLog("into folder: " + folder);
        java.util.HashMap<String, Uri> made = new java.util.HashMap<>();
        made.put("", top);
        long total = 0;
        for (Entry e : entries) if (!e.dir) total += e.size;
        long[] done = { 0 };
        int n = 0;
        try (Image img = new Image(cr, iso)) {
            for (Entry e : entries) {                // parents come before their children (breadth first)
                int slash = e.path.lastIndexOf('/');
                String parent = slash < 0 ? "" : e.path.substring(0, slash);
                String name = slash < 0 ? e.path : e.path.substring(slash + 1);
                Uri p = made.get(parent);
                if (p == null) continue;
                if (e.dir) {
                    Uri d = DocumentsContract.createDocument(cr, p, DocumentsContract.Document.MIME_TYPE_DIR, name);
                    if (d != null) made.put(e.path, d);
                    continue;
                }
                Uri f = DocumentsContract.createDocument(cr, p, "application/octet-stream", name);
                if (f == null) { addLog("could not create " + e.path); continue; }
                try (OutputStream os = cr.openOutputStream(f, "wt")) {
                    if (os == null) throw new IOException("cannot write " + e.path);
                    copy(img, e, os, done, total);
                }
                n++;
                setStatus("Extracting " + n + " files, " + mb(done[0]) + " of " + mb(total));
            }
        }
        setStatus("Done: " + n + " files (" + mb(total) + ") in \"" + folder + "\".");
    }

    private void copy(Image img, Entry e, OutputStream os, long[] done, long total) throws IOException {
        long pos = base + e.sector * SECTOR, left = e.size;
        ByteBuffer bb = ByteBuffer.allocate(1 << 20);
        while (left > 0) {
            bb.clear();
            if (left < bb.capacity()) bb.limit((int) left);
            int r = img.ch.read(bb, pos);
            if (r <= 0) throw new IOException("the image ends inside " + e.path);
            os.write(bb.array(), 0, r);
            pos += r; left -= r; done[0] += r;
            setProgress(done[0], total);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String mb(long bytes) { return String.format("%.1f MB", bytes / 1048576.0); }

    private static String sha1(byte[] d) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-1").digest(d);
        StringBuilder sb = new StringBuilder();
        for (byte x : h) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
