package com.bucket;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** In-app image viewer: images open here, inside Bucket, instead of leaving
 *  for a browser tab or an external app. Everything else (pdf, video, zip,
 *  ...) goes the other way on purpose — see FileOpener, which hands those
 *  to the system's default app.
 *
 *  Snappy on purpose: the bytes are cached under getCacheDir()/viewer keyed
 *  by item id, so reopening an image never touches the network, and the feed
 *  thumbnail already warmed the HTTP path on first view. Decode is sampled
 *  down to the screen so a 20 MP phone photo cannot OOM the viewer. */
public class ViewerActivity extends Activity {
    static final String EXTRA_URL = "url";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_ID = "id";

    private static final long MAX_CACHE_BYTES = 100L * 1024 * 1024;
    private static final int MAX_CACHE_FILES = 100;

    private ZoomView image;
    private ProgressBar progress;
    private LinearLayout bar;
    private TextView title;

    private String url;
    private String name;
    private String itemId;

    /** Open an image without leaving the app. id may be null (parsed back
     *  out of the /api/items/&lt;id&gt;/raw URL when missing). */
    static void open(Activity activity, String id, String url, String name) {
        if (activity == null || url == null) return;
        if (id == null) id = parseId(url);
        Intent intent = new Intent(activity, ViewerActivity.class);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_NAME, name == null ? "image" : name);
        if (id != null) intent.putExtra(EXTRA_ID, id);
        activity.startActivity(intent);
    }

    static String parseId(String url) {
        if (url == null) return null;
        int at = url.indexOf("/api/items/");
        if (at < 0) return null;
        String rest = url.substring(at + "/api/items/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        String id = rest.substring(0, slash);
        int q = id.indexOf('?');
        if (q >= 0) id = id.substring(0, q);
        if (!id.matches("[A-Za-z0-9_-]+")) return null;
        return id;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        url = getIntent().getStringExtra(EXTRA_URL);
        name = getIntent().getStringExtra(EXTRA_NAME);
        itemId = getIntent().getStringExtra(EXTRA_ID);
        if (name == null) name = "image";
        if (itemId == null) itemId = parseId(url);
        if (url == null) {
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        image = new ZoomView(this);
        image.setBackgroundColor(Color.BLACK);
        image.setOnSingleTap(new Runnable() {
            @Override public void run() {
                bar.setVisibility(bar.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE);
            }
        });
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this);
        FrameLayout.LayoutParams progressLp = new FrameLayout.LayoutParams(-2, -2);
        progressLp.gravity = Gravity.CENTER;
        root.addView(progress, progressLp);

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xCC0E1013);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(-1, -2);
        barLp.gravity = Gravity.TOP;

        Button close = viewerButton("\u2715");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        title = new TextView(this);
        title.setText(name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(13f);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(8), 0, dp(8), 0);
        Button save = viewerButton("Save");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveToDownloads(); }
        });

        bar.addView(close, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams titleLp =
                new LinearLayout.LayoutParams(0, -2, 1f);
        bar.addView(title, titleLp);
        bar.addView(save, new LinearLayout.LayoutParams(-2, -2));
        root.addView(bar, barLp);

        setContentView(root);
        trimCache();
        load();
    }

    private Button viewerButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        return button;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private File cacheFile() {
        String key = itemId != null ? itemId
                : String.valueOf(url.hashCode()).replace('-', 'n');
        return new File(new File(getCacheDir(), "viewer"), key);
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        final int reqW;
        final int reqH;
        if (getResources() != null && getResources().getDisplayMetrics() != null) {
            reqW = Math.max(720, getResources().getDisplayMetrics().widthPixels);
            reqH = Math.max(1280, getResources().getDisplayMetrics().heightPixels);
        } else {
            reqW = 1080;
            reqH = 1920;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File cached = cacheFile();
                    if (!cached.isFile() || cached.length() == 0) {
                        download(cached);
                    }
                    final Bitmap bitmap = decodeSampled(cached, reqW, reqH);
                    if (bitmap == null) throw new Exception("decode failed");
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) {
                                bitmap.recycle();
                                return;
                            }
                            progress.setVisibility(View.GONE);
                            image.setImageBitmap(bitmap);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (!isFinishing()) {
                                toast("Could not load image");
                                finish();
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void download(File dest) throws Exception {
        dest.getParentFile().mkdirs();
        File tmp = new File(dest.getParent(), dest.getName() + ".tmp");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(60000);
            conn.setUseCaches(false);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);
            if (conn.getResponseCode() != 200) {
                throw new Exception("server said " + conn.getResponseCode());
            }
            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(tmp);
            copy(in, out);
            out.close();
            in.close();
            if (!tmp.renameTo(dest)) {
                dest.delete();
                if (!tmp.renameTo(dest)) throw new Exception("cache write failed");
            }
        } finally {
            if (conn != null) conn.disconnect();
            tmp.delete();
        }
    }

    private static Bitmap decodeSampled(File file, int reqW, int reqH) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= reqW
                && bounds.outHeight / (sample * 2) >= reqH) {
            sample *= 2;
        }
        // Cap total pixels so a huge panorama cannot OOM the viewer.
        while ((long) (bounds.outWidth / sample) * (bounds.outHeight / sample)
                > 12_000_000L) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** Save without re-downloading: the viewer cache already holds the exact
     *  bytes, so this is a local copy into Downloads. */
    private void saveToDownloads() {
        final File cached = cacheFile();
        if (!cached.isFile()) {
            toast("Image is still loading");
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    2001);
            return;
        }
        toast("Saving " + name + "...");
        new Thread(new Runnable() {
            @Override public void run() {
                final String error = copyToDownloads(cached, name);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        toast(error == null ? "Saved to Downloads"
                                : "Could not save: " + error);
                    }
                });
            }
        }).start();
    }

    private String copyToDownloads(File src, String fileName) {
        String safe = fileName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.isEmpty()) safe = "image";
        String mime = guessMime(safe);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return "could not create file";
                InputStream in = new java.io.FileInputStream(src);
                try {
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    copy(in, out);
                    out.close();
                } catch (Exception e) {
                    getContentResolver().delete(uri, null, null);
                    throw e;
                }
                in.close();
                return null;
            }
            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            File dest = uniqueFile(dir, safe);
            InputStream in = new java.io.FileInputStream(src);
            OutputStream out = new FileOutputStream(dest);
            copy(in, out);
            out.close();
            in.close();
            MediaScannerConnection.scanFile(this,
                    new String[]{dest.getAbsolutePath()}, null, null);
            return null;
        } catch (Exception e) {
            String reason = e.getMessage();
            return reason == null || reason.isEmpty()
                    ? e.getClass().getSimpleName() : reason;
        }
    }

    private static String guessMime(String fileName) {
        String mime = java.net.URLConnection.guessContentTypeFromName(fileName);
        return mime != null ? mime : "image/*";
    }

    private static File uniqueFile(File dir, String fileName) {
        File file = new File(dir, fileName);
        if (!file.exists()) return file;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            file = new File(dir, base + " (" + i + ")" + ext);
            if (!file.exists()) return file;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private void trimCache() {
        try {
            File dir = new File(getCacheDir(), "viewer");
            File[] files = dir.listFiles();
            if (files == null || files.length <= MAX_CACHE_FILES) return;
            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            long total = 0;
            for (File f : files) total += f.length();
            for (File f : files) {
                if (files.length <= MAX_CACHE_FILES && total <= MAX_CACHE_BYTES) break;
                total -= f.length();
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** Pinch-zoom + drag + double-tap image view with no dependencies.
     *  Starts fitted to the screen (letterboxed); zoom clamps between that
     *  fit scale and 6x so you can read small text but never lose the photo. */
    private static class ZoomView extends ImageView {
        private final Matrix matrix = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector tapDetector;
        private float fitScale = 1f;
        private float lastX;
        private float lastY;
        private boolean dragging;
        private Runnable onSingleTap;

        ZoomView(android.content.Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            scaleDetector = new ScaleGestureDetector(context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override public boolean onScale(ScaleGestureDetector d) {
                            float current = currentScale();
                            float target = current * d.getScaleFactor();
                            float lo = fitScale;
                            float hi = fitScale * 6f;
                            if (target < lo) target = lo;
                            if (target > hi) target = hi;
                            matrix.postScale(target / current, target / current,
                                    d.getFocusX(), d.getFocusY());
                            fixBounds();
                            setImageMatrix(matrix);
                            return true;
                        }
                    });
            tapDetector = new GestureDetector(context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDoubleTap(MotionEvent e) {
                            toggleZoom(e.getX(), e.getY());
                            return true;
                        }
                        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                            if (onSingleTap != null) onSingleTap.run();
                            return true;
                        }
                    });
        }

        void setOnSingleTap(Runnable r) {
            onSingleTap = r;
        }

        @Override public void setImageBitmap(Bitmap bitmap) {
            super.setImageBitmap(bitmap);
            post(new Runnable() {
                @Override public void run() { fit(); }
            });
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            if (w != oldW || h != oldH) fit();
        }

        private float currentScale() {
            float[] v = new float[9];
            matrix.getValues(v);
            return v[Matrix.MSCALE_X];
        }

        private void fit() {
            Drawable d = getDrawable();
            if (d == null) return;
            int vw = getWidth() - getPaddingLeft() - getPaddingRight();
            int vh = getHeight() - getPaddingTop() - getPaddingBottom();
            if (vw <= 0 || vh <= 0) return;
            int dw = d.getIntrinsicWidth();
            int dh = d.getIntrinsicHeight();
            if (dw <= 0 || dh <= 0) return;
            float scale = Math.min((float) vw / dw, (float) vh / dh);
            fitScale = scale;
            matrix.setScale(scale, scale);
            matrix.postTranslate(
                    (vw - dw * scale) / 2f + getPaddingLeft(),
                    (vh - dh * scale) / 2f + getPaddingTop());
            setImageMatrix(matrix);
        }

        private void toggleZoom(float x, float y) {
            if (getDrawable() == null) return;
            float current = currentScale();
            float target = current <= fitScale * 1.1f ? fitScale * 2.5f : fitScale;
            matrix.postScale(target / current, target / current, x, y);
            fixBounds();
            setImageMatrix(matrix);
        }

        private void fixBounds() {
            Drawable d = getDrawable();
            if (d == null) return;
            RectF rect = new RectF(0, 0,
                    d.getIntrinsicWidth(), d.getIntrinsicHeight());
            matrix.mapRect(rect);
            float vw = getWidth() - getPaddingLeft() - getPaddingRight();
            float vh = getHeight() - getPaddingTop() - getPaddingBottom();
            float dx = 0f;
            float dy = 0f;
            if (rect.width() <= vw) {
                dx = (vw - rect.width()) / 2f - (rect.left - getPaddingLeft());
            } else if (rect.left > getPaddingLeft()) {
                dx = getPaddingLeft() - rect.left;
            } else if (rect.right < getPaddingLeft() + vw) {
                dx = getPaddingLeft() + vw - rect.right;
            }
            if (rect.height() <= vh) {
                dy = (vh - rect.height()) / 2f - (rect.top - getPaddingTop());
            } else if (rect.top > getPaddingTop()) {
                dy = getPaddingTop() - rect.top;
            } else if (rect.bottom < getPaddingTop() + vh) {
                dy = getPaddingTop() + vh - rect.bottom;
            }
            matrix.postTranslate(dx, dy);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            tapDetector.onTouchEvent(event);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                dragging = true;
                lastX = event.getX();
                lastY = event.getY();
            } else if (action == MotionEvent.ACTION_MOVE && dragging
                    && !scaleDetector.isInProgress()) {
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                lastX = event.getX();
                lastY = event.getY();
                if (getDrawable() != null) {
                    // A fitted image is already centred; only drag once zoomed.
                    RectF rect = new RectF(0, 0,
                            getDrawable().getIntrinsicWidth(),
                            getDrawable().getIntrinsicHeight());
                    matrix.mapRect(rect);
                    float vw = getWidth();
                    float vh = getHeight();
                    if (rect.width() > vw || rect.height() > vh) {
                        matrix.postTranslate(dx, dy);
                        fixBounds();
                        setImageMatrix(matrix);
                    }
                }
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                dragging = false;
            }
            return true;
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms,
            int[] grants) {
        if (grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            saveToDownloads();
        } else {
            toast("Storage permission needed to save files");
        }
    }
}
