package com.dictate;

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
 *  address, sweep the local /24 for the health endpoint. Identical strategy
 *  to Bucket's Server.java; only the port and prefs name differ. */
final class Server {
    static final int PORT = 8766;
    private static final String PREFS = "dictate";
    private static final String KEY_BASE = "base_url";

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

    /** True when a Dictate server answers at this origin. */
    static boolean alive(String base, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/api/health").openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setUseCaches(false);
            if (conn.getResponseCode() != 200) return false;
            String body = readAtMost(conn.getInputStream(), 512);
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

    /** Probe every host in a /24 at once; first Dictate to answer wins. */
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