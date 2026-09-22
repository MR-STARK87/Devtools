package com.bucket;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Hosts the Bucket page. Everything the user sees is the same web UI the
 *  laptop serves; this class only finds the server and wires up the two
 *  things a browser tab cannot do over plain HTTP: file picking and saving.
 *  Workbench rework: light concrete ground, white cards, vermilion signal,
 *  hazard stripe -- matches bucket/static/app.css tokens. */
public class MainActivity extends Activity {
    private static final int PICK_FILES = 1001;
    private static final int REQ_DOWNLOAD = 1002;
    // Workbench palette -- keep in sync with app.css :root
    private static final int GROUND = 0xFFEDEFF2;
    private static final int SURFACE = 0xFFFFFFFF;
    private static final int SURFACE2 = 0xFFF2F4F7;
    private static final int INK = 0xFF0E1013;
    private static final int MUTED = 0xFF6B7382;
    private static final int FAINT = 0xFF9AA3B1;
    private static final int LINE = 0xFFD9DDE3;
    private static final int LINE_STRONG = 0xFFB8C0CC;
    private static final int SIGNAL = 0xFFFF3B1F;
    private static final int HAZARD = 0xFFFFD400;

    private WebView web;
    private LinearLayout status;
    private TextView statusText;
    private TextView statusSub;
    private ValueCallback<Uri[]> pendingPick;
    private String[] pendingDownload;
    private String base;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(GROUND);
        getWindow().setNavigationBarColor(GROUND);
        // light status bar icons on light ground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(GROUND);

