package com.bucket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.widget.CheckBox;
import android.widget.Toast;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Receives the Android share sheet and posts straight into the bucket.
 *
 *  This is the half a plain-HTTP web app cannot have: the web share_target
 *  needs a secure context, an intent-filter does not. Uploading here rather
 *  than opening the page means sharing stays a one-tap system action.
 *
 *  Sharing 2+ files asks first: zip into one item, or send separately —
 *  with a "remember" checkbox that flips the same account-wide batch mode
 *  the PC page's composer switch controls. The choice is resolved here, at
 *  share time, so it also covers the offline path: the outbox stores the
 *  flag in meta.json and the flush only replays it.
 *
 *  When the laptop is not reachable (out of the house, server down) the
 *  share is not lost: the bytes are copied into the app's offline outbox
 *  right now — content URI grants die with this activity, so deferring the
 *  read is not an option — and MainActivity flushes the queue the next
 *  time it finds the server.
 */
public class ShareActivity extends Activity {
    private static final String TAG = "Bucket";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Warm the cookie manager on the UI thread; later reads happen on
        // worker threads where first-time initialization is not allowed.
        CookieManager.getInstance();

        final Intent intent = getIntent();
        if (intent == null) { finish(); return; }

        final List<Uri> files = new ArrayList<>();
        Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (single != null) files.add(single);
        ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (many != null) files.addAll(many);

        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        final String shared = text == null ? null : text.toString().trim();

        if (files.isEmpty() && (shared == null || shared.isEmpty())) {
            toast("Nothing to send");
            finish();
            return;
        }

        toast("Sending to Bucket…");
        new Thread(new Runnable() {
            @Override public void run() {
                resolveAndDeliver(files, shared);
            }
        }).start();
    }

    /** One share's answer from the choice dialog. */
    private static final class Choice {
        final boolean zip;
        final boolean remember;

        Choice(boolean zip, boolean remember) {
            this.zip = zip;
            this.remember = remember;
        }
    }

    /** Try the remembered server first — a full subnet sweep makes no sense
     *  from a carrier network, so away from home this stays fast and falls
     *  through to the outbox. */
    private void resolveAndDeliver(List<Uri> files, String text) {
        String base = Server.saved(this);
        boolean online = base != null && Server.alive(base, 1200);
        String cookie = online ? Uploader.cookieFor(base) : null;

        boolean zip = false;
        if (files.size() > 1) {
            String mode = Server.batchMode(this);
            if (cookie != null) {
                // Paired and reachable: the server is the source of truth,
                // so a choice remembered on the PC page applies here too.
                String fetched = Server.fetchBatchMode(base, cookie);
                if (fetched != null) {
                    mode = fetched;
                    Server.saveBatchMode(this, mode);
                }
            }
            if (Server.BATCH_ASK.equals(mode)) {
                Choice choice = askUser(files.size());
                if (choice == null) {
                    finishWith("Cancelled");
                    return;
                }
                zip = choice.zip;
                if (choice.remember) {
                    String remembered = zip ? Server.BATCH_ZIP : Server.BATCH_SEPARATE;
                    Server.saveBatchMode(this, remembered);
                    // Offline there is nothing to push to; the mirror covers
                    // later offline shares and converges on next contact.
                    if (cookie != null) Server.pushBatchMode(base, cookie, remembered);
                }
            } else {
                zip = Server.BATCH_ZIP.equals(mode);
            }
        }

        if (cookie != null
                && Uploader.post(base, cookie, parts(files), text, zip)) {
            finishWith(zip && files.size() > 1 ? "Sent as one zip" : "Sent to Bucket");
            return;
        }
        Log.i(TAG, "share: queuing for later (server unreachable or unpaired)");

        int pending = Outbox.store(this, files, text, zip);
        if (pending >= 0) {
            finishWith(pending == 1 ? "Saved for later — 1 item pending"
                                    : "Saved for later — " + pending + " items pending");
        } else {
            finishWith("Bucket outbox is full — share not saved");
        }
    }

    /** Shows the zip-vs-separate dialog and blocks the calling (worker)
     *  thread until the user answers. Null when dismissed without choosing —
     *  a cancelled share sends nothing, not even to the outbox. */
    private Choice askUser(int count) {
        final CountDownLatch gate = new CountDownLatch(1);
        final Choice[] answer = new Choice[1];
        runOnUiThread(new Runnable() {
            @Override public void run() {
                showChoiceDialog(count, answer, gate);
            }
        });
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return answer[0];
    }

    private void showChoiceDialog(int count, final Choice[] answer,
            final CountDownLatch gate) {
        final CheckBox remember = new CheckBox(this);
        remember.setText("Remember this choice");
        float density = getResources().getDisplayMetrics().density;
        remember.setPadding((int) (20 * density), (int) (10 * density),
                (int) (20 * density), (int) (10 * density));
        new AlertDialog.Builder(this)
                .setTitle(count + " files")
                .setMessage("Zip them into one item, or send them separately?"
                        + " Any shared text still arrives on its own.")
                .setView(remember)
                .setPositiveButton("Zip into one", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        answer[0] = new Choice(true, remember.isChecked());
                        gate.countDown();
                    }
                })
                .setNegativeButton("Send separately", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        answer[0] = new Choice(false, remember.isChecked());
                        gate.countDown();
                    }
                })
                .setNeutralButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        gate.countDown();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) {
                        gate.countDown();
                    }
                })
                .show();
    }

    private List<Uploader.Part> parts(List<Uri> files) {
        final ContentResolver cr = getApplicationContext().getContentResolver();
        List<Uploader.Part> parts = new ArrayList<>();
        for (final Uri uri : files) {
            String mime = cr.getType(uri);
            if (mime == null) mime = "application/octet-stream";
            parts.add(new Uploader.Part(Uploader.displayName(cr, uri), mime,
                    new Uploader.Source() {
                        @Override public java.io.InputStream open() throws Exception {
                            return cr.openInputStream(uri);
                        }
                    }));
        }
        return parts;
    }

    private void finishWith(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                toast(message);
                finish();
            }
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
