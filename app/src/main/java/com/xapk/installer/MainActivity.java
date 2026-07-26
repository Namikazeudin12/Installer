package com.xapk.installer;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "XAPKInstaller";
    // ⚠️ GANTI URL INI SESUAI RELEASE GITHUB ANDA
    private static final String ZIP_URL = "https://github.com/USERNAME/REPO/releases/download/v1.0/Tokped.zip";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private TextView tvLog;
    private ScrollView scrollView;
    private CheckBox cbChangeDate;
    private Button btnInstall;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tvLog);
        scrollView = findViewById(R.id.scrollView);
        cbChangeDate = findViewById(R.id.cbChangeDate);
        btnInstall = findViewById(R.id.btnInstall);

        cbChangeDate.setChecked(true);
        btnInstall.setOnClickListener(v -> checkPermissionsAndStart());
    }

    private void checkPermissionsAndStart() {
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.REQUEST_INSTALL_PACKAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.REQUEST_INSTALL_PACKAGES);
            }
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            new InstallTask().execute();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                new InstallTask().execute();
            } else {
                log("❌ Izin ditolak");
            }
        }
    }

    private void log(String message) {
        runOnUiThread(() -> {
            tvLog.append(message + "\n");
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    // ============================================================
    // ROOT SHELL - Robust & Interactive
    // ============================================================
    private class RootShell {
        private Process process;
        private DataOutputStream stdin;
        private BufferedReader stdout;
        private BufferedReader stderr;
        private boolean isAlive = false;

        public RootShell() throws IOException {
            String[] suVariants = {"su", "su 0", "su root"};
            IOException lastError = null;

            for (String suCmd : suVariants) {
                try {
                    Log.d(TAG, "Mencoba root: " + suCmd);
                    process = Runtime.getRuntime().exec(suCmd.split(" "));
                    stdin = new DataOutputStream(process.getOutputStream());
                    stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));

                    // Verifikasi root dengan 'id'
                    stdin.writeBytes("id\n");
                    stdin.flush();

                    String id = readLineWithTimeout(3000);
                    Log.d(TAG, "Output id: " + id);

                    if (id != null && id.contains("uid=0")) {
                        isAlive = true;
                        log("✅ Root aktif (" + suCmd + "): " + id.trim());
                        return;
                    } else {
                        destroy();
                    }
                } catch (IOException e) {
                    lastError = e;
                    destroy();
                }
            }
            throw new IOException("Gagal mendapatkan akses root. Pastikan VSPhone sudah di-root.", lastError);
        }

        public synchronized String execute(String command) throws IOException {
            if (!isAlive) throw new IOException("Shell root sudah ditutup");

            log("🔧 [ROOT] " + command);

            // Bersihkan buffer lama
            while (stdout.ready()) stdout.readLine();
            while (stderr.ready()) stderr.readLine();

            // Kirim perintah + marker exit code
            stdin.writeBytes(command + "\n");
            stdin.writeBytes("echo \"__EXITCODE__:$?\"\n");
            stdin.flush();

            StringBuilder output = new StringBuilder();
            StringBuilder errBuf = new StringBuilder();
            boolean done = false;
            long deadline = System.currentTimeMillis() + 60000; // 60 detik timeout

            while (System.currentTimeMillis() < deadline && !done) {
                try {
                    if (stdout.ready()) {
                        String line = stdout.readLine();
                        if (line == null) break;

                        if (line.startsWith("__EXITCODE__:")) {
                            int code = Integer.parseInt(line.substring(13));
                            if (code != 0) {
                                while (stderr.ready()) {
                                    String e = stderr.readLine();
                                    if (e != null) errBuf.append(e).append("\n");
                                }
                                throw new IOException(
                                    "GAGAL (exit " + code + ")\nCmd: " + command +
                                    "\nStderr: " + errBuf.toString().trim()
                                );
                            }
                            done = true;
                        } else {
                            output.append(line).append("\n");
                            final String l = line;
                            runOnUiThread(() -> log("   → " + l));
                        }
                    }

                    if (stderr.ready()) {
                        String line = stderr.readLine();
                        if (line != null) {
                            errBuf.append(line).append("\n");
                            final String e = line;
                            runOnUiThread(() -> log("   ⚠️ " + e));
                        }
                    }

                    if (!done) Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (!done) {
                throw new IOException("Timeout menunggu perintah: " + command);
            }
            return output.toString().trim();
        }

        private String readLineWithTimeout(int timeoutMs) throws IOException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (stdout.ready()) return stdout.readLine();
                try { Thread.sleep(50); } catch (InterruptedException e) { break; }
            }
            return null;
        }

        public void close() {
            isAlive = false;
            try { stdin.writeBytes("exit\n"); stdin.flush(); } catch (Exception ignored) {}
            destroy();
        }

        private void destroy() {
            try { if (stdin != null) stdin.close(); } catch (Exception ignored) {}
            try { if (stdout != null) stdout.close(); } catch (Exception ignored) {}
            try { if (stderr != null) stderr.close(); } catch (Exception ignored) {}
            if (process != null) process.destroy();
        }
    }

    // ============================================================
    // INSTALL TASK
    // ============================================================
    private class InstallTask extends AsyncTask<Void, String, Boolean> {
        private ProgressDialog progressDialog;
        private RootShell rootShell;

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(MainActivity.this);
            progressDialog.setMessage("Memproses...");
            progressDialog.setCancelable(false);
            progressDialog.show();
            tvLog.setText("");
            log("🚀 Memulai proses...");
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                // 1. Root shell
                publishProgress("🔐 Membuka root shell...");
                rootShell = new RootShell();

                // 2. Download
                File cacheDir = getExternalCacheDir();
                if (cacheDir == null) cacheDir = getCacheDir();
                File zipFile = new File(cacheDir, "Tokped.zip");

                publishProgress("⬇️ Mengunduh ZIP...");
                downloadFile(ZIP_URL, zipFile);
                log("✅ Download selesai: " + zipFile.length() + " bytes");

                // 3. Extract ZIP
                File extractDir = new File(cacheDir, "extracted");
                deleteRecursive(extractDir);
                extractDir.mkdirs();

                publishProgress("📦 Mengekstrak ZIP...");
                unzip(zipFile, extractDir);
                log("✅ Ekstrak ZIP selesai");

                // 4. Cari XAPK
                List<File> xapkFiles = findXapkFiles(extractDir);
                log("📁 Ditemukan " + xapkFiles.size() + " file XAPK");

                if (xapkFiles.isEmpty()) {
                    log("❌ Tidak ada file XAPK");
                    return false;
                }

                // 5. Proses tiap XAPK
                for (File xapk : xapkFiles) {
                    log("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    log("🔹 Memproses: " + xapk.getName());
                    processXapk(xapk);
                }

                // 6. Ubah tanggal
                if (cbChangeDate.isChecked()) {
                    log("\n📅 Mengubah tanggal sistem...");
                    changeSystemDate();
                }

                log("\n🎉 SEMUA PROSES SELESAI");
                return true;

            } catch (Exception e) {
                log("\n❌ ERROR: " + e.getMessage());
                Log.e(TAG, "Install error", e);
                return false;
            } finally {
                if (rootShell != null) rootShell.close();
            }
        }

        private void processXapk(File xapkFile) throws Exception {
            File workDir = new File(xapkFile.getParent(), xapkFile.getName() + "_extracted");
            deleteRecursive(workDir);
            workDir.mkdirs();

            log("   📦 Mengekstrak XAPK...");
            unzip(xapkFile, workDir);

            // Cari package name dari manifest.json jika ada
            String packageName = readPackageNameFromManifest(workDir);

            // Cari APK
            List<File> apkFiles = new ArrayList<>();
            findFiles(workDir, ".apk", apkFiles);
            log("   📱 Ditemukan " + apkFiles.size() + " APK");

            if (apkFiles.isEmpty()) {
                log("   ⚠️ Tidak ada APK");
                deleteRecursive(workDir);
                return;
            }

            // Deteksi package name dari APK jika manifest tidak ada
            if (packageName == null) {
                packageName = detectPackageNameFromApk(apkFiles.get(0));
            }
            if (packageName != null) {
                log("   📦 Package: " + packageName);
            }

            // Install
            if (apkFiles.size() == 1) {
                installSingleApk(apkFiles.get(0));
            } else {
                installSplitApks(apkFiles);
            }

            // Verifikasi instalasi
            if (packageName != null) {
                try {
                    String verify = rootShell.execute("pm list packages " + packageName);
                    if (verify.contains(packageName)) {
                        log("   ✅ Verifikasi: Package terinstal");
                    } else {
                        log("   ⚠️ Verifikasi: Package TIDAK ditemukan");
                    }
                } catch (Exception e) {
                    log("   ⚠️ Verifikasi gagal: " + e.getMessage());
                }
            }

            // Pindahkan OBB
            log("   📂 Memindahkan OBB...");
            moveObbFiles(workDir);

            // Bersihkan
            deleteRecursive(workDir);
        }

        private String readPackageNameFromManifest(File dir) {
            File manifest = new File(dir, "manifest.json");
            if (!manifest.exists()) return null;
            try {
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(manifest));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject json = new JSONObject(sb.toString());
                return json.optString("package_name", null);
            } catch (Exception e) {
                Log.w(TAG, "Gagal baca manifest.json", e);
            }
            return null;
        }

        private String detectPackageNameFromApk(File apk) {
            try {
                String result = rootShell.execute("aapt dump badging \"" + apk.getAbsolutePath() + "\" | grep package:");
                Matcher m = Pattern.compile("name='([^']+)'").matcher(result);
                if (m.find()) return m.group(1);
            } catch (Exception e) {
                Log.w(TAG, "aapt tidak tersedia", e);
            }
            return null;
        }

        private void installSingleApk(File apk) throws Exception {
            log("   🔧 Install: " + apk.getName());
            String result = rootShell.execute("pm install -r -d -t \"" + apk.getAbsolutePath() + "\"");
            log("   ✅ Result: " + (result.isEmpty() ? "Success" : result));
        }

        private void installSplitApks(List<File> apks) throws Exception {
            log("   🔧 Install split APKs...");

            File baseApk = null;
            List<File> splits = new ArrayList<>();
            for (File apk : apks) {
                String n = apk.getName().toLowerCase();
                if (n.equals("base.apk") || n.startsWith("base")) baseApk = apk;
                else splits.add(apk);
            }
            if (baseApk == null) baseApk = apks.get(0);

            // Buat session
            String createResult = rootShell.execute("pm install-create -r -d -t");
            log("   📋 Session create: " + createResult);

            int sessionId = parseSessionId(createResult);
            if (sessionId == -1) {
                throw new IOException("Gagal parsing Session ID dari: " + createResult);
            }
            log("   📋 Session ID: " + sessionId);

            // Tulis base
            rootShell.execute("pm install-write " + sessionId + " base \"" + baseApk.getAbsolutePath() + "\"");
            log("   📝 Base APK written");

            // Tulis splits
            for (int i = 0; i < splits.size(); i++) {
                File split = splits.get(i);
                String name = split.getName().replace(".apk", "");
                rootShell.execute("pm install-write " + sessionId + " " + name + " \"" + split.getAbsolutePath() + "\"");
                log("   📝 Split " + (i+1) + "/" + splits.size() + ": " + split.getName());
            }

            // Commit
            String commitResult = rootShell.execute("pm install-commit " + sessionId);
            log("   ✅ Commit: " + (commitResult.isEmpty() ? "Success" : commitResult));
        }

        private int parseSessionId(String output) {
            // Format: "Success: created install session [1234567890]"
            Matcher m = Pattern.compile("\\[\\s*(\\d+)\\s*\\]").matcher(output);
            if (m.find()) return Integer.parseInt(m.group(1));

            // Fallback: angka panjang (5+ digit)
            m = Pattern.compile("(\\d{5,})").matcher(output);
            if (m.find()) return Integer.parseInt(m.group(1));

            return -1;
        }

        private void moveObbFiles(File extractDir) throws Exception {
            List<File> obbFiles = new ArrayList<>();
            findFiles(extractDir, ".obb", obbFiles);

            if (obbFiles.isEmpty()) {
                log("   ℹ️ Tidak ada OBB");
                return;
            }

            for (File obb : obbFiles) {
                String name = obb.getName();
                // Format: main.<version>.<package>.obb
                String[] parts = name.split("\\.");
                if (parts.length >= 3) {
                    StringBuilder pkg = new StringBuilder();
                    for (int i = 2; i < parts.length - 1; i++) {
                        if (i > 2) pkg.append(".");
                        pkg.append(parts[i]);
                    }
                    String packageName = pkg.toString();

                    File targetDir = new File("/sdcard/Android/obb/" + packageName);
                    rootShell.execute("mkdir -p \"" + targetDir.getAbsolutePath() + "\"");
                    rootShell.execute("chmod 755 \"" + targetDir.getAbsolutePath() + "\"");

                    File targetFile = new File(targetDir, name);
                    rootShell.execute("cp -f \"" + obb.getAbsolutePath() + "\" \"" + targetFile.getAbsolutePath() + "\"");
                    rootShell.execute("chmod 644 \"" + targetFile.getAbsolutePath() + "\"");
                    log("   ✅ OBB → " + packageName + "/" + name);
                } else {
                    log("   ⚠️ Format OBB tidak dikenal: " + name);
                }
            }
        }

        private void changeSystemDate() throws Exception {
            // Matikan auto-time
            try {
                rootShell.execute("settings put global auto_time 0");
                rootShell.execute("settings put global auto_time_zone 0");
                log("   ⏰ Auto-time dimatikan");
            } catch (Exception e) {
                log("   ⚠️ Gagal matikan auto-time: " + e.getMessage());
            }

            // Hitung kemarin di Java, lalu gunakan format POSIX Android: MMddHHmmYYYY.ss
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -1);
            String dateStr = new SimpleDateFormat("MMddHHmmyyyy.ss", Locale.US).format(cal.getTime());

            try {
                String result = rootShell.execute("date " + dateStr);
                log("   ✅ Tanggal diubah: " + dateStr);
                if (!result.isEmpty()) log("   → Output: " + result);
            } catch (Exception e) {
                log("   ❌ Gagal ubah tanggal: " + e.getMessage());
            }

            // Verifikasi
            try {
                String now = rootShell.execute("date");
                log("   🔍 Sekarang: " + now);
            } catch (Exception e) {
                log("   ⚠️ Gagal verifikasi tanggal");
            }
        }

        private void downloadFile(String urlStr, File output) throws IOException {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            int response = conn.getResponseCode();
            if (response != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + response);
            }

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(output)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            }
        }

        private void unzip(File zipFile, File targetDir) throws IOException {
            try (ZipFile zip = new ZipFile(zipFile)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    File outFile = new File(targetDir, entry.getName());
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        try (InputStream in = zip.getInputStream(entry);
                             FileOutputStream out = new FileOutputStream(outFile)) {
                            byte[] buf = new byte[8192];
                            int read;
                            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
                        }
                    }
                }
            }
        }

        private List<File> findXapkFiles(File dir) {
            List<File> result = new ArrayList<>();
            findFiles(dir, ".xapk", result);
            return result;
        }

        private void findFiles(File dir, String ext, List<File> result) {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.isDirectory()) findFiles(f, ext, result);
                else if (f.getName().toLowerCase().endsWith(ext)) result.add(f);
            }
        }

        private void deleteRecursive(File file) {
            if (file == null) return;
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) for (File c : children) deleteRecursive(c);
            }
            file.delete();
        }

        @Override
        protected void onProgressUpdate(String... values) {
            log(values[0]);
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
            log(success ? "\n✅ PROSES BERHASIL" : "\n❌ PROSES GAGAL");
        }
    }
}
