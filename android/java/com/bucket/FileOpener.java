package com.bucket;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.webkit.CookieManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

/** System-default opener for everything that is not an image.
 *
 *  Images stay inside Bucket (ViewerActivity); every other type is staged to
 *  getCacheDir()/shared and fired at ACTION_VIEW with the real MIME type, so
 *  the phone's own PDF reader / video player / office app handles it. The
 *  bytes cannot be handed off as a bare http URL — the receiving app would
 *  arrive unpaired and get a 401 — hence the download-then-view round trip.
 *  Served through CacheProvider so no file:// URI ever escapes (banned since
 *  API 24) and no androidx FileProvider artifact is needed. */
final class FileOpener {
    private static final long MAX_CACHE_BYTES = 100L * 1024 * 1024;

    private FileOpener() {}

    static void open(final Activity activity, final String url,
            final String name, final String mime) {
        if (activity == null || url == null) return;
        final String safe = safeName(name);
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(activity, "Opening " + pretty(safe),
                        Toast.LENGTH_SHORT).show();
            }
        });
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File file = download(activity, url, safe);
                    final Uri uri = CacheProvider.uriFor(file);
                    final String effMime = effMime(mime, file.getName());
                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (activity.isFinishing()) return;
                            Intent view = new Intent(Intent.ACTION_VIEW);
                            view.setDataAndType(uri, effMime);
                            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            try {
                                activity.startActivity(
                                        Intent.createChooser(view, "Open with"));
                            } catch (ActivityNotFoundException e) {
                                Toast.makeText(activity,
                                        "No app can open this file",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } catch (final Exception e) {
                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            String reason = e.getMessage();
                            Toast.makeText(activity,
                                    "Could not open: " + (reason == null
                                            || reason.isEmpty()
                                            ? e.getClass().getSimpleName()
                                            : reason),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private static File download(Activity activity, String url, String safe)
            throws Exception {
        File dir = new File(activity.getCacheDir(), "shared");
        dir.mkdirs();
        trim(dir);
        File dest = uniqueFile(dir, safe);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(60000);
            conn.setUseCaches(false);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("server said " + code);
            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(dest);
            copy(in, out);
            out.close();
            in.close();
            return dest;
        } catch (Exception e) {
            dest.delete();
            throw e;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String effMime(String mime, String fileName) {
        if (mime != null && !mime.trim().isEmpty() && !mime.contains("*")) {
            return mime;
        }
        String guessed = URLConnection.guessContentTypeFromName(fileName);
        return guessed != null ? guessed : "application/octet-stream";
    }

    private static String safeName(String name) {
        String safe = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "file" : safe;
    }

    private static String pretty(String name) {
        return name.length() > 32 ? name.substring(0, 29) + "\u2026" : name;
    }

    private static File uniqueFile(File dir, String name) {
        File file = new File(dir, name);
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            file = new File(dir, base + " (" + i + ")" + ext);
            if (!file.exists()) return file;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void trim(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            long total = 0;
            for (File f : files) total += f.length();
            if (total <= MAX_CACHE_BYTES) return;
            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (File f : files) {
                if (total <= MAX_CACHE_BYTES) break;
                total -= f.length();
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
