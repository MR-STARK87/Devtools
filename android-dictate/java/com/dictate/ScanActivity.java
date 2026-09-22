package com.dictate;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Native QR scanner for pairing.
 *
 *  Why native instead of the page's BarcodeDetector path: the Dictate page is
 *  served over plain HTTP on the LAN, which is not a secure context, so
 *  {@code navigator.mediaDevices.getUserMedia} is unavailable inside the
 *  WebView and {@code BarcodeDetector} is not exposed there either. The
 *  in-page "Scan QR" button therefore always ends in "type the code instead".
 *  The camera itself is fine — only the *web* camera APIs are gated — so this
 *  activity previews with {@code android.hardware.Camera} (Camera1: no extra
 *  dependencies, works on minSdk 24) and decodes with a vendored minimal
 *  ZXing QR subset ({@code com.google.zxing.qrcode} + {@code common} only,
 *  Apache-2.0, pure Java, compiled in like our own sources).
 *
 *  Contract: on success {@code setResult(RESULT_OK, intent with
 *  EXTRA_QR_TEXT)} and finish; on cancel/back {@code RESULT_CANCELED}.
 *  MainActivity forwards the text to the page's
 *  {@code window.__dictateNativeScan}, which parses the 6-digit code and
 *  POSTs /api/pair exactly like a manually typed code. */
