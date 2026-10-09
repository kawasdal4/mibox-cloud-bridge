package id.miboxcloud.uploader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;

public class UploadService extends Service {
    public static final String EXTRA_URIS = "uris";
    public static final String EXTRA_NAMES = "names";
    public static final String EXTRA_SIZES = "sizes";
    public static final String EXTRA_PARENT_ID = "parent_id";
    public static final String EXTRA_FOLDER_LABEL = "folder_label";
    public static final String EXTRA_SERVER_URL = "server_url";
    public static final String EXTRA_TOKEN = "token";

    private static final String CHANNEL_ID = "mibox_cloud_uploads";
    private static final int FOREGROUND_ID = 4100;
    private static final int SUMMARY_ID = 4199;
    private volatile boolean running = false;
    private volatile int latestStartId = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (running) {
            postNotification(SUMMARY_ID, "Upload sedang berjalan", "Tunggu proses aktif selesai sebelum memulai antrean lain.", false);
            return START_NOT_STICKY;
        }
        running = true;
        startForeground(FOREGROUND_ID, buildNotification("Menyiapkan upload…", "MiBox Cloud", true, -1));
        if (intent == null) {
            finishService();
            return START_NOT_STICKY;
        }
        ArrayList<String> uris = intent.getStringArrayListExtra(EXTRA_URIS);
        ArrayList<String> names = intent.getStringArrayListExtra(EXTRA_NAMES);
        long[] sizes = intent.getLongArrayExtra(EXTRA_SIZES);
        String parentId = intent.getStringExtra(EXTRA_PARENT_ID);
        String folderLabel = intent.getStringExtra(EXTRA_FOLDER_LABEL);
        String serverUrl = intent.getStringExtra(EXTRA_SERVER_URL);
        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (uris == null || names == null || sizes == null || uris.isEmpty() || uris.size() != names.size() || uris.size() != sizes.length
                || parentId == null || serverUrl == null || token == null) {
            postNotification(SUMMARY_ID, "Upload tidak dimulai", "Data permintaan tidak lengkap.", false);
            finishService();
            return START_NOT_STICKY;
        }
        // Service starts while the share target Activity is visible. The foreground notification keeps the task apparent.
        new Thread(() -> runUploadQueue(startId, uris, names, sizes, parentId, folderLabel == null ? "Google Drive" : folderLabel,
                serverUrl.replaceAll("/+$", ""), token), "mibox-cloud-upload").start();
        return START_NOT_STICKY;
    }

    private void runUploadQueue(int startId, ArrayList<String> uriValues, ArrayList<String> names, long[] sizes,
                                String parentId, String folderLabel, String serverUrl, String token) {
        int succeeded = 0;
        int failed = 0;
        ArrayList<String> failures = new ArrayList<>();
        int total = names.size();
        for (int i = 0; i < total; i++) {
            String name = names.get(i);
            File unknownSizeTemp = null;
            try {
                Uri uri = Uri.parse(uriValues.get(i));
                long size = sizes[i];
                FileInfo info;
                if (size < 0) {
                    updateForeground("Mengukur ukuran file…", name, true, -1);
                    unknownSizeTemp = copyToTemporaryFile(uri);
                    info = new FileInfo(unknownSizeTemp, unknownSizeTemp.length());
                } else {
                    info = new FileInfo(uri, size);
                }
                uploadOne(i, total, name, info, parentId, folderLabel, serverUrl, token);
                succeeded++;
                postNotification(4200 + i, "Upload berhasil", name + " tersimpan dan terverifikasi di Google Drive.", false);
            } catch (Exception e) {
                failed++;
                String message = safeMessage(e);
                failures.add(name + ": " + message);
                postNotification(4200 + i, "Upload belum berhasil", name + " — " + message, false);
            } finally {
                if (unknownSizeTemp != null) {
                    // Only our local cache copy is removed; the shared source file is never deleted.
                    try { if (unknownSizeTemp.exists()) unknownSizeTemp.delete(); } catch (Exception ignored) { }
                }
            }
        }
        String summary = succeeded + " berhasil, " + failed + " gagal dari " + total + " file. Tujuan: " + folderLabel;
        postNotification(SUMMARY_ID, failed == 0 ? "Semua upload selesai" : "Upload selesai dengan kendala", summary, false);
        if (failed > 0) {
            StringBuilder detail = new StringBuilder();
            for (String f : failures) detail.append("• ").append(f).append("\n");
            android.util.Log.w("MiBoxCloud", "Upload errors: " + detail);
        }
        finishService();
    }

    private void uploadOne(int index, int total, String name, FileInfo info, String parentId, String folderLabel,
                           String serverUrl, String token) throws Exception {
        String path = "/api/upload?parent_id=" + enc(parentId) + "&name=" + enc(name) + "&size=" + info.size;
        HttpURLConnection connection = (HttpURLConnection) new URL(serverUrl + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(0); // Large files may take a long time; server verifies before responding.
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setRequestProperty("X-File-Name", name);
        connection.setFixedLengthStreamingMode(info.size);

        long sent = 0;
        byte[] buffer = new byte[256 * 1024];
        try (InputStream input = info.open(this); OutputStream output = connection.getOutputStream()) {
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (sent + n > info.size) throw new IOException("Ukuran file berubah saat dibaca; batalkan dan bagikan ulang file.");
                output.write(buffer, 0, n);
                sent += n;
                if (sent - info.lastProgress >= 1024 * 1024 || sent == info.size) {
                    info.lastProgress = sent;
                    int percent = info.size > 0 ? (int) Math.min(100, (sent * 100L) / info.size) : 0;
                    updateForeground("Mengirim file " + (index + 1) + "/" + total + " · " + percent + "%", name, false, percent);
                }
            }
            if (sent != info.size) throw new IOException("Transfer terputus: data yang dibaca tidak lengkap.");
        } catch (Exception e) {
            connection.disconnect();
            throw e;
        }

        updateForeground("Memverifikasi di Google Drive…", name, true, -1);
        int responseCode = connection.getResponseCode();
        InputStream responseStream = responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream();
        String responseText = readAll(responseStream);
        connection.disconnect();
        JSONObject response;
        try { response = new JSONObject(responseText); } catch (Exception e) { response = new JSONObject(); }
        if (responseCode >= 200 && responseCode < 300 && response.optBoolean("ok") && response.optBoolean("verified")) {
            String method = response.optString("verification", "size verification");
            android.util.Log.i("MiBoxCloud", "Verified upload: " + name + " / " + method);
            return;
        }
        String error = response.optString("error", "HTTP " + responseCode + ". Periksa folder tujuan sebelum mencoba ulang.");
        if (response.optBoolean("verification_pending")) {
            throw new IOException("Transfer dikirim tetapi verifikasi belum pasti. Periksa Drive sebelum upload ulang.");
        }
        throw new IOException(error);
    }

    private File copyToTemporaryFile(Uri uri) throws Exception {
        File file = File.createTempFile("mibox-cloud-", ".cache", getCacheDir());
        try (InputStream input = getContentResolver().openInputStream(uri); FileOutputStream output = new FileOutputStream(file)) {
            if (input == null) throw new IOException("Sumber file tidak dapat dibuka.");
            byte[] buffer = new byte[128 * 1024]; int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        } catch (Exception e) {
            file.delete(); throw e;
        }
        return file;
    }

    private void updateForeground(String title, String text, boolean indeterminate, int percent) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title).setContentText(text)
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(openAppPendingIntent());
        if (indeterminate) builder.setProgress(0, 0, true);
        else builder.setProgress(100, Math.max(0, percent), false);
        getSystemService(NotificationManager.class).notify(FOREGROUND_ID, builder.build());
    }

    private Notification buildNotification(String title, String text, boolean ongoing, int progress) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title).setContentText(text).setOngoing(ongoing)
                .setCategory(ongoing ? Notification.CATEGORY_PROGRESS : Notification.CATEGORY_STATUS)
                .setContentIntent(openAppPendingIntent()).setAutoCancel(!ongoing);
        if (progress < 0) b.setProgress(0, 0, true); else b.setProgress(100, progress, false);
        return b.build();
    }

    private void postNotification(int id, String title, String text, boolean ongoing) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(ongoing).setAutoCancel(!ongoing).setContentIntent(openAppPendingIntent());
        getSystemService(NotificationManager.class).notify(id, b.build());
    }

    private PendingIntent openAppPendingIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 1, intent, flags);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Upload MiBox Cloud", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Progres upload file dan konfirmasi setelah verifikasi Google Drive");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        try (InputStream in = input) { while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); }
        return out.toString("UTF-8");
    }

    private String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return "periksa koneksi Tailscale dan folder Google Drive";
        if (m.length() > 180) m = m.substring(0, 180) + "…";
        return m;
    }

    private void finishService() {
        try { stopForeground(true); } catch (Exception ignored) { }
        running = false;
        stopSelf(latestStartId);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static class FileInfo {
        final Object source;
        final long size;
        long lastProgress = 0;
        FileInfo(Uri uri, long size) { this.source = uri; this.size = size; }
        FileInfo(File file, long size) { this.source = file; this.size = size; }
        InputStream open(Context context) throws Exception {
            if (source instanceof Uri) {
                InputStream stream = context.getContentResolver().openInputStream((Uri) source);
                if (stream == null) throw new IOException("Sumber file tidak dapat dibuka oleh Android.");
                return stream;
            }
            return new FileInputStream((File) source);
        }
    }
}
