package com.enship.travel;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // UUID SPP standard — le HC-06 utilise toujours celui-ci
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    private WebView webView;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket btSocket;
    private InputStream  btIn;
    private OutputStream btOut;
    private volatile boolean btConnected = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private static final int REQ_PERMISSIONS = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        setupWebView();
        requestBTPermissions();

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    // ── WebView setup ──────────────────────────────────────────────
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        // Injecter l'interface Java → JS
        webView.addJavascriptInterface(new AndroidBTInterface(), "AndroidBT");

        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    // ── Permissions Bluetooth ──────────────────────────────────────
    private void requestBTPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            String[] perms = {
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            };
            boolean needed = false;
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    needed = true; break;
                }
            }
            if (needed) ActivityCompat.requestPermissions(this, perms, REQ_PERMISSIONS);
        } else {
            // Android 6–11
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                }, REQ_PERMISSIONS);
            }
        }
    }

    // ── Interface JavaScript ↔ Java ────────────────────────────────
    public class AndroidBTInterface {

        /** Retourne la liste des appareils Bluetooth déjà jumelés (JSON) */
        @JavascriptInterface
        public String getPairedDevices() {
            JSONArray arr = new JSONArray();
            try {
                if (bluetoothAdapter == null) return arr.toString();
                Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
                for (BluetoothDevice d : paired) {
                    JSONObject obj = new JSONObject();
                    obj.put("name",    d.getName()    != null ? d.getName() : "Unknown");
                    obj.put("address", d.getAddress() != null ? d.getAddress() : "");
                    arr.put(obj);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return arr.toString();
        }

        /** Connecte au HC-06 via son adresse MAC (appel bloquant dans un thread) */
        @JavascriptInterface
        public void connect(final String address) {
            executor.execute(() -> {
                try {
                    if (btSocket != null && btSocket.isConnected()) {
                        btSocket.close();
                    }
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                    btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    bluetoothAdapter.cancelDiscovery();
                    btSocket.connect();
                    btIn  = btSocket.getInputStream();
                    btOut = btSocket.getOutputStream();
                    btConnected = true;

                    // Lancer la boucle de lecture
                    startReading();

                    mainHandler.post(() ->
                        Toast.makeText(MainActivity.this, "HC-06 connecté !", Toast.LENGTH_SHORT).show()
                    );
                } catch (IOException e) {
                    btConnected = false;
                    final String msg = e.getMessage();
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "Erreur BT : " + msg, Toast.LENGTH_LONG).show();
                        webView.evaluateJavascript(
                            "window.onAndroidBTDisconnected('Erreur connexion : " + escapeJs(msg) + "')", null);
                    });
                }
            });
        }

        /** Envoie une commande texte à l'Arduino */
        @JavascriptInterface
        public void send(final String data) {
            if (!btConnected || btOut == null) return;
            executor.execute(() -> {
                try {
                    btOut.write(data.getBytes("UTF-8"));
                    btOut.flush();
                } catch (IOException e) {
                    handleDisconnect(e.getMessage());
                }
            });
        }

        /** Ferme la connexion */
        @JavascriptInterface
        public void disconnect() {
            closeConnection("Déconnecté par l'utilisateur");
        }
    }

    // ── Boucle de lecture Bluetooth ────────────────────────────────
    private void startReading() {
        executor.execute(() -> {
            StringBuilder buffer = new StringBuilder();
            byte[] buf = new byte[1024];
            while (btConnected) {
                try {
                    int n = btIn.read(buf);
                    if (n <= 0) continue;
                    String chunk = new String(buf, 0, n, "UTF-8");
                    buffer.append(chunk);

                    // Extraire les lignes complètes
                    int idx;
                    while ((idx = buffer.indexOf("\n")) >= 0) {
                        String line = buffer.substring(0, idx).trim();
                        buffer.delete(0, idx + 1);
                        if (!line.isEmpty()) {
                            final String finalLine = line;
                            mainHandler.post(() ->
                                webView.evaluateJavascript(
                                    "window.onAndroidBTData(" + jsString(finalLine) + ")", null)
                            );
                        }
                    }
                } catch (IOException e) {
                    if (btConnected) handleDisconnect(e.getMessage());
                    break;
                }
            }
        });
    }

    private void handleDisconnect(String reason) {
        btConnected = false;
        final String r = reason != null ? reason : "connexion perdue";
        mainHandler.post(() -> {
            webView.evaluateJavascript(
                "window.onAndroidBTDisconnected('" + escapeJs(r) + "')", null);
        });
    }

    private void closeConnection(String reason) {
        btConnected = false;
        try { if (btSocket != null) btSocket.close(); } catch (IOException ignored) {}
        btSocket = null; btIn = null; btOut = null;
        handleDisconnect(reason);
    }

    // ── Helpers ──────────────────────────────────────────────────
    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
    }

    private static String jsString(String s) {
        return "'" + escapeJs(s) + "'";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeConnection("App fermée");
        executor.shutdown();
    }
}
