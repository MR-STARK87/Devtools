package com.bucket;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Offline outbox for shares made while the laptop is out of reach.
 *
 *  Share-sheet content URIs are ephemeral: the read grant dies with the
 *  activity and the source app may not even be running later. So a queued
 *  share is not a list of URIs — the bytes are copied into app-private
 *  storage at share time, and only the copy is replayed later.
 *
 *  Layout: <filesDir>/outbox/<seq>/ containing the blob files (f0, f1, ...)
 *  plus meta.json written LAST. A crash mid-store leaves a nameless
 *  directory, which the next scan deletes — never a half-described entry.
 *  Same blob-before-index contract as the server's Store.
 */
final class Outbox {
    /** App-private storage is never swept by the OS, so the queue needs its
     *  own ceiling. Refusing the share is honest; silently eating the disk
     *  is not. */
    static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;

    private static final String ROOT = "outbox";
    private static final String META = "meta.json";

    static final class SavedFile {
        final File file;
        final String name;
        final String mime;

        SavedFile(File file, String name, String mime) {
            this.file = file;
            this.name = name;
            this.mime = mime;
        }
    }

    static final class Entry {
        final File dir;
        final String text;
        final List<SavedFile> files;
        /** Chosen at share time: bundle the batch into one zip on flush. */
        final boolean zip;

        Entry(File dir, String text, List<SavedFile> files, boolean zip) {
            this.dir = dir;
            this.text = text;
            this.files = files;
            this.zip = zip;
        }
    }

    private Outbox() {}

    static File root(Context c) {
        return new File(c.getFilesDir(), ROOT);
    }

    /** Copies the shared bytes into the outbox now. Returns the number of
     *  pending entries afterwards, or -1 when the store was refused (outbox
     *  full) or failed. */
    static int store(Context c, List<Uri> uris, String text, boolean zip) {
        File root = root(c);
        root.mkdirs();
        long budget = MAX_TOTAL_BYTES - totalBytes(root);
        if (budget <= 0) return -1;

        File dir = new File(root, String.format(Locale.US, "%013d", nextSeq(root)));
        if (!dir.mkdirs()) return -1;

        List<SavedFile> saved = new ArrayList<>();
        try {
            ContentResolver cr = c.getContentResolver();
            for (int i = 0; i < uris.size(); i++) {
                Uri uri = uris.get(i);
                File target = new File(dir, "f" + i);
                long copied = copyBounded(cr.openInputStream(uri), target, budget);
                if (copied < 0) throw new IllegalArgumentException("outbox full");
                budget -= copied;
                String mime = cr.getType(uri);
                if (mime == null) mime = "application/octet-stream";
                saved.add(new SavedFile(target, Uploader.displayName(cr, uri), mime));
            }

            JSONObject meta = new JSONObject();
            meta.put("queued_at", System.currentTimeMillis());
            if (text != null && !text.isEmpty()) meta.put("text", text);
            // The choice is resolved at share time (the user is there to be
            // asked); flush only replays it. Absent key = separate, so
            // entries queued by older app versions still flush unchanged.
            if (zip) meta.put("bundle", "zip");
            JSONArray files = new JSONArray();
            for (SavedFile f : saved) {
                JSONObject o = new JSONObject();
                o.put("file", f.file.getName());
                o.put("name", f.name);
                o.put("mime", f.mime);
                files.put(o);
            }
            if (files.length() > 0) meta.put("files", files);

            // Meta last — see class comment.
            OutputStream out = new FileOutputStream(new File(dir, META));
            out.write(meta.toString().getBytes("UTF-8"));
            out.close();
            return entries(root).size();
        } catch (Exception e) {
            delete(dir);
            return -1;
        }
    }

    /** Uploads queued entries, oldest first, until one fails (server gone,
     *  phone not paired). Entries that sent are deleted; the rest stay for
     *  the next attempt. Returns {sent, remaining}. */
    static int[] flush(Context c, String base) {
        int sent = 0;
        List<Entry> pending = entries(root(c));
        for (Entry entry : pending) {
            String cookie = Uploader.cookieFor(base);
            if (cookie == null) break;
            List<Uploader.Part> parts = new ArrayList<>();
            for (final SavedFile f : entry.files) {
                parts.add(new Uploader.Part(f.name, f.mime, new Uploader.Source() {
                    @Override public InputStream open() throws Exception {
                        return new FileInputStream(f.file);
                    }
                }));
            }
            if (!Uploader.post(base, cookie, parts, entry.text, entry.zip)) break;
            delete(entry.dir);
            sent++;
        }
        return new int[]{sent, entries(root(c)).size()};
    }

    /** Valid entries, oldest first. Dirs without a parseable meta.json are
     *  crash leftovers and get swept here. */
    private static List<Entry> entries(File root) {
        List<Entry> out = new ArrayList<>();
        File[] dirs = root.listFiles();
        if (dirs == null) return out;
        Arrays.sort(dirs, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File meta = new File(dir, META);
            if (!meta.isFile()) {
                delete(dir);
                continue;
            }
            try {
                JSONObject m = readJson(meta);
                String text = m.optString("text", null);
                List<SavedFile> files = new ArrayList<>();
                JSONArray arr = m.optJSONArray("files");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        File f = new File(dir, o.getString("file"));
                        if (!f.isFile()) throw new IllegalArgumentException("missing blob");
                        files.add(new SavedFile(f, o.getString("name"), o.getString("mime")));
                    }
                }
                if (files.isEmpty() && (text == null || text.isEmpty())) {
                    delete(dir); // nothing to send
                    continue;
                }
                boolean zip = "zip".equals(m.optString("bundle", ""));
                out.add(new Entry(dir, text, files, zip));
            } catch (Exception e) {
                // Unreadable meta: crash during write, or a future format we
                // do not understand. Dropping it is better than retrying it
                // forever.
                delete(dir);
            }
        }
        return out;
    }

    private static long totalBytes(File root) {
        long total = 0;
        File[] dirs = root.listFiles();
        if (dirs == null) return 0;
        for (File dir : dirs) {
            File[] blobs = dir.listFiles();
            if (blobs == null) continue;
            for (File f : blobs) total += f.length();
        }
        return total;
    }

    private static long nextSeq(File root) {
        long max = 0;
        File[] dirs = root.listFiles();
        if (dirs != null) {
            for (File dir : dirs) {
                try {
                    max = Math.max(max, Long.parseLong(dir.getName()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return max + 1;
    }

    /** Copies up to `budget` bytes; returns the count, or -1 over budget. */
    private static long copyBounded(InputStream in, File target, long budget)
            throws Exception {
        long copied = 0;
        OutputStream out = new FileOutputStream(target);
        byte[] buf = new byte[64 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                copied += n;
                if (copied > budget) return -1;
                out.write(buf, 0, n);
            }
            return copied;
        } finally {
            out.close();
            in.close();
        }
    }

    private static JSONObject readJson(File f) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        InputStream in = new FileInputStream(f);
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        in.close();
        return new JSONObject(buf.toString("UTF-8"));
    }

    private static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }
}
