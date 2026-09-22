package com.bucket;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/** The one place that talks to POST /api/items.
 *
 *  ShareActivity uses it for the direct path (server reachable right now) and
 *  Outbox uses it to flush queued shares once the laptop is back. Keeping the
 *  multipart wire format in one class means both paths send byte-identical
 *  requests.
 */
final class Uploader {
    private static final String TAG = "Bucket";

    /** A file part: display name, MIME type, and a lazily opened byte source.
     *  The source is opened at write time so callers can stream from content
     *  URIs or plain files with the same code. */
    interface Source {
        InputStream open() throws Exception;
    }

    static final class Part {
        final String filename;
        final String mime;
        final Source source;

        Part(String filename, String mime, Source source) {
            this.filename = filename;
            this.mime = mime;
            this.source = source;
        }
    }

    private Uploader() {}

    /** The pairing cookie for this origin, or null when the phone is not
     *  paired. The cookie itself is never logged — it is the credential. */
    static String cookieFor(String base) {
        String cookie = CookieManager.getInstance().getCookie(base);
        if (cookie == null || !cookie.contains("bucket_token")) {
            Log.w(TAG, "upload: not paired yet, open the app first");
            return null;
        }
        return cookie;
    }

    /** POSTs the parts (and optional text) as multipart to /api/items.
     *  When zip is true the server bundles a multi-file batch into one .zip
     *  item instead of one item per file (a lone file is stored as-is —
     *  the server ignores the flag unless there are 2+ files). True on
     *  any 2xx. */
    static boolean post(String base, String cookie, List<Part> parts, String text,
            boolean zip) {
        String boundary = "----bucket" + System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/api/items").openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(60000);
            conn.setChunkedStreamingMode(0);
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);

            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            for (Part part : parts) writeFile(out, boundary, part);
            if (text != null && !text.isEmpty()) writeText(out, boundary, text);
            if (zip) writeField(out, boundary, "bundle", "zip");
            out.writeBytes("--" + boundary + "--\r\n");
            out.flush();
            out.close();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) Log.w(TAG, "upload: server said " + code);
            return code >= 200 && code < 300;
        } catch (Exception e) {
            Log.w(TAG, "upload failed", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Content URIs rarely carry a usable name; ask the provider for one. */
    static String displayName(ContentResolver cr, Uri uri) {
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0) {
                    String n = c.getString(col);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String path = uri.getLastPathSegment();
        return path == null ? "shared" : path;
    }

    private static void writeFile(DataOutputStream out, String boundary, Part part)
            throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"files\"; filename=\""
                + part.filename.replace("\"", "") + "\"\r\n");
        out.writeBytes("Content-Type: " + part.mime + "\r\n\r\n");
        InputStream in = part.source.open();
        if (in != null) {
            copy(in, out);
            in.close();
        }
        out.writeBytes("\r\n");
    }

    private static void writeText(DataOutputStream out, String boundary, String text)
            throws Exception {
        writeField(out, boundary, "text", text);
    }

    private static void writeField(DataOutputStream out, String boundary,
            String name, String value) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n");
        out.writeBytes("Content-Type: text/plain; charset=utf-8\r\n\r\n");
        out.write(value.getBytes("UTF-8"));
        out.writeBytes("\r\n");
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
