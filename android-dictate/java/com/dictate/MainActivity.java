package com.dictate;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Hosts the Dictate page. Studio Paper rework: light paper desk with ink
 *  and vermilion signal. The WebView is the whole client; this class only
 *  finds the LAN server and shows a light loading state that matches the
 *  web theme so the cold start never flashes dark. */
public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 1001;
    private static final int REQ_SCAN_QR = 2001;
    private WebView web;
    private LinearLayout status;
    private TextView statusText;
    // A WebKit permission request that arrived before the runtime grant.
    // WebKit does NOT re-ask on its own, so without keeping this the page's
    // getUserMedia promise hangs forever (black preview, no error).
    private PermissionRequest pendingWebPermission;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Light paper chrome -- matches dictate/static/app.css --paper / --ground
        getWindow().setStatusBarColor(0xFFFDFCF8);
        getWindow().setNavigationBarColor(0xFFFDFCF8);
        // Dark icons on light status bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFF5F1E8);

        web = new WebView(this);
        web.setBackgroundColor(0xFFFDFCF8);
        web.setVisibility(View.GONE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // Needed for getUserMedia inside the pairing gate QR scanner
        // (kept as a fallback for browsers; the app itself prefers the
        // native ScanActivity via the DictateNative bridge below, because
        // plain-HTTP LAN origins are not a secure context for WebView).
        // The page is the whole client and is versioned by the server; a
        // stale cached copy strands UI fixes on the phone (the server also
        // sends no-cache, but the WebView's heuristic cache is not worth
        // trusting). Always load from the LAN server.
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new NativeBridge(), "DictateNative");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    String[] resources = request.getResources();
                    boolean hasVideo = false;
                    for (String r : resources) {
                        if (r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) { hasVideo = true; break; }
                    }
                    if (hasVideo) {
                        // If we still need runtime permission, ask the system
                        // and grant/deny when it answers — dropping the
                        // request here leaves getUserMedia pending forever.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                && checkSelfPermission(Manifest.permission.CAMERA)
                                != PackageManager.PERMISSION_GRANTED) {
                            if (pendingWebPermission != null) {
                                try { pendingWebPermission.deny(); }
                                catch (Exception ignored) {}
                            }
                            pendingWebPermission = request;
                            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                            return;
                        }
                    }
                    try {
                        request.grant(resources);
                    } catch (Exception e) {
                        try { request.deny(); } catch (Exception ignored) {}
                    }
                }
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (request == pendingWebPermission) pendingWebPermission = null;
            }
        });
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        // Pre-ask camera so the scan button doesn't need a second tap
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }

        status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0xFFFDFCF8);
        status.setPadding(64, 64, 64, 64);

        statusText = new TextView(this);
        statusText.setTextColor(0xFF121416);
        statusText.setTextSize(15f);
        statusText.setGravity(Gravity.CENTER);
        statusText.setText("Looking for your laptop...");
        status.addView(statusText);

        Button retry = new Button(this);
        retry.setText("Search again");
        retry.setAllCaps(false);
        // Keep native button but slightly paper-toned
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resolveThenLoad(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = 32;
        status.addView(retry, lp);

        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        web.clearCache(true);
        resolveThenLoad();
    }

    private void resolveThenLoad() {
        show("Looking for your laptop...");
        new Thread(new Runnable() {
            @Override public void run() {
                final String found = Server.discover(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (found == null) {
                            show("Could not find Dictate on this network.\n\n"
                                    + "Start it on the laptop, make sure the laptop is "
                                    + "on this phone's hotspot, then search again.");
                            return;
                        }
                        status.setVisibility(View.GONE);
                        web.setVisibility(View.VISIBLE);
                        web.loadUrl(found + "/");
                    }
                });
            }
        }).start();
    }

    private void show(String message) {
        statusText.setText(message);
        status.setVisibility(View.VISIBLE);
        web.setVisibility(View.GONE);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code != REQ_CAMERA) return;
        boolean granted = grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED;
        // Settle the deferred WebKit request, if any. WebKit does not re-ask
        // on its own, so without this the getUserMedia promise hangs.
        if (pendingWebPermission != null) {
            try {
                if (granted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        pendingWebPermission.grant(
                                pendingWebPermission.getResources());
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        pendingWebPermission.deny();
                    }
                }
            } catch (Exception ignored) {
            }
            pendingWebPermission = null;
        }
        // If denied, the web UI shows "camera blocked" and falls back to
        // code entry; the native scanner toasts on its own behalf.
    }

    /** Bridge called from the pairing gate: the page prefers this over the
     *  web BarcodeDetector path, which WebView does not expose over HTTP. */
    private class NativeBridge {
        @JavascriptInterface
        public boolean hasNativeScanner() {
            return true;
        }

        @JavascriptInterface
        public void scanQr() {
            runOnUiThread(new Runnable() {
                @Override public void run() { openNativeScanner(); }
            });
        }
    }

    private void openNativeScanner() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA},
                    REQ_CAMERA);
            Toast.makeText(this,
                    "Allow the camera, then tap Scan QR again",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivityForResult(
                    new Intent(this, ScanActivity.class), REQ_SCAN_QR);
        } catch (Exception e) {
            Toast.makeText(this, "Scanner could not open — type the code",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_SCAN_QR) return;
        if (result != RESULT_OK || data == null) return; // user cancelled
        String text = data.getStringExtra(ScanActivity.EXTRA_QR_TEXT);
        if (text == null || text.isEmpty()) return;
        final String escaped = text.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "\\r");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            web.evaluateJavascript(
                    "window.__dictateNativeScan && window.__dictateNativeScan('"
                            + escaped + "')",
                    null);
        } else {
            web.loadUrl("javascript:window.__dictateNativeScan && "
                    + "window.__dictateNativeScan('" + escaped + "')");
        }
    }

    @Override public void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override public void onBackPressed() {
        if (web.getVisibility() == View.VISIBLE && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
