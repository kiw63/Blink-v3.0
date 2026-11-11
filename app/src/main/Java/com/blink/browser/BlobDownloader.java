package com.blink.browser;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Downloads same-page blob: URLs into the public Downloads collection (Android 10+).
 * No JavaScriptInterface is exposed to arbitrary websites: the native application
 * polls the page using evaluateJavascript after a user-initiated download event.
 * Capped at 128 MiB so hostile pages cannot allocate unbounded native storage/memory.
 */
final class BlobDownloader {
    private static final int BLOCK = 256 * 1024;
    private static final long MAX_SIZE = 128L * 1024 * 1024;
    private static final int MAX_TRIES = 600;
    private static final java.util.Map<String, BlobDownloader> ACTIVE = new java.util.HashMap<>();
    private static final java.util.Map<String, BlobDownloader> RUNNING = new java.util.HashMap<>();

    private final Activity activity;
    private final WebView page;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String filename;
    private final String declaredMime;
    private final String blobUrl;
    private final String sessionId = java.util.UUID.randomUUID().toString();
    private Uri destination;
    private OutputStream output;
    private long offset;
    private long total;
    private int tries;
    private int lastMilestone = -1;
    private boolean done;

    private BlobDownloader(Activity activity, WebView page, String blobUrl,
                           String filename, String mimeType) {
        this.activity = activity;
        this.page = page;
        this.blobUrl = blobUrl;
        this.filename = safeName(filename);
        this.declaredMime = mimeType == null || mimeType.isEmpty()
                ? "application/octet-stream" : mimeType;
    }

    static void download(Activity activity, WebView page, String url, String filename, String mimeType) {
        if (page == null || !(url.startsWith("blob:") || url.startsWith("data:"))) {
            Toast.makeText(activity, "Tautan unduhan web tidak valid", Toast.LENGTH_LONG).show();
            return;
        }
        BlobDownloader worker = new BlobDownloader(activity, page, url, filename, mimeType);
        RUNNING.put(worker.sessionId, worker);
        worker.start();
    }

    /** Clean up transfers owned by a destroyed browser activity. */
    static void cancelAllFor(Activity owner) {
        java.util.ArrayList<BlobDownloader> work = new java.util.ArrayList<>(RUNNING.values());
        for (BlobDownloader job : work) {
            if (job.activity == owner) job.fail("Download dibatalkan: jendela ditutup");
        }
    }

    /** Main-thread cancellation from the Blink download center. */
    static void cancel(String uri) {
        BlobDownloader instance = ACTIVE.get(uri);
        if (instance != null) instance.fail("Download dibatalkan");
    }

    private void start() {
        Toast.makeText(activity, "Mempersiapkan file: " + filename, Toast.LENGTH_SHORT).show();
        handler.postDelayed(() -> {
            if (!done) fail("Download melewati batas waktu; coba lagi");
        }, 10 * 60 * 1000L);
        // Javascript string is encoded as a literal, never concatenated as executable source.
        String script = "(function(){try{window.__blinkBlobTransfers=window.__blinkBlobTransfers||{};"
            + "var s={status:'loading'};window.__blinkBlobTransfers[" + JSONObject.quote(sessionId) + "]=s;"
            + "fetch(" + JSONObject.quote(blobUrl) + ")"
            + ".then(function(r){if(!r.ok)throw Error('HTTP '+r.status);return r.blob()})"
            + ".then(function(b){s.blob=b;s.size=b.size;s.mime=b.type;s.status='ready'})"
            + ".catch(function(e){s.status='error';s.error=String(e)});return true}"
            + "catch(e){return false}})()";
        try {
            page.evaluateJavascript(script, unused -> handler.postDelayed(this::pollMetadata, 120));
        } catch (RuntimeException error) {
            fail("Halaman sudah ditutup");
        }
    }

    private void pollMetadata() {
        if (done) return;
        String js = "(function(){var s=window.__blinkBlobTransfers&&window.__blinkBlobTransfers["
                + JSONObject.quote(sessionId) + "];return JSON.stringify({status:s&&s.status,"
                + "size:s&&s.size,mime:s&&s.mime,error:s&&s.error})})()";
        evaluate(js, payload -> {
            if (done) return;
            JSONObject data = parse(payload);
            if (data == null) { retry(this::pollMetadata); return; }
            String status = data.optString("status");
            if ("error".equals(status)) { fail("Situs tidak menyediakan file blob"); return; }
            if (!"ready".equals(status)) { retry(this::pollMetadata); return; }
            total = data.optLong("size", -1);
            if (total < 0 || total > MAX_SIZE) {
                fail("Unduhan blob maksimal 128 MB; untuk file besar gunakan tautan HTTP/HTTPS");
                return;
            }
            String actualMime = data.optString("mime", declaredMime);
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                values.put(MediaStore.MediaColumns.MIME_TYPE,
                        actualMime.isEmpty() ? declaredMime : actualMime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Blink");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                destination = activity.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (destination == null) throw new IOException("Tidak bisa membuat berkas");
                ACTIVE.put(destination.toString(), this);
                DownloadCenter.startBlob(activity, destination, filename, actualMime);
                output = activity.getContentResolver().openOutputStream(destination, "w");
                if (output == null) throw new IOException("Penyimpanan tidak dapat dibuka");
                if (total == 0) finish();
                else readNextChunk();
            } catch (Exception error) {
                fail("Penyimpanan Downloads tidak tersedia");
            }
        });
    }

