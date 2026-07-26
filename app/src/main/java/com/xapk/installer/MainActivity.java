package com.xapk.installer;

import android.app.ProgressDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends AppCompatActivity {

    private static final String DEFAULT_URL = 
        "https://github.com/Namikazeudin12/Tokpedajah/releases/download/Baru/Tokped.zip";

    private Button btnInstall;
    private TextView tvLog;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnInstall = findViewById(R.id.btn_install);
        tvLog = findViewById(R.id.tv_log);
        scrollView = findViewById(R.id.scrollView);

        btnInstall.setOnClickListener(v -> new InstallTask().execute(DEFAULT_URL));
    }

    private void log(String msg) {
        runOnUiThread(() -> {
            tvLog.append(msg + "\n");
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    private class InstallTask extends AsyncTask<String, String, Boolean> {
        private ProgressDialog dialog;

        @Override
        protected void onPreExecute() {
            dialog = new ProgressDialog(MainActivity.this);
            dialog.setMessage("Memproses...");
            dialog.setCancelable(false);
            dialog.show();
        }

        @Override
        protected Boolean doInBackground(String... params) {
            String urlStr = params[0];
            try {
                File cacheDir = new File(getExternalCacheDir(), "xapk_install");
                cacheDir.mkdirs();
                String fileName = urlStr.substring(urlStr.lastIndexOf('/') + 1);
                if (fileName.contains("?")) fileName = fileName.substring(0, fileName.indexOf("?"));
                File downloadedFile = new File(cacheDir, fileName);

                publishProgress("Mengunduh " + fileName + "...");
                downloadFile(urlStr, downloadedFile);

                File extractRoot = new File(cacheDir, "extracted");
                extractRoot.mkdirs();
                publishProgress("Mengekstrak ZIP...");
                unzip(downloadedFile, extractRoot);

                List<File> xapkFiles = new ArrayList<>();
                findXAPKFiles(extractRoot, xapkFiles);
                if (xapkFiles.isEmpty()) {
                    publishProgress("❌ Tidak ditemukan file XAPK.");
                    return false;
                }

                for (File xapk : xapkFiles) {
                    publishProgress("Memproses " + xapk.getName());
                    File xapkOut = new File(extractRoot, xapk.getName() + "_out");
                    xapkOut.mkdirs();
                    unzip(xapk, xapkOut);
                    processXAPKDirectory(xapkOut);
                }

                publishProgress("Mengubah tanggal...");
                runRootCommand(
                    "settings put global auto_time 0\n" +
                    "settings put global auto_time_zone 0\n" +
                    "date $(date -d '1 day ago' '+%m%d%H%M%Y.%S')\n"
                );

                publishProgress("✅ Semua selesai. Tanggal mundur 1 hari.");
                deleteRecursive(cacheDir);
                return true;
            } catch (Exception e) {
                publishProgress("❌ Error: " + e.getMessage());
                return false;
            }
        }

        @Override
        protected void onProgressUpdate(String... values) {
            log(values[0]);
        }

        @Override
        protected void onPostExecute(Boolean success) {
            dialog.dismiss();
            if (!success) log("Gagal.");
        }

        private void downloadFile(String urlStr, File dest) throws IOException {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.connect();
            int length = conn.getContentLength();
            InputStream is = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int read;
            long total = 0;
            while ((read = is.read(buf)) != -1) {
                fos.write(buf, 0, read);
                total += read;
                if (length > 0) publishProgress("Download: " + (total * 100 / length) + "%");
            }
            fos.close();
            is.close();
            conn.disconnect();
        }

        private void unzip(File zipFile, File destDir) throws IOException {
            ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                File entryFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    entryFile.mkdirs();
                    continue;
                }
                entryFile.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(entryFile);
                int len;
                while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
                zis.closeEntry();
            }
            zis.close();
        }

        private void findXAPKFiles(File dir, List<File> result) {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.isDirectory()) findXAPKFiles(f, result);
                else if (f.getName().toLowerCase().endsWith(".xapk")) result.add(f);
            }
        }

        private void processXAPKDirectory(File extractedDir) throws IOException, InterruptedException {
            List<File> apks = new ArrayList<>();
            findAPKFiles(extractedDir, apks);
            if (apks.isEmpty()) {
                publishProgress("   Tidak ada APK.");
                return;
            }
            if (apks.size() == 1) {
                runRootCommand("pm install -r \"" + apks.get(0).getAbsolutePath() + "\"\n");
            } else {
                long totalSize = 0;
                for (File a : apks) totalSize += a.length();
                String sessionCmd = "pm install-create -r -t -S " + totalSize + " | grep -o '[0-9]*'\n";
                Process p = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(p.getOutputStream());
                os.writeBytes(sessionCmd);
                os.writeBytes("exit\n");
                os.flush();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                String sessionId = null;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && line.matches("\\d+")) {
                        sessionId = line;
                        break;
                    }
                }
                p.waitFor();
                if (sessionId == null) throw new IOException("Gagal membuat session install.");
                for (File a : apks) {
                    String writeCmd = "cat \"" + a.getAbsolutePath() + "\" | pm install-write -S " +
                        a.length() + " " + sessionId + " \"" + a.getName() + "\" -\n";
                    runRootCommand(writeCmd);
                }
                runRootCommand("pm install-commit " + sessionId + "\n");
            }

            File obbTarget = new File(Environment.getExternalStorageDirectory(), "Android/obb");
            File[] subDirs = extractedDir.listFiles(File::isDirectory);
            if (subDirs != null) {
                for (File sub : subDirs) {
                    File obbDir = new File(sub, "obb");
                    if (obbDir.exists() && obbDir.isDirectory()) {
                        String pkg = sub.getName();
                        File destObb = new File(obbTarget, pkg);
                        copyDirectory(obbDir, destObb);
                        publishProgress("   OBB disalin untuk " + pkg);
                    }
                }
            }
            File androidObb = new File(extractedDir, "Android/obb");
            if (androidObb.exists() && androidObb.isDirectory()) {
                File[] pkgs = androidObb.listFiles(File::isDirectory);
                if (pkgs != null) {
                    for (File pkgDir : pkgs) {
                        File dest = new File(obbTarget, pkgDir.getName());
                        copyDirectory(pkgDir, dest);
                        publishProgress("   OBB disalin untuk " + pkgDir.getName());
                    }
                }
            }
        }

        private void findAPKFiles(File dir, List<File> result) {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.isDirectory()) findAPKFiles(f, result);
                else if (f.getName().toLowerCase().endsWith(".apk")) result.add(f);
            }
        }

        private void runRootCommand(String cmd) throws IOException, InterruptedException {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(cmd + "exit\n");
            os.flush();
            p.waitFor();
        }

        private void copyDirectory(File source, File dest) throws IOException {
            if (source.isDirectory()) {
                if (!dest.exists()) dest.mkdirs();
                String[] children = source.list();
                if (children != null) {
                    for (String child : children) {
                        copyDirectory(new File(source, child), new File(dest, child));
                    }
                }
            } else {
                FileInputStream fis = new FileInputStream(source);
                FileOutputStream fos = new FileOutputStream(dest);
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
                fis.close();
                fos.close();
            }
        }

        private void deleteRecursive(File fileOrDirectory) {
            if (fileOrDirectory.isDirectory()) {
                File[] children = fileOrDirectory.listFiles();
                if (children != null) for (File child : children) deleteRecursive(child);
            }
            fileOrDirectory.delete();
        }
    }
                }
