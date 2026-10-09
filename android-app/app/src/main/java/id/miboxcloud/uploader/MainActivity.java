package id.miboxcloud.uploader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "mibox_cloud_settings";
    private static final String KEY_URL = "server_url";
    private static final String KEY_TOKEN = "api_token";
    private static final int BLUE = Color.rgb(37, 99, 235);
    private static final int DARK = Color.rgb(20, 35, 65);
    private static final int MUTED = Color.rgb(100, 116, 139);
    private static final int BG = Color.rgb(245, 247, 251);
    private static final int BORDER = Color.rgb(226, 232, 240);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<SharedFile> sharedFiles = new ArrayList<>();
    private final ArrayList<FolderNode> navigation = new ArrayList<>();
    private final ArrayList<DriveFolder> folders = new ArrayList<>();

    private LinearLayout page;
    private LinearLayout folderList;
    private EditText searchField;
    private TextView currentPathText;
    private TextView connectionStatus;
    private ProgressBar folderProgress;
    private String lastMessage = "";

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String serverUrl() { return prefs().getString(KEY_URL, "").trim().replaceAll("/+$", ""); }
    private String token() { return prefs().getString(KEY_TOKEN, "").trim(); }
    private boolean configured() { return !serverUrl().isEmpty() && !token().isEmpty(); }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(29, 78, 216));
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarDividerColor(BORDER);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 52);
        }
        readSharedIntent(getIntent());
        if (navigation.isEmpty()) navigation.add(new FolderNode("root", "Drive Saya"));
        render();
        if (configured() && !sharedFiles.isEmpty()) loadFolders(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        sharedFiles.clear();
        readSharedIntent(intent);
        if (navigation.isEmpty()) navigation.add(new FolderNode("root", "Drive Saya"));
        render();
        if (configured() && !sharedFiles.isEmpty()) loadFolders(false);
    }

    private void readSharedIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        try {
            if (Intent.ACTION_SEND.equals(action)) {
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) uri = intent.getClipData().getItemAt(0).getUri();
                if (uri == null) uri = intent.getData();
                if (uri != null) addSharedFile(uri);
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Uri> uris = getParcelableUriList(intent);
                if (uris.isEmpty() && intent.getClipData() != null) {
                    for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                        Uri u = intent.getClipData().getItemAt(i).getUri(); if (u != null) uris.add(u);
                    }
                }
                for (Uri uri : uris) if (uri != null) addSharedFile(uri);
            }
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    private ArrayList<Uri> getParcelableUriList(Intent intent) {
        ArrayList<Uri> out = new ArrayList<>();
        ArrayList<?> raw = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (raw != null) for (Object item : raw) if (item instanceof Uri) out.add((Uri) item);
        return out;
    }

    private void addSharedFile(Uri uri) {
        for (SharedFile existing : sharedFiles) if (existing.uri.equals(uri)) return;
        String name = queryDisplayName(uri);
        long size = querySize(uri);
        String mime = getContentResolver().getType(uri);
        if (mime == null || mime.trim().isEmpty()) mime = "application/octet-stream";
        sharedFiles.add(new SharedFile(uri, name, size, mime));
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0) {
                    String value = cursor.getString(col);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        } catch (Exception ignored) { }
        finally { if (cursor != null) cursor.close(); }
        String last = uri.getLastPathSegment();
        return last == null ? "file.bin" : last;
    }

    private long querySize(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (col >= 0 && !cursor.isNull(col)) return cursor.getLong(col);
            }
        } catch (Exception ignored) { }
        finally { if (cursor != null) cursor.close(); }
        return -1L;
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(16), dp(18), dp(28));
        scroll.addView(page);
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView mark = new TextView(this);
        mark.setText("☁");
        mark.setTextSize(25);
        mark.setTextColor(Color.WHITE);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(BLUE, 16, 0, Color.TRANSPARENT));
        header.addView(mark, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout titleWrap = new LinearLayout(this);
        titleWrap.setOrientation(LinearLayout.VERTICAL);
        titleWrap.setPadding(dp(12), 0, 0, 0);
        TextView title = text("MiBox Cloud", 23, DARK, true);
        TextView subtitle = text("Universal File Uploader", 13, MUTED, false);
        titleWrap.addView(title); titleWrap.addView(subtitle);
        header.addView(titleWrap, new LinearLayout.LayoutParams(0, -2, 1));
        TextView settings = text("⚙", 23, BLUE, true);
        settings.setGravity(Gravity.CENTER);
        settings.setBackground(round(Color.WHITE, 16, 1, BORDER));
        settings.setOnClickListener(v -> renderSettings());
        header.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(header);

        TextView intro = text("Kirim file ke Google Drive dengan memilih folder tujuan secara manual.", 14, MUTED, false);
        intro.setPadding(dp(2), dp(12), dp(2), dp(14));
        page.addView(intro);

        LinearLayout connectionCard = card();
        LinearLayout cRow = row();
        TextView cIcon = text(configured() ? "●" : "○", 16, configured() ? Color.rgb(22, 163, 74) : Color.rgb(234, 88, 12), true);
        cRow.addView(cIcon, new LinearLayout.LayoutParams(dp(24), -2));
        LinearLayout cTexts = new LinearLayout(this); cTexts.setOrientation(LinearLayout.VERTICAL);
        cTexts.addView(text(configured() ? "Koneksi Mi Box" : "Atur koneksi Mi Box", 15, DARK, true));
        cTexts.addView(text(configured() ? serverUrl() : "Masukkan IP Tailscale dan token API", 12, MUTED, false));
        cRow.addView(cTexts, new LinearLayout.LayoutParams(0, -2, 1));
        Button cfg = button(configured() ? "Ubah" : "Atur");
        cfg.setOnClickListener(v -> renderSettings());
        cRow.addView(cfg);
        connectionCard.addView(cRow);
        if (!configured()) {
            TextView warning = text("Aplikasi belum terhubung. Tekan Atur, lalu isi alamat server dan token yang dibuat di Termux.", 13, MUTED, false);
            warning.setPadding(0, dp(10), 0, 0);
            connectionCard.addView(warning);
        }
        page.addView(connectionCard);

        if (sharedFiles.isEmpty()) {
            LinearLayout welcome = card();
            TextView icon = text("⇧", 34, BLUE, true); icon.setGravity(Gravity.CENTER);
            welcome.addView(icon, new LinearLayout.LayoutParams(-1, dp(60)));
            TextView heading = text("Siap menerima file", 19, DARK, true); heading.setGravity(Gravity.CENTER);
            welcome.addView(heading);
            TextView body = text("Buka galeri atau pengelola file, pilih satu atau beberapa file, tekan Bagikan, lalu pilih MiBox Cloud.", 14, MUTED, false);
            body.setGravity(Gravity.CENTER); body.setPadding(dp(4), dp(8), dp(4), dp(8));
            welcome.addView(body);
            Button settingsBtn = button("Konfigurasi koneksi"); settingsBtn.setOnClickListener(v -> renderSettings());
            welcome.addView(settingsBtn, matchWrap());
            page.addView(welcome);
            return;
        }

        LinearLayout filesCard = card();
        filesCard.addView(text("File yang akan dikirim", 17, DARK, true));
        filesCard.addView(text(sharedFiles.size() + (sharedFiles.size() == 1 ? " file dipilih" : " file dipilih"), 12, MUTED, false));
        for (SharedFile item : sharedFiles) {
            LinearLayout fRow = row();
            TextView fileIcon = text(iconForMime(item.mime), 25, BLUE, true); fileIcon.setGravity(Gravity.CENTER);
            fRow.addView(fileIcon, new LinearLayout.LayoutParams(dp(42), dp(46)));
            LinearLayout fBody = new LinearLayout(this); fBody.setOrientation(LinearLayout.VERTICAL);
            fBody.addView(text(item.name, 14, DARK, true));
            fBody.addView(text(item.size >= 0 ? formatSize(item.size) + " · " + item.mime : "Ukuran akan dibaca saat upload", 11, MUTED, false));
            fRow.addView(fBody, new LinearLayout.LayoutParams(0, -2, 1));
            filesCard.addView(fRow);
        }
        page.addView(filesCard);

        LinearLayout folderCard = card();
        LinearLayout folderHead = row();
        folderHead.addView(text("Pilih folder tujuan", 17, DARK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button refresh = smallButton("Refresh"); refresh.setOnClickListener(v -> loadFolders(true));
        folderHead.addView(refresh);
        folderCard.addView(folderHead);

        LinearLayout pathRow = row();
        Button up = smallButton("‹ Kembali");
        up.setEnabled(navigation.size() > 1);
        up.setOnClickListener(v -> {
            if (navigation.size() > 1) navigation.remove(navigation.size() - 1);
            folders.clear(); render(); loadFolders(false);
        });
        pathRow.addView(up);
        currentPathText = text(currentPath(), 12, MUTED, false);
        currentPathText.setPadding(dp(9), dp(8), 0, dp(8));
        pathRow.addView(currentPathText, new LinearLayout.LayoutParams(0, -2, 1));
        folderCard.addView(pathRow);

        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setTextSize(14);
        searchField.setHint("⌕  Cari folder...");
        searchField.setPadding(dp(13), dp(7), dp(13), dp(7));
        searchField.setBackground(round(Color.rgb(248, 250, 252), 12, 1, BORDER));
        folderCard.addView(searchField, matchWrap());

        LinearLayout actions = row();
        Button newFolder = button("＋ Folder baru");
        newFolder.setOnClickListener(v -> showCreateFolderDialog());
        actions.addView(newFolder, new LinearLayout.LayoutParams(0, dp(46), 1));
        folderCard.addView(actions);

        folderProgress = new ProgressBar(this);
        folderCard.addView(folderProgress, new LinearLayout.LayoutParams(dp(34), dp(34)));
        folderList = new LinearLayout(this); folderList.setOrientation(LinearLayout.VERTICAL);
        folderCard.addView(folderList);
        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderFolderRows(s.toString()); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        renderFolderRows(searchField.getText().toString());
        page.addView(folderCard);

        LinearLayout selectionCard = card();
        selectionCard.addView(text("Tujuan upload", 13, MUTED, false));
        selectionCard.addView(text(currentPath(), 16, DARK, true));
        TextView note = text("File yang sudah ada tidak akan ditimpa. Jika nama sama ditemukan, upload akan dihentikan agar tidak mengganti file lama.", 12, MUTED, false);
        note.setPadding(0, dp(5), 0, dp(10)); selectionCard.addView(note);
        Button upload = button(sharedFiles.size() == 1 ? "Upload ke folder ini" : "Upload semua ke folder ini");
        upload.setOnClickListener(v -> confirmUpload());
        selectionCard.addView(upload, matchWrap());
        page.addView(selectionCard);
        TextView footer = text("Transfer melalui koneksi privat. Notifikasi sukses muncul setelah Google Drive diverifikasi.", 11, MUTED, false);
        footer.setGravity(Gravity.CENTER); footer.setPadding(dp(6), dp(14), dp(6), 0); page.addView(footer);
    }

    private void renderSettings() {
        LinearLayout outer = new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL); outer.setPadding(dp(20), dp(24), dp(20), dp(20)); outer.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(outer); setContentView(scroll);
        TextView top = text("Koneksi Mi Box", 26, DARK, true); outer.addView(top);
        TextView help = text("Alamat server harus menggunakan IP Tailscale Mi Box, bukan IP Wi-Fi lokal. Token dibuat oleh installer cloud bridge di Termux.", 14, MUTED, false);
        help.setPadding(0, dp(8), 0, dp(18)); outer.addView(help);
        LinearLayout form = card();
        form.addView(text("Alamat server", 14, DARK, true));
        EditText url = new EditText(this); url.setSingleLine(true); url.setHint("http://100.x.y.z:8765"); url.setInputType( android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI ); url.setText(serverUrl()); form.addView(url, matchWrap());
        TextView tokenLabel = text("Token API", 14, DARK, true); tokenLabel.setPadding(0, dp(16), 0, 0); form.addView(tokenLabel);
        EditText secret = new EditText(this); secret.setSingleLine(true); secret.setHint("Tempel token dari Termux"); secret.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); secret.setText(token()); form.addView(secret, matchWrap());
        TextView security = text("Jangan bagikan token ini. Server membatasi koneksi ke jaringan privat Tailscale.", 12, MUTED, false); security.setPadding(0, dp(10), 0, 0); form.addView(security);
        outer.addView(form);
        Button save = button("Simpan dan tes koneksi");
        save.setOnClickListener(v -> {
            String u = url.getText().toString().trim().replaceAll("/+$", "");
            String t = secret.getText().toString().trim();
            if (!(u.startsWith("http://") || u.startsWith("https://")) || !u.contains(":")) {
                toast("Alamat server harus diawali http:// atau https://"); return;
            }
            if (t.length() < 32) { toast("Token belum lengkap. Salin token dari Termux."); return; }
            prefs().edit().putString(KEY_URL, u).putString(KEY_TOKEN, t).apply();
            hideKeyboard(url);
            testConnection();
        });
        outer.addView(save, matchWrap());
        Button back = ghostButton("Kembali"); back.setOnClickListener(v -> { render(); if (configured() && !sharedFiles.isEmpty()) loadFolders(false); });
        outer.addView(back, matchWrap());
        TextView extra = text("Perangkat Android dan Mi Box dapat memakai jaringan berbeda selama Tailscale aktif pada keduanya dan IP privatnya dapat dijangkau.", 12, MUTED, false);
        extra.setPadding(0, dp(16), 0, 0); outer.addView(extra);
    }

    private void testConnection() {
        new Thread(() -> {
            try {
                JSONObject obj = requestJson("GET", "/api/health", null);
                runOnUiThread(() -> {
                    String detail = "Koneksi berhasil · ruang kosong Mi Box " + formatSize(obj.optLong("free_bytes", 0));
                    toast(detail);
                    navigation.clear(); navigation.add(new FolderNode("root", "Drive Saya"));
                    folders.clear(); render();
                    if (!sharedFiles.isEmpty()) loadFolders(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Koneksi belum berhasil")
                        .setMessage(friendlyError(e)).setPositiveButton("OK", null).show());
            }
        }).start();
    }

    private void loadFolders(boolean showToast) {
        if (!configured()) return;
        if (folderProgress != null) folderProgress.setVisibility(View.VISIBLE);
        if (folderList != null) folderList.removeAllViews();
        String parent = currentNode().id;
        io.execute(() -> {
            try {
                JSONObject response = requestJson("GET", "/api/folders?parent=" + enc(parent), null);
                JSONArray array = response.getJSONArray("folders");
                ArrayList<DriveFolder> found = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject f = array.getJSONObject(i);
                    found.add(new DriveFolder(f.getString("id"), f.getString("name")));
                }
                Collections.sort(found, Comparator.comparing(a -> a.name.toLowerCase()));
                runOnUiThread(() -> {
                    folders.clear(); folders.addAll(found);
                    if (folderProgress != null) folderProgress.setVisibility(View.GONE);
                    if (folderList != null) renderFolderRows(searchField == null ? "" : searchField.getText().toString());
                    if (showToast) toast("Daftar folder diperbarui");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (folderProgress != null) folderProgress.setVisibility(View.GONE);
                    if (folderList != null) {
                        folderList.removeAllViews();
                        TextView err = text("Folder tidak dapat dimuat. " + friendlyError(e), 13, Color.rgb(185, 28, 28), false);
                        folderList.addView(err);
                    }
                    if (showToast) toast("Tidak dapat memuat folder");
                });
            }
        });
    }

    private void renderFolderRows(String filter) {
        if (folderList == null) return;
        folderList.removeAllViews();
        String needle = filter == null ? "" : filter.trim().toLowerCase();
        int count = 0;
        for (DriveFolder folder : folders) {
            if (!folder.name.toLowerCase().contains(needle)) continue;
            count++;
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setBackground(round(Color.WHITE, 12, 1, BORDER));
            TextView icon = text("📁", 24, Color.rgb(217, 119, 6), true); icon.setGravity(Gravity.CENTER);
            row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout textBox = new LinearLayout(this); textBox.setOrientation(LinearLayout.VERTICAL);
            textBox.addView(text(folder.name, 14, DARK, true));
            textBox.addView(text("Buka folder", 11, MUTED, false));
            row.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1));
            TextView arrow = text("›", 28, MUTED, false); arrow.setGravity(Gravity.CENTER);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(40)));
            row.setOnClickListener(v -> {
                navigation.add(new FolderNode(folder.id, folder.name));
                folders.clear(); render(); loadFolders(false);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(6), 0, dp(2));
            folderList.addView(row, lp);
        }
        if (count == 0) {
            TextView empty = text(needle.isEmpty() ? "Belum ada subfolder di lokasi ini." : "Folder tidak ditemukan.", 13, MUTED, false);
            empty.setGravity(Gravity.CENTER); empty.setPadding(0, dp(18), 0, dp(18));
            folderList.addView(empty);
        }
    }

    private void showCreateFolderDialog() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("Nama folder baru");
        input.setPadding(dp(12), dp(9), dp(12), dp(9));
        new AlertDialog.Builder(this)
                .setTitle("Buat folder baru")
                .setMessage("Folder dibuat di lokasi saat ini, lalu langsung dibuka sebagai tujuan upload.")
                .setView(input)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Buat folder", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) { toast("Nama folder tidak boleh kosong"); return; }
                    JSONObject body = new JSONObject();
                    try { body.put("parent_id", currentNode().id); body.put("name", name); }
                    catch (Exception ignored) { }
                    io.execute(() -> {
                        try {
                            JSONObject result = requestJson("POST", "/api/folders", body);
                            JSONObject f = result.getJSONObject("folder");
                            runOnUiThread(() -> {
                                navigation.add(new FolderNode(f.optString("id"), f.optString("name", name)));
                                folders.clear(); render(); loadFolders(false); toast("Folder dibuat di Google Drive");
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Folder gagal dibuat")
                                    .setMessage(friendlyError(e)).setPositiveButton("OK", null).show());
                        }
                    });
                }).show();
    }

    private void confirmUpload() {
        if (!configured()) { renderSettings(); return; }
        if (sharedFiles.isEmpty()) { toast("Pilih file dari menu Bagikan terlebih dahulu"); return; }
        StringBuilder list = new StringBuilder();
        for (SharedFile f : sharedFiles) list.append("• ").append(f.name).append(" ( ").append(f.size >= 0 ? formatSize(f.size) : "ukuran dibaca otomatis").append(" )\n");
        new AlertDialog.Builder(this)
                .setTitle("Konfirmasi upload")
                .setMessage("Lokasi: " + currentPath() + "\n\n" + list + "\nFile dengan nama yang sudah ada tidak akan ditimpa.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Mulai upload", (dialog, which) -> startUploadService())
                .show();
    }

    private void startUploadService() {
        ArrayList<String> uris = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        long[] sizes = new long[sharedFiles.size()];
        ClipData clip = null;
        for (int i = 0; i < sharedFiles.size(); i++) {
            SharedFile f = sharedFiles.get(i);
            uris.add(f.uri.toString()); names.add(f.name); sizes[i] = f.size;
            ClipData.Item item = new ClipData.Item(f.uri);
            if (clip == null) clip = new ClipData("MiBox Cloud upload", new String[]{f.mime}, item);
            else clip.addItem(item);
        }
        Intent intent = new Intent(this, UploadService.class);
        intent.putStringArrayListExtra(UploadService.EXTRA_URIS, uris);
        intent.putStringArrayListExtra(UploadService.EXTRA_NAMES, names);
        intent.putExtra(UploadService.EXTRA_SIZES, sizes);
        intent.putExtra(UploadService.EXTRA_PARENT_ID, currentNode().id);
        intent.putExtra(UploadService.EXTRA_FOLDER_LABEL, currentPath());
        intent.putExtra(UploadService.EXTRA_SERVER_URL, serverUrl());
        intent.putExtra(UploadService.EXTRA_TOKEN, token());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (clip != null) intent.setClipData(clip);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
            toast("Upload dimulai. Pantau progres dan notifikasi MiBox Cloud.");
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Tidak dapat memulai upload")
                    .setMessage(e.getMessage() == null ? "Android menolak memulai layanan upload." : e.getMessage())
                    .setPositiveButton("OK", null).show();
        }
    }

    private JSONObject requestJson(String method, String path, JSONObject body) throws Exception {
        String base = serverUrl();
        if (base.isEmpty()) throw new IOException("Alamat server belum diatur.");
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Authorization", "Bearer " + token());
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Cache-Control", "no-store");
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String response = readAll(stream);
        conn.disconnect();
        JSONObject obj;
        try { obj = new JSONObject(response); } catch (Exception e) { obj = new JSONObject(); }
        if (code < 200 || code >= 300) throw new IOException(obj.optString("error", "HTTP " + code));
        if (!obj.optBoolean("ok", false)) throw new IOException(obj.optString("error", "Permintaan gagal."));
        return obj;
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private FolderNode currentNode() { return navigation.get(navigation.size() - 1); }
    private String currentPath() {
        StringBuilder b = new StringBuilder();
        for (FolderNode node : navigation) { if (b.length() > 0) b.append(" / "); b.append(node.name); }
        return b.toString();
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(14), dp(14), dp(14), dp(14));
        l.setBackground(round(Color.WHITE, 18, 1, BORDER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, dp(12)); l.setElevation(dp(1));
        // Margins are added by callers using default page spacing; card returns with outside spacing in its view params.
        l.setLayoutParams(lp); return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setIncludeFontPadding(true); return t;
    }

    private Button button(String label) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(14);
        b.setBackground(round(BLUE, 12, 0, Color.TRANSPARENT)); b.setMinHeight(dp(44)); b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }
    private Button smallButton(String label) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(12); b.setTextColor(BLUE);
        b.setPadding(dp(8), 0, dp(8), 0); b.setMinHeight(dp(38)); b.setBackground(round(Color.rgb(239, 246, 255), 10, 1, Color.rgb(191, 219, 254))); return b;
    }
    private Button ghostButton(String label) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(DARK); b.setBackground(round(Color.WHITE, 12, 1, BORDER)); return b;
    }
    private GradientDrawable round(int color, int radiusDp, int borderWidthDp, int borderColor) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp));
        if (borderWidthDp > 0) d.setStroke(dp(borderWidthDp), borderColor); return d;
    }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private void hideKeyboard(View view) {
        try { ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.getWindowToken(), 0); } catch (Exception ignored) { }
    }
    private String enc(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); } catch (Exception e) { return value; }
    }
    private String friendlyError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return "Pastikan Mi Box menyala, Tailscale terhubung, alamat dan token benar, lalu coba lagi.";
        return m + "\n\nPeriksa Tailscale di HP dan Mi Box, IP server, token, serta log ~/cloud-bridge/logs/server.log di Termux.";
    }
    private String formatSize(long bytes) {
        if (bytes < 0) return "Ukuran tidak diketahui";
        if (bytes < 1024) return bytes + " B";
        double k = bytes / 1024.0;
        if (k < 1024) return new DecimalFormat("0.#").format(k) + " KB";
        double m = k / 1024.0; if (m < 1024) return new DecimalFormat("0.#").format(m) + " MB";
        return new DecimalFormat("0.##").format(m / 1024.0) + " GB";
    }
    private String iconForMime(String mime) {
        if (mime.startsWith("video/")) return "▶";
        if (mime.startsWith("image/")) return "▧";
        if (mime.startsWith("audio/")) return "♫";
        if (mime.contains("pdf")) return "PDF";
        if (mime.contains("zip") || mime.contains("compressed") || mime.contains("archive")) return "ZIP";
        if (mime.startsWith("text/") || mime.contains("document") || mime.contains("spreadsheet") || mime.contains("presentation")) return "▤";
        return "FILE";
    }

    private static class SharedFile {
        final Uri uri; final String name; final long size; final String mime;
        SharedFile(Uri uri, String name, long size, String mime) { this.uri = uri; this.name = name; this.size = size; this.mime = mime; }
    }
    private static class FolderNode {
        final String id; final String name;
        FolderNode(String id, String name) { this.id = id; this.name = name; }
    }
    private static class DriveFolder {
        final String id; final String name;
        DriveFolder(String id, String name) { this.id = id; this.name = name; }
    }
}