    private void readNextChunk() {
        if (done) return;
        tries = 0;
        // FileReader reads a bounded slice, so native memory never holds the whole blob.
        String js = "(function(){var s=window.__blinkBlobTransfers&&window.__blinkBlobTransfers["
            + JSONObject.quote(sessionId) + "];if(!s||s.status!=='ready')return false;"
            + "s.status='reading';s.chunk='';var r=new FileReader();"
            + "r.onload=function(){s.chunk=String(r.result).split(',')[1]||'';s.status='chunk'};"
            + "r.onerror=function(){s.status='error';s.error='Tidak dapat membaca file'};"
            + "r.readAsDataURL(s.blob.slice(" + offset + "," + Math.min(total, offset + BLOCK) + "));return true})()";
        evaluate(js, ignored -> handler.postDelayed(this::pollChunk, 100));
    }

    private void pollChunk() {
        if (done) return;
        String js = "(function(){var s=window.__blinkBlobTransfers&&window.__blinkBlobTransfers["
                + JSONObject.quote(sessionId) + "];return JSON.stringify({status:s&&s.status,"
                + "chunk:s&&s.status==='chunk'?s.chunk:'',error:s&&s.error})})()";
        evaluate(js, payload -> {
            if (done) return;
            JSONObject obj = parse(payload);
            if (obj == null) { retry(this::pollChunk); return; }
            String status = obj.optString("status");
            if ("error".equals(status)) { fail("Gagal membaca file dari halaman"); return; }
            if (!"chunk".equals(status)) { retry(this::pollChunk); return; }
            try {
                byte[] bytes = Base64.decode(obj.optString("chunk"), Base64.DEFAULT);
                long expected = Math.min(BLOCK, total - offset);
                if (bytes.length != expected) throw new IOException("Potongan file tidak lengkap");
                output.write(bytes);
                offset += bytes.length;
                if (offset == total) { finish(); return; }
                int milestone = (int) (100 * offset / total) / 25;
                if (milestone > lastMilestone) {
                    lastMilestone = milestone;
                    if (milestone > 0) Toast.makeText(activity,
                            "Unduhan " + filename + ": " + (milestone * 25) + "%",
                            Toast.LENGTH_SHORT).show();
                }
                // Drop previous base64 string before decoding the next slice.
                evaluate("(function(){var s=window.__blinkBlobTransfers&&window.__blinkBlobTransfers["
                        + JSONObject.quote(sessionId)
                        + "];if(s){s.chunk='';s.status='ready'}return true})()", ignored -> readNextChunk());
            } catch (Exception error) {
                fail("Tidak dapat menulis file unduhan");
            }
        });
    }

    private void retry(Runnable next) {
        if (++tries > MAX_TRIES) { fail("Proses unduhan berhenti atau halaman telah berubah"); return; }
        handler.postDelayed(next, 150);
    }

    private void evaluate(String script, android.webkit.ValueCallback<String> callback) {
        if (done) return;
        try {
            page.evaluateJavascript(script, callback);
        } catch (RuntimeException error) {
            fail("Halaman tertutup saat mengunduh");
        }
    }

    /** WebView JS result is a JSON-encoded STRING whose contents are a JSON object. */
    private JSONObject parse(String jsResult) {
        if (jsResult == null || "null".equals(jsResult)) return null;
        try {
            String value = new JSONArray("[" + jsResult + "]").getString(0);
            return new JSONObject(value);
        } catch (Exception ignored) { return null; }
    }

    private void finish() {
        if (done) return;
        try {
            output.flush();
            output.close();
            output = null;
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            activity.getContentResolver().update(destination, values, null, null);
            done = true;
            RUNNING.remove(sessionId);
            if (destination != null) {
                ACTIVE.remove(destination.toString());
                DownloadCenter.completedBlob(activity, destination);
            }
            clearPageBuffer();
            Toast.makeText(activity, "Selesai: Download/Blink/" + filename,
                    Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            fail("Gagal menyelesaikan file unduhan");
        }
    }

    private void fail(String message) {
        if (done) return;
        done = true;
        RUNNING.remove(sessionId);
        try { if (output != null) output.close(); } catch (IOException ignored) { }
        if (destination != null) {
            ACTIVE.remove(destination.toString());
            DownloadCenter.failedBlob(activity, destination);
            try { activity.getContentResolver().delete(destination, null, null); }
            catch (Exception ignored) { }
        }
        clearPageBuffer();
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private void clearPageBuffer() {
        try { page.evaluateJavascript("(function(){if(window.__blinkBlobTransfers)delete "
                + "window.__blinkBlobTransfers[" + JSONObject.quote(sessionId) + "]})()", null); }
        catch (RuntimeException ignored) { }
    }

    static String safeName(String input) {
        return SafeFilename.sanitize(input);
    }
}
