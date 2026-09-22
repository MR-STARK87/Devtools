package com.bucket;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Locating the laptop, and remembering where it was last seen.
 *
 *  The laptop's address is handed out by this phone's own hotspot DHCP, so it
 *  is not stable enough to hard-code. Rather than ask the user to retype an
 *  address, sweep the local /24 for the health endpoint.
 */
final class Server {
    static final int PORT = 8765;
    private static final String PREFS = "bucket";
    private static final String KEY_BASE = "base_url";
    private static final String KEY_BATCH = "batch_mode";

    /** The multi-file answer: ask, zip, or separate. Mirrors the server's
     *  /api/batch-mode so the share sheet can decide offline; refreshed
     *  from the server whenever the laptop is reachable. */
    static final String BATCH_ASK = "ask";
    static final String BATCH_ZIP = "zip";
    static final String BATCH_SEPARATE = "separate";

    static String batchMode(Context c) {
        String mode = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_BATCH, BATCH_ASK);
        return isBatchMode(mode) ? mode : BATCH_ASK;
    }

    static void saveBatchMode(Context c, String mode) {
        if (!isBatchMode(mode)) return;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_BATCH, mode).apply();
    }

    static boolean isBatchMode(String mode) {
        return BATCH_ZIP.equals(mode) || BATCH_SEPARATE.equals(mode)
                || BATCH_ASK.equals(mode);
    }

    /** Reads the account-wide mode from the server. Needs the pairing
     *  cookie (the endpoint is device-authenticated). Null on any failure —
     *  callers keep their cached mirror. */
    static String fetchBatchMode(String base, String cookie) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/api/batch-mode").openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Cookie", cookie);
            if (conn.getResponseCode() != 200) return null;
            String body = readAtMost(conn.getInputStream(), 64);
            // {"mode":"zip"} — parse leniently, validate strictly.
            int at = body.indexOf("\"mode\"");
            if (at < 0) return null;
            int q1 = body.indexOf('"', at + 6);
            if (q1 < 0) return null;
            int q2 = body.indexOf('"', q1 + 1);
            if (q2 < 0) return null;
            String mode = body.substring(q1 + 1, q2);
            return isBatchMode(mode) ? mode : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Pushes a remembered choice to the server so the PC page and any
     *  other surface follow it. Best-effort: the local mirror is already
     *  saved, so a failure here only delays convergence. */
    static void pushBatchMode(String base, String cookie, String mode) {
        HttpURLConnection conn = null;
        try {
            byte[] body = ("{\"mode\":\"" + mode + "\"}").getBytes("UTF-8");
            conn = (HttpURLConnection) new URL(base + "/api/batch-mode").openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setFixedLengthStreamingMode(body.length);
            conn.getOutputStream().write(body);
            conn.getOutputStream().close();
            conn.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private Server() {}

    static String saved(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_BASE, null);
    }

    static void save(Context c, String base) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_BASE, base).apply();
    }

    static String baseFor(String host) {
        return "http://" + host + ":" + PORT;
    }

    /** True when a Bucket server answers at this origin. */
    static boolean alive(String base, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/api/health").openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setUseCaches(false);
            if (conn.getResponseCode() != 200) return false;
            String body = readAtMost(conn.getInputStream(), 512);
            // Confirm it is Bucket, not just something listening on 8765.
            return body.contains("\"ok\"") && body.contains("\"origin\"");
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Saved address if it still answers, else a subnet sweep. Null if not found. */
    static String discover(Context ctx) {
        String remembered = saved(ctx);
        if (remembered != null && alive(remembered, 1200)) return remembered;

        for (String prefix : localPrefixes()) {
            String found = sweep(prefix);
            if (found != null) {
                save(ctx, found);
                return found;
            }
        }
        return null;
    }

    /** Probe every host in a /24 at once; first Bucket to answer wins. */
    private static String sweep(final String prefix) {
        ExecutorService pool = Executors.newFixedThreadPool(48);
        final List<String> hits = Collections.synchronizedList(new ArrayList<String>());
        try {
            for (int i = 1; i <= 254; i++) {
                final String base = baseFor(prefix + i);
                pool.execute(new Runnable() {
                    @Override public void run() {
                        if (hits.isEmpty() && alive(base, 900)) hits.add(base);
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(25, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** "192.168.239." style prefixes for every IPv4 /24 this phone is on. */
    private static Set<String> localPrefixes() {
        Set<String> out = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr == null || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.indexOf(':') >= 0) continue;
                    int cut = ip.lastIndexOf('.');
                    if (cut > 0) out.add(ip.substring(0, cut + 1));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String readAtMost(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[256];
        int n;
        while (buf.size() < limit && (n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        in.close();
        return buf.toString("UTF-8");
    }
}
