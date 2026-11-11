package com.blink.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** Visible in-app download list for DownloadManager and MediaStore files. */
final class DownloadCenter {
    private static final String FILE = "blink_download_center";
    private static final String KEY = "entries";
    private static final int LIMIT = 120;
    private static java.lang.ref.WeakReference<AlertDialog> visibleDialog;
    private static final int WHITE = Color.rgb(238, 240, 244);
    private static final int MUTED = Color.rgb(180, 184, 192);
    private static final int BLUE = Color.rgb(86, 167, 255);
    private DownloadCenter() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static JSONArray records(Context context) {
        try { return new JSONArray(prefs(context).getString(KEY, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static void insert(Context ctx, JSONObject item) {
        JSONArray result = new JSONArray();
        result.put(item);
        JSONArray old = records(ctx);
        for (int i = 0; i < old.length() && result.length() < LIMIT; i++) {
            JSONObject row = old.optJSONObject(i);
            if (row != null) result.put(row);
        }
        prefs(ctx).edit().putString(KEY, result.toString()).apply();
    }

    private static void update(Context ctx, String key, String status) {
        JSONArray list = records(ctx);
        for (int i = 0; i < list.length(); i++) {
            JSONObject row = list.optJSONObject(i);
            if (row != null && key.equals(row.optString("key"))) {
                try { row.put("state", status); } catch (Exception ignored) { }
                prefs(ctx).edit().putString(KEY, list.toString()).apply();
                return;
            }
        }
    }

    static void startHttp(Context ctx, long id, String filename, String mime) {
        JSONObject row = new JSONObject();
        try {
            row.put("key", "dm:" + id);
            row.put("kind", "dm");
            row.put("dmId", id);
            row.put("name", filename);
            row.put("mime", mime == null ? "application/octet-stream" : mime);
            row.put("date", System.currentTimeMillis());
            row.put("state", "running");
            insert(ctx, row);
        } catch (Exception ignored) { }
    }

    static void startBlob(Context ctx, Uri uri, String filename, String mime) {
        JSONObject row = new JSONObject();
        try {
            row.put("key", "blob:" + uri);
            row.put("kind", "blob");
            row.put("uri", uri.toString());
            row.put("name", filename);
            row.put("mime", mime == null ? "application/octet-stream" : mime);
            row.put("date", System.currentTimeMillis());
            row.put("state", "running");
            insert(ctx, row);
        } catch (Exception ignored) { }
    }

    static void completedBlob(Context ctx, Uri uri) { update(ctx, "blob:" + uri, "complete"); }
    static void failedBlob(Context ctx, Uri uri) { update(ctx, "blob:" + uri, "failed"); }

    private static final class Snapshot {
        boolean found;
        boolean complete;
        boolean active;
        int progress = -1;
        String caption = "Status tidak tersedia";
        Uri openUri;
        String mime;
    }

    private static Snapshot inspect(Context ctx, JSONObject row) {
        Snapshot data = new Snapshot();
        data.mime = row.optString("mime", "application/octet-stream");
        if ("blob".equals(row.optString("kind"))) {
            String status = row.optString("state", "running");
            data.found = true;
            data.complete = "complete".equals(status);
            data.active = "running".equals(status);
            data.caption = data.complete ? "Selesai · Download/Blink" :
                    data.active ? "Mengunduh file dari halaman" : "Download gagal atau dibatalkan";
            if (data.complete) {
                try { data.openUri = Uri.parse(row.getString("uri")); }
                catch (Exception ignored) { data.complete = false; }
            }
            return data;
        }
        DownloadManager mgr = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        if (mgr == null) return data;
        long id = row.optLong("dmId", -1);
        if (id < 0) return data;
        try (Cursor cursor = mgr.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return data;
            data.found = true;
            int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long size = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            if (size > 0) data.progress = (int) Math.min(100, downloaded * 100.0 / size);
            if (state == DownloadManager.STATUS_SUCCESSFUL) {
                data.complete = true;
                data.progress = 100;
                data.caption = "Selesai · Download";
                data.openUri = mgr.getUriForDownloadedFile(id);
            } else if (state == DownloadManager.STATUS_RUNNING) {
                data.active = true;
                data.caption = "Mengunduh";
            } else if (state == DownloadManager.STATUS_PENDING) {
                data.active = true;
                data.caption = "Menunggu";
            } else if (state == DownloadManager.STATUS_PAUSED) {
                data.active = true;
                data.caption = "Tertunda (jaringan/server)";
            } else data.caption = "Gagal";
        } catch (Exception ignored) { data.caption = "Status tidak dapat dibaca"; }
        return data;
    }

    private static void open(Activity activity, Snapshot entry) {
        if (entry.openUri == null) {
            Toast.makeText(activity, "Berkas tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(entry.openUri,
                entry.mime == null || entry.mime.isEmpty() ? "*/*" : entry.mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { activity.startActivity(intent); }
        catch (ActivityNotFoundException exception) {
            Toast.makeText(activity, "Aplikasi untuk membuka tipe file ini tidak ditemukan", Toast.LENGTH_LONG).show();
        } catch (Exception exception) {
            Toast.makeText(activity, "File gagal dibuka", Toast.LENGTH_SHORT).show();
        }
    }

    private static void forget(Activity activity, JSONObject entry, boolean removeFile) {
        if (removeFile) {
            if ("dm".equals(entry.optString("kind"))) {
                DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) dm.remove(entry.optLong("dmId", -1));
            } else {
                try { BlobDownloader.cancel(entry.optString("uri")); } catch (Exception ignored) { }
                try { activity.getContentResolver().delete(Uri.parse(entry.getString("uri")), null, null); }
                catch (Exception ignored) { }
            }
        }
        String key = entry.optString("key");
        JSONArray old = records(activity), updated = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item != null && !key.equals(item.optString("key"))) updated.put(item);
        }
        prefs(activity).edit().putString(KEY, updated.toString()).apply();
        show(activity);
    }

    private static TextView button(Activity activity, String label, Runnable action) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextSize(14);
        view.setTextColor(BLUE);
        view.setPadding(14, 14, 22, 14);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    static void show(Activity activity) {
        if (visibleDialog != null) {
            AlertDialog old = visibleDialog.get();
            if (old != null && old.isShowing()) old.dismiss();
        }
        JSONArray list = records(activity);
        ScrollView scroller = new ScrollView(activity);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(24, 12, 24, 12);
        rows.setBackgroundColor(Color.rgb(32, 32, 37));
        scroller.addView(rows);
        if (list.length() == 0) {
            TextView none = new TextView(activity);
            none.setText("Belum ada file di daftar Download Blink.");
            none.setTextColor(MUTED);
            none.setTextSize(15);
            none.setPadding(12, 25, 12, 25);
            rows.addView(none);
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            Snapshot state = inspect(activity, item);
            TextView title = new TextView(activity);
            title.setText(item.optString("name", "download"));
            title.setTextColor(WHITE);
            title.setTextSize(16);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            title.setPadding(10, 18, 10, 2);
            rows.addView(title);
            TextView status = new TextView(activity);
            String progress = state.progress < 0 ? "" : " · " + state.progress + "%";
            status.setText(state.caption + progress);
            status.setTextSize(13);
            status.setTextColor(MUTED);
            status.setPadding(10, 0, 10, 2);
            rows.addView(status);
            LinearLayout commands = new LinearLayout(activity);
            commands.setOrientation(LinearLayout.HORIZONTAL);
            commands.setGravity(Gravity.CENTER_VERTICAL);
            rows.addView(commands);
            if (state.complete) {
                commands.addView(button(activity, "Buka", () -> open(activity, state)));
            }
            if (state.active) {
                commands.addView(button(activity, "Batalkan", () ->
                    new AlertDialog.Builder(activity)
                        .setTitle("Batalkan download?")
                        .setMessage(item.optString("name"))
                        .setNegativeButton("Tidak", null)
                        .setPositiveButton("Batalkan", (d, w) -> forget(activity, item, true))
                        .show()));
            } else {
                commands.addView(button(activity, "Hapus dari daftar", () -> forget(activity, item, false)));
                commands.addView(button(activity, "Hapus file", () ->
                    new AlertDialog.Builder(activity)
                        .setTitle("Hapus file dari perangkat?")
                        .setMessage(item.optString("name"))
                        .setNegativeButton("Tidak", null)
                        .setPositiveButton("Hapus file", (d, w) -> forget(activity, item, true))
                        .show()));
            }
            View divider = new View(activity);
            divider.setBackgroundColor(Color.rgb(62, 62, 69));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
            lp.topMargin = 8;
            rows.addView(divider, lp);
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Download Blink (" + list.length() + ")")
                .setView(scroller)
                .setNegativeButton("Tutup", null)
                .setNeutralButton("Segarkan", (d, which) -> show(activity))
                .setPositiveButton("Unduhan Android", (d, which) -> {
                    try { activity.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); }
                    catch (Exception ignored) { Toast.makeText(activity,
                            "Pengelola file Android tidak tersedia", Toast.LENGTH_SHORT).show(); }
                }).create();
        visibleDialog = new java.lang.ref.WeakReference<>(dialog);
        dialog.show();
    }
}