        web = new WebView(this);
        web.setBackgroundColor(GROUND);
        web.setVisibility(View.GONE);
        configure(web);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        root.addView(buildStatus(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        resolveThenLoad();
    }

    private LinearLayout buildStatus() {
        status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(GROUND);
        status.setPadding(20, 20, 20, 20);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(32, 32, 32, 28);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(SURFACE);
        cardBg.setCornerRadius(36f);
        cardBg.setStroke(2, LINE);
        card.setBackground(cardBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(16f);
        }

        // Bucky -- persistent mascot, drawn natively to avoid asset churn
        BuckyView bucky = new BuckyView(this);
        LinearLayout.LayoutParams buckyLp = new LinearLayout.LayoutParams(
            dp(200), dp(150));
        buckyLp.bottomMargin = dp(10);
        card.addView(bucky, buckyLp);

        statusText = new TextView(this);
        statusText.setTextColor(INK);
        statusText.setTextSize(16f);
        statusText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusText.setGravity(Gravity.CENTER);
        statusText.setText("Looking for your laptop...");
        statusText.setLineSpacing(dp(2), 1f);
        card.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        statusSub = new TextView(this);
        statusSub.setTextColor(MUTED);
        statusSub.setTextSize(13f);
        statusSub.setGravity(Gravity.CENTER);
        statusSub.setLineSpacing(dp(3), 1f);
        statusSub.setPadding(0, dp(6), 0, 0);
        statusSub.setText("Bucky's on watch -- start Bucket on the laptop and make sure it's on this phone's hotspot.");
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(4);
        card.addView(statusSub, subLp);

        // meta pill -- waiting for drops
        TextView meta = new TextView(this);
        meta.setText("\u25CF waiting for drops  \u2022  LAN only");
        meta.setTextColor(FAINT);
        meta.setTextSize(10.5f);
        meta.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        meta.setGravity(Gravity.CENTER);
        meta.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable metaBg = new GradientDrawable();
        metaBg.setColor(SURFACE2);
        metaBg.setCornerRadius(999f);
        metaBg.setStroke(2, LINE);
        meta.setBackground(metaBg);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-2, -2);
        metaLp.topMargin = dp(14);
        card.addView(meta, metaLp);

        Button retry = new Button(this);
        retry.setText("Search again");
        retry.setAllCaps(false);
        retry.setTextSize(14f);
        retry.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        retry.setTextColor(SURFACE);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(SIGNAL);
        btnBg.setCornerRadius(999f);
        retry.setBackground(btnBg);
        retry.setPadding(dp(22), dp(10), dp(22), dp(10));
        retry.setMinHeight(0);
        retry.setMinimumHeight(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            retry.setElevation(4f);
            retry.setStateListAnimator(null);
        }
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resolveThenLoad(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(18);
        card.addView(retry, lp);

        // hazard accent bar at top of card -- 4px repeating hazard
        // simulated via a thin view with hazard background
        LinearLayout hazard = new LinearLayout(this);
        hazard.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable hazardBg = new GradientDrawable();
        // we fake hazard with solid HAZARD then overlay stripes via Bucky? Keep solid for now
        // to mimic web gate -- a 4px bar: we use HAZARD base, card already has top rounding
        // Instead add a 4px view with hazard color and clip to card top
        // Simpler: add a top stripe view
        View stripe = new View(this);
        stripe.setBackgroundColor(HAZARD);
        // stripe will be added as first child with negative margin to sit at edge
        // Use a wrapper to clip
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(Gravity.CENTER);
        // stripe 4dp tall, full width
        LinearLayout.LayoutParams stripeLp = new LinearLayout.LayoutParams(-1, dp(4));
        // add stripe then card via wrapper? easier: just add card with top stripe view inside it at index 0
        // Reorder: insert stripe at top of card
        card.addView(stripe, 0, stripeLp);
        // adjust card padding top to account for stripe
        card.setPadding(32, 20, 32, 28);

        status.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return status;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void configure(WebView v) {
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        CookieManager.getInstance().setAcceptCookie(true);
        v.addJavascriptInterface(new NativeBridge(), "BucketNative");

        v.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view,
                    String url) {
                return handleRawUrl(url);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view,
                    WebResourceRequest request) {
                // Subresource loads (the feed <img> tags hit /raw too) must
                // never be intercepted — only top-level Open taps.
                if (request != null && !request.isForMainFrame()) return false;
                return handleRawUrl(
                        request == null ? null : request.getUrl().toString());
            }
        });
        v.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage msg) {
                // The page's JS otherwise fails silently on-device; this
                // keeps errors one `adb logcat -s Bucket` away. Never logs
                // URLs with tokens — only the message text and source id.
                Log.w("Bucket", "js: " + msg.message() + " @"
                        + msg.sourceId() + ":" + msg.lineNumber());
                return super.onConsoleMessage(msg);
            }

            @Override public boolean onJsConfirm(WebView view, String url,
                    String message, JsResult result) {
                showClearConfirmation(message, result);
                return true;
            }

            @Override public boolean onShowFileChooser(WebView w,
                    ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (pendingPick != null) pendingPick.onReceiveValue(null);
                pendingPick = cb;
                try {
                    startActivityForResult(params.createIntent(), PICK_FILES);
                    return true;
                } catch (Exception e) {
                    pendingPick = null;
                    return false;
                }
            }
        });
        v.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String agent,
                    String disposition, String mime, long size) {
                String name = URLUtil.guessFileName(url, disposition, mime);
                startDownload(url, name, mime);
            }
        });
    }

    /** Bridge the page calls instead of navigating: images stay in-app in
     *  ViewerActivity, everything else goes to the system's default app via
     *  FileOpener. The page feature-detects window.BucketNative, so a plain
     *  browser tab (no bridge) keeps the old anchor behaviour untouched. */
    private class NativeBridge {
        @JavascriptInterface public void openImage(final String id,
                final String url, final String name) {
            final String absolute = absoluteUrl(url);
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    ViewerActivity.open(MainActivity.this, id, absolute, name);
                }
            });
        }

        @JavascriptInterface public void openFile(final String url,
                final String name, final String mime) {
            FileOpener.open(MainActivity.this, absoluteUrl(url), name, mime);
        }

        private String absoluteUrl(String url) {
            if (url == null) return base;
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return url;
            }
            if (base != null) return base + (url.startsWith("/") ? url : "/" + url);
            return url;
        }
    }

    /** Safety net under the bridge: an Open tap is a main-frame navigation to
     *  /raw, so even if the bridge call never fires the tap still stays
     *  in-app (viewer for images, system default otherwise) instead of
     *  loading raw bytes into the WebView and stranding the feed. Save taps
     *  carry ?download=1 and stay on the DownloadListener path. */
    private boolean handleRawUrl(String url) {
        if (url == null || !url.contains("/api/items/") || !url.contains("/raw")) {
            return false;
        }
        if (url.contains("download=1")) return false;
        openRawUrl(url);
        return true;
    }

    private void openRawUrl(final String url) {
        new Thread(new Runnable() {
            @Override public void run() {
                String mime = null;
                String disposition = null;
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("HEAD");
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    conn.setUseCaches(false);
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) conn.setRequestProperty("Cookie", cookie);
                    if (conn.getResponseCode() == 200) {
                        mime = conn.getContentType();
                        disposition = conn.getHeaderField("Content-Disposition");
                    }
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
                final String effMime = mime;
                final String effDisposition = disposition;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (effMime != null && effMime.startsWith("image/")) {
                            ViewerActivity.open(MainActivity.this,
                                    ViewerActivity.parseId(url), url,
                                    URLUtil.guessFileName(url, effDisposition,
                                            effMime));
                        } else {
                            FileOpener.open(MainActivity.this, url,
                                    URLUtil.guessFileName(url, effDisposition,
                                            effMime),
                                    effMime);
                        }
                    }
                });
            }
        }).start();
    }

    /** Keeps JavaScript confirm() from falling back to the platform's dated
     * gray alert. The card deliberately uses the same workbench tokens as the
     * page, so destructive actions still feel like part of Bucket. */
    private void showClearConfirmation(String message, final JsResult result) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipToOutline(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(SURFACE);
        cardBg.setCornerRadius(dp(18));
        cardBg.setStroke(dp(1), LINE);
        card.setBackground(cardBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(12));
        }

        View stripe = new View(this);
        stripe.setBackgroundColor(HAZARD);
        card.addView(stripe, new LinearLayout.LayoutParams(-1, dp(6)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(20), dp(24), dp(18));
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("Clear all items?");
        title.setTextColor(INK);
        title.setTextSize(20f);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        body.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView copy = new TextView(this);
        copy.setText(message == null || message.trim().isEmpty()
                ? "Delete every item in the bucket?" : message);
        copy.setTextColor(MUTED);
        copy.setTextSize(14f);
        copy.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(-1, -2);
        copyLp.topMargin = dp(8);
        body.addView(copy, copyLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
        actionsLp.topMargin = dp(22);
        body.addView(actions, actionsLp);

        Button cancel = dialogButton("Cancel", SURFACE2, MUTED, LINE);
        Button clear = dialogButton("Clear all", SIGNAL, SURFACE, SIGNAL);
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        actions.addView(cancel, buttonLp);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        clearLp.leftMargin = dp(10);
        actions.addView(clear, clearLp);

        final boolean[] settled = {false};
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface d) {
                if (!settled[0]) {
                    settled[0] = true;
                    result.cancel();
                }
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                if (!settled[0]) {
                    settled[0] = true;
                    result.cancel();
                }
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (settled[0]) return;
                settled[0] = true;
                result.cancel();
                dialog.dismiss();
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (settled[0]) return;
                settled[0] = true;
                result.confirm();
                dialog.dismiss();
            }
        });

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.28f;
            window.setAttributes(attrs);
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        window = dialog.getWindow();
        if (window != null) {
            int maxWidth = getResources().getDisplayMetrics().widthPixels - dp(32);
            window.setLayout(Math.min(dp(360), maxWidth),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private Button dialogButton(String text, int background, int foreground,
            int stroke) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(foreground);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(background);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), stroke);
        button.setBackground(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setStateListAnimator(null);
        }
        return button;
    }

    private void startDownload(String url, String name, String mime) {
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty()) name = "download";
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = new String[]{url, name, mime};
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_DOWNLOAD);
            return;
        }
        save(url, name, mime);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms,
            int[] grants) {
        if (req != REQ_DOWNLOAD) {
            super.onRequestPermissionsResult(req, perms, grants);
            return;
        }
        String[] pending = pendingDownload;
        pendingDownload = null;
        if (pending != null && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            save(pending[0], pending[1], pending[2]);
        } else {
            toast("Storage permission needed to save files");
        }
    }

    private void save(final String url, final String name, final String mime) {
        toast("Saving " + name + "...");
        new Thread(new Runnable() {
            @Override public void run() {
                final String error = download(url, name, mime);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        toast(error == null ? "Saved to Downloads"
                                            : "Could not save: " + error);
                    }
                });
            }
        }).start();
    }

    private String download(String url, String name, String mime) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(60000);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);
            int code = conn.getResponseCode();
            if (code != 200) return "server said " + code;

            InputStream in = conn.getInputStream();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return "could not create file";
                try {
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    copy(in, out);
                    out.close();
                } catch (Exception e) {
                    getContentResolver().delete(uri, null, null);
                    throw e;
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                File file = uniqueFile(dir, name);
                OutputStream out = new FileOutputStream(file);
                copy(in, out);
                out.close();
                MediaScannerConnection.scanFile(this,
                        new String[]{file.getAbsolutePath()}, null, null);
            }
            in.close();
            return null;
        } catch (Exception e) {
            String reason = e.getMessage();
            return reason == null || reason.isEmpty()
                    ? e.getClass().getSimpleName() : reason;
        } finally {
            if (conn != null) conn.disconnect();
        }
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

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void resolveThenLoad() {
        show("Looking for your laptop...", "Bucky's on watch -- start Bucket on the laptop and make sure it's on this phone's hotspot.");
        new Thread(new Runnable() {
            @Override public void run() {
                final String found = Server.discover(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (found == null) {
                            show("Could not find Bucket on this network.",
                                 "Start it on the laptop, make sure the laptop is on this phone's hotspot, then search again.");
                            return;
                        }
                        base = found;
                        status.setVisibility(View.GONE);
                        web.setVisibility(View.VISIBLE);
                        web.loadUrl(base + "/");
                        flushOutbox(found);
                    }
                });
            }
        }).start();
    }

    /** Sends whatever was queued while away. Runs after the page is up so
     *  uploads never delay the feed; a lost race is fine — the entries stay
     *  queued and the next open retries them. */
    private void flushOutbox(final String found) {
        new Thread(new Runnable() {
            @Override public void run() {
                final int[] flushed = Outbox.flush(MainActivity.this, found);
                if (flushed[0] == 0 && flushed[1] == 0) return;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (flushed[0] > 0) {
                            toast(flushed[0] == 1 ? "Sent 1 queued item"
                                    : "Sent " + flushed[0] + " queued items");
                        } else {
                            toast(flushed[1] + " queued item"
                                    + (flushed[1] == 1 ? "" : "s")
                                    + " waiting — pair to send");
                        }
                    }
                });
            }
        }).start();
    }

    private void show(String title, String subtitle) {
        statusText.setText(title);
        statusSub.setText(subtitle);
        status.setVisibility(View.VISIBLE);
        web.setVisibility(View.GONE);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        if (req != PICK_FILES) {
            super.onActivityResult(req, result, data);
            return;
        }
        if (pendingPick == null) return;
        pendingPick.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result, data));
        pendingPick = null;
    }

    @Override public void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override public void onBackPressed() {
        if (web.getVisibility() == View.VISIBLE && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** Cute workbench Bucky -- bucket with hazard stripe, blinking eyes, floating paper. */
    private static class BuckyView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint eyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path bucketPath = new Path();
        private long start = System.currentTimeMillis();

        BuckyView(Context c) { super(c); }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            float cx = w / 2f;
            // float to mimic web viewBox 200x150
            float scale = Math.min(w / 200f, h / 150f) * 0.94f;
            canvas.save();
            canvas.translate(cx - 100 * scale, h / 2f - 75 * scale);
            canvas.scale(scale, scale);

            // gentle float
            float t = (System.currentTimeMillis() - start) / 1000f;
            float floatY = (float) Math.sin(t * 1.9) * 3f;
            // blink
            float blinkPhase = (t % 5.2f) / 5.2f;
            boolean blinking = (blinkPhase > 0.92f && blinkPhase < 0.96f);
            float eyeScale = blinking ? 0.12f : 1f;

            // shadow
            fill.setColor(0x120E1013);
            canvas.drawOval(58, 136, 142, 150, fill);

            // bucket body
            bucketPath.reset();
            bucketPath.moveTo(52, 52);
            bucketPath.lineTo(148, 52);
            bucketPath.lineTo(140, 122);
            bucketPath.cubicTo(140, 132, 128, 142, 100, 142);
            bucketPath.cubicTo(72, 142, 60, 132, 60, 122);
            bucketPath.close();
            fill.setColor(Color.WHITE);
            stroke.setColor(0xFF0E1013);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2.8f);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawPath(bucketPath, fill);
            canvas.drawPath(bucketPath, stroke);

            // rim
            fill.setColor(Color.WHITE);
            canvas.drawRoundRect(new RectF(46, 42, 154, 58), 7, 7, fill);
            canvas.drawRoundRect(new RectF(46, 42, 154, 58), 7, 7, stroke);
            // inner top
            fill.setColor(0xFFF2F4F7);
            stroke.setStrokeWidth(2.2f);
            canvas.drawOval(new RectF(53, 43.5f, 147, 56.5f), fill);
            canvas.drawOval(new RectF(53, 43.5f, 147, 56.5f), stroke);
            stroke.setStrokeWidth(2.8f);

            // hazard band clipped to bucket
            canvas.save();
            canvas.clipPath(bucketPath);
            fill.setColor(0xFFFFD400);
            canvas.drawRect(new RectF(60, 112, 140, 122), fill);
            stroke.setColor(0xFF0E1013);
            stroke.setStrokeWidth(1.7f);
            for (int x = 64; x < 140; x += 16) {
                canvas.drawLine(x, 112, x + 10, 122, stroke);
            }
            stroke.setStrokeWidth(1.3f);
            fill.setStyle(Paint.Style.STROKE);
            fill.setColor(0xFF0E1013);
            // reuse stroke as fill stroke
            Paint hazardBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
            hazardBorder.setColor(0xFF0E1013);
            hazardBorder.setStyle(Paint.Style.STROKE);
            hazardBorder.setStrokeWidth(1.3f);
            canvas.drawRect(new RectF(60, 112, 140, 122), hazardBorder);
            canvas.restore();
            stroke.setStrokeWidth(2.8f);
            stroke.setStyle(Paint.Style.STROKE);

            // arms
            stroke.setColor(0xFF0E1013);
            stroke.setStrokeWidth(2.6f);
            Path armL = new Path(); armL.moveTo(60, 92); armL.cubicTo(46, 94, 39, 100, 48, 106);
            Path armR = new Path(); armR.moveTo(140, 92); armR.cubicTo(154, 94, 161, 100, 152, 106);
            canvas.drawPath(armL, stroke);
            canvas.drawPath(armR, stroke);
            fill.setColor(0xFF0E1013);
            fill.setStyle(Paint.Style.FILL);
            canvas.drawCircle(48, 106, 2.2f, fill);
            canvas.drawCircle(152, 106, 2.2f, fill);

            // face -- float applied
            canvas.save();
            canvas.translate(0, floatY);
            // cheeks
            fill.setColor(0xFFFF3B1F);
            fill.setAlpha(33);
            canvas.drawCircle(66, 98, 5.5f, fill);
            canvas.drawCircle(134, 98, 5.5f, fill);
            fill.setAlpha(255);
            // eyes
            eyePaint.setColor(0xFF0E1013);
            // left eye
            canvas.save();
            canvas.translate(83, 86);
            canvas.scale(1f, eyeScale);
            canvas.drawCircle(0, 0, 9.2f, eyePaint);
            fill.setColor(Color.WHITE);
            canvas.drawCircle(2.6f, -2.8f, 2.7f, fill);
            fill.setAlpha(180);
            canvas.drawCircle(1f, 1.8f, 1f, fill);
            fill.setAlpha(255);
            canvas.restore();
            // right eye
            canvas.save();
            canvas.translate(117, 86);
            canvas.scale(1f, eyeScale);
            canvas.drawCircle(0, 0, 9.2f, eyePaint);
            fill.setColor(Color.WHITE);
            canvas.drawCircle(2.6f, -2.8f, 2.7f, fill);
            fill.setAlpha(180);
            canvas.drawCircle(1f, 1.8f, 1f, fill);
            fill.setAlpha(255);
            canvas.restore();
            // mouth
            stroke.setColor(0xFF0E1013);
            stroke.setStrokeWidth(2.2f);
            Path mouth = new Path();
            mouth.moveTo(93, 106);
            mouth.quadTo(100, 112, 107, 106);
            canvas.drawPath(mouth, stroke);
            stroke.setStrokeWidth(2.8f);
            canvas.restore();

            // floating paper
            canvas.save();
            float paperFloat = (float) Math.sin(t * 1.8) * 4f;
            canvas.translate(0, paperFloat);
            canvas.rotate(11, 156, 41);
            // paper body
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            RectF paper = new RectF(138, 18, 174, 64);
            canvas.drawRoundRect(paper, 4.5f, 4.5f, fill);
            stroke.setColor(0xFF0E1013);
            stroke.setStrokeWidth(2.2f);
            stroke.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(paper, 4.5f, 4.5f, stroke);
            // fold
            fill.setColor(0xFFE9ECF0);
            fill.setStyle(Paint.Style.FILL);
            Path fold = new Path();
            fold.moveTo(157, 18); fold.lineTo(166, 27); fold.lineTo(157, 27); fold.close();
            canvas.drawPath(fold, fill);
            canvas.drawPath(fold, stroke);
            // lines
            stroke.setColor(0xFFD9DDE3);
            stroke.setStrokeWidth(1.7f);
            canvas.drawLine(145, 32, 163, 32, stroke);
            canvas.drawLine(145, 38, 163, 38, stroke);
            canvas.drawLine(145, 44, 158, 44, stroke);
            stroke.setColor(0xFF0E1013);
            stroke.setStrokeWidth(2.8f);
            // dot
            fill.setColor(0xFFFF3B1F);
            fill.setStyle(Paint.Style.FILL);
            canvas.drawCircle(163, 19, 3f, fill);
            stroke.setColor(0xFF0E1013);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.2f);
            canvas.drawCircle(163, 19, 3f, stroke);
            canvas.restore();

            // sparkles
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xFFFF3B1F);
            fill.setAlpha(242);
            float sp = (float) Math.sin(t * 2.2) * 1.5f;
            canvas.drawCircle(34, 36 + sp, 2.1f, fill);
            fill.setAlpha(190);
            canvas.drawCircle(170, 62 - sp, 1.7f, fill);
            fill.setAlpha(150);
            canvas.drawCircle(28, 74 + sp*0.7f, 1.3f, fill);
            fill.setColor(0xFFFFD400);
            fill.setAlpha(242);
            canvas.drawCircle(172, 34 - sp, 1.8f, fill);
            fill.setAlpha(180);
            canvas.drawCircle(30, 60 + sp, 1.2f, fill);
            fill.setAlpha(255);

            canvas.restore();

            // schedule next frame
            postInvalidateOnAnimation();
        }
    }
}