@SuppressWarnings("deprecation")
public class ScanActivity extends Activity implements SurfaceHolder.Callback,
        Camera.PreviewCallback {
    public static final String EXTRA_QR_TEXT = "qr_text";
    private static final int REQ_CAMERA = 1;

    private SurfaceView preview;
    private SurfaceHolder holder;
    private Camera camera;
    private boolean surfaceReady;
    private boolean found;
    private boolean decoding;
    private long lastAttempt;
    private Map<DecodeHintType, Object> hints;
    private Button torchBtn;
    private boolean torchOn;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF121417);
        getWindow().setNavigationBarColor(0xFF121417);

        hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new SurfaceView(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        holder = preview.getHolder();
        holder.addCallback(this);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
            holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }

        ViewfinderView finder = new ViewfinderView(this);
        finder.setClickable(false);
        finder.setFocusable(false);
        root.addView(finder, new FrameLayout.LayoutParams(-1, -1));

        // Top bar: cancel (left) + torch (right, only if flash exists).
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(12), dp(12), dp(12));
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setAllCaps(false);
        cancel.setTextColor(0xFFFFFCF9);
        cancel.setBackgroundColor(Color.TRANSPARENT);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        LinearLayout.LayoutParams cancelLp =
                new LinearLayout.LayoutParams(0, -2, 1f);
        top.addView(cancel, cancelLp);

        torchBtn = new Button(this);
        torchBtn.setText("Torch");
        torchBtn.setAllCaps(false);
        torchBtn.setTextColor(0xFFFFFCF9);
        torchBtn.setBackgroundColor(Color.TRANSPARENT);
        torchBtn.setVisibility(View.GONE);
        torchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleTorch(); }
        });
        top.addView(torchBtn, new LinearLayout.LayoutParams(-2, -2));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Bottom hint.
        TextView hint = new TextView(this);
        hint.setText("Point at the QR in the PC terminal");
        hint.setTextColor(0xBFFFFCF9);
        hint.setTextSize(13f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(16), dp(10), dp(16), dp(24));
        root.addView(hint,
                new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA},
                    REQ_CAMERA);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        found = false;
        decoding = false;
        lastAttempt = 0;
        if (surfaceReady) openCamera();
    }

    @Override protected void onPause() {
        super.onPause();
        closeCamera();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms,
            int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code != REQ_CAMERA) return;
        if (grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            if (surfaceReady) openCamera();
        } else {
            Toast.makeText(this,
                    "Camera permission needed to scan the QR",
                    Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    // --- camera ------------------------------------------------------

    private void openCamera() {
        if (camera != null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return; // waiting on the runtime grant above
        }
        try {
            camera = Camera.open();
        } catch (Exception e) {
            Toast.makeText(this, "Could not open camera (" + e.getMessage()
                    + ")", Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        try {
            Camera.Parameters params = camera.getParameters();
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null) {
                if (focusModes.contains(
                        Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    params.setFocusMode(
                            Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (focusModes.contains(
                        Camera.Parameters.FOCUS_MODE_AUTO)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
            }
            Camera.Size size = pickPreviewSize(params);
            if (size != null) params.setPreviewSize(size.width, size.height);
            List<String> flashes = params.getSupportedFlashModes();
            final boolean hasTorch = flashes != null && (flashes.contains(
                    Camera.Parameters.FLASH_MODE_TORCH));
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    torchBtn.setVisibility(
                            hasTorch ? View.VISIBLE : View.GONE);
                }
            });
            camera.setParameters(params);
            // Portrait phone: rotate the *display*; the NV21 bytes stay
            // landscape, which is fine — QR finder patterns are rotation
            // invariant so the decoder needs no transpose.
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception e) {
            closeCamera();
            Toast.makeText(this, "Could not start preview (" + e.getMessage()
                    + ")", Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void closeCamera() {
        if (camera == null) return;
        try {
            camera.setPreviewCallback(null);
            camera.stopPreview();
        } catch (Exception ignored) {
        }
        try {
            camera.release();
        } catch (Exception ignored) {
        }
        camera = null;
        torchOn = false;
    }

    private static Camera.Size pickPreviewSize(Camera.Parameters params) {
        List<Camera.Size> sizes = params.getSupportedPreviewSizes();
        if (sizes == null || sizes.isEmpty()) return null;
        // Largest 4:3-ish frame at or under 1280 wide: sharp enough for a
        // dense terminal QR, small enough to decode at ~3 fps on old phones.
        Camera.Size best = null;
        for (Camera.Size s : sizes) {
            if (s.width > 1280) continue;
            if (best == null
                    || s.width * s.height > best.width * best.height) {
                best = s;
            }
        }
        return best != null ? best : sizes.get(0);
    }

    private void toggleTorch() {
        if (camera == null) return;
        try {
            Camera.Parameters params = camera.getParameters();
            torchOn = !torchOn;
            params.setFlashMode(torchOn
                    ? Camera.Parameters.FLASH_MODE_TORCH
                    : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(params);
            torchBtn.setText(torchOn ? "Torch on" : "Torch");
        } catch (Exception e) {
            Toast.makeText(this, "Torch not available",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // --- SurfaceHolder.Callback --------------------------------------

    @Override public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        openCamera();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int w,
            int h2) {
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        closeCamera();
    }

    // --- Camera.PreviewCallback --------------------------------------

    @Override public void onPreviewFrame(byte[] data, Camera cam) {
        if (found || decoding || data == null) return;
        long now = System.currentTimeMillis();
        if (now - lastAttempt < 350) return; // ~3 attempts/sec is plenty
        Camera.Size size;
        try {
            size = cam.getParameters().getPreviewSize();
        } catch (Exception e) {
            return;
        }
        if (size == null) return;
        lastAttempt = now;
        decoding = true;
        try {
            String text = decode(data, size.width, size.height);
            if (text != null && !text.trim().isEmpty()) {
                found = true;
                Intent out = new Intent();
                out.putExtra(EXTRA_QR_TEXT, text);
                setResult(RESULT_OK, out);
                finish();
                return;
            }
        } finally {
            decoding = false;
        }
    }

    /** Decode one NV21 frame; null when no QR is (yet) visible. */
    private String decode(byte[] yuv, int width, int height) {
        // Centered ~75% square: ignores terminal-window chrome at the edges
        // while keeping the QR at full sensor resolution.
        int edge = Math.min(width, height) * 3 / 4;
        int left = (width - edge) / 2;
        int top = (height - edge) / 2;
        try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    yuv, width, height, left, top, edge, edge, false);
            BinaryBitmap bitmap =
                    new BinaryBitmap(new HybridBinarizer(source));
            Result result = new QRCodeReader().decode(bitmap, hints);
            return result.getText();
        } catch (Exception e) {
            return null; // NotFound/Checksum/Format: no QR in this frame
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Dimmed overlay with a centered square cutout + signal corners. */
    private static class ViewfinderView extends View {
        private final Paint dim = new Paint();
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);

        ViewfinderView(Activity c) {
            super(c);
            dim.setColor(0x68000000);
            frame.setColor(0xE8FFFCF9);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(3f);
            corner.setColor(0xFFD9401A);
            corner.setStyle(Paint.Style.STROKE);
            corner.setStrokeWidth(9f);
            corner.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int edge = (int) (Math.min(w, h) * 0.68);
            int left = (w - edge) / 2;
            int top = (h - edge) / 2 - dp(24);
            if (top < dp(72)) top = dp(72);
            int right = left + edge;
            int bottom = top + edge;
            Rect full = new Rect(0, 0, w, h);
            Rect hole = new Rect(left, top, right, bottom);
            // Dim everything outside the square (four rects, no clip path so
            // it stays cheap at 60fps on old GPUs).
            canvas.drawRect(0, 0, w, top, dim);
            canvas.drawRect(0, bottom, w, h, dim);
            canvas.drawRect(0, top, left, bottom, dim);
            canvas.drawRect(right, top, w, bottom, dim);
            canvas.drawRect(left, top, right, bottom, frame);
            // Signal corners.
            float l = 34f;
            // top-left
            canvas.drawLine(left, top + l, left, top, corner);
            canvas.drawLine(left, top, left + l, top, corner);
            // top-right
            canvas.drawLine(right - l, top, right, top, corner);
            canvas.drawLine(right, top, right, top + l, corner);
            // bottom-left
            canvas.drawLine(left, bottom - l, left, bottom, corner);
            canvas.drawLine(left, bottom, left + l, bottom, corner);
            // bottom-right
            canvas.drawLine(right - l, bottom, right, bottom, corner);
            canvas.drawLine(right, bottom, right, bottom - l, corner);
        }

        private int dp(int v) {
            return Math.round(
                    v * getResources().getDisplayMetrics().density);
        }
    }
}
