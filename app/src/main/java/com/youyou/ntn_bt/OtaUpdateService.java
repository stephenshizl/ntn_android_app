package com.youyou.ntn_bt;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.jieli.jl_bt_ota.model.response.TargetInfoResponse;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps an explicitly started Bluetooth upgrade alive while the UI is backgrounded. */
@SuppressLint("MissingPermission")
public final class OtaUpdateService extends Service implements BleOtaController.Listener {
    private static final String CHANNEL = "bluetooth_ota";
    private static final int NOTIFICATION = 6323;
    private static final String START = "com.youyou.ntn_bt.START_BLUETOOTH_OTA";
    public final class LocalBinder extends Binder { OtaUpdateService service() { return OtaUpdateService.this; } }
    interface Observer { void changed(); }
    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final Set<Observer> observers = new HashSet<>();
    final List<BluetoothDevice> devices = new ArrayList<>();
    private BleOtaController controller;
    private PowerManager.WakeLock wakeLock;
    BluetoothFirmwareFile firmware;
    String message, deviceInfo = "", selectedAddress = "";
    boolean active, importing, ready, recoveryLoader;
    String bluetoothVersion = "";
    private void clearDeviceInfo() {
        bluetoothVersion = ""; recoveryLoader = false; deviceInfo = "";
    }
    int progress;
    private final Runnable deadline = () -> {
        if (!active) return;
        if (controller != null) { controller.close(); controller = null; }
        finished(false, getString(R.string.ota_session_timeout));
    };
    @Override public void onCreate() {
        super.onCreate(); message = getString(R.string.ota_idle);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.ota_title), NotificationManager.IMPORTANCE_LOW));
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    void observe(Observer observer) { observers.add(observer); observer.changed(); }
    void unobserve(Observer observer) { observers.remove(observer); }
    private void changed() { for (Observer observer : new ArrayList<>(observers)) observer.changed(); }
    private BleOtaController controller() {
        if (controller == null) controller = new BleOtaController(this, this);
        return controller;
    }
    void scan() {
        if (active) return;
        devices.clear(); changed();
        try { controller().scan(); } catch (RuntimeException failure) { status(R.string.ota_scan_failed, failure.getMessage()); }
    }
    void connect(BluetoothDevice device) {
        if (active || device == null) return;
        selectedAddress = device.getAddress(); clearDeviceInfo(); ready = false; changed();
        try { controller().connect(device); } catch (RuntimeException failure) { status(R.string.ota_protocol_error, failure.getMessage()); }
    }
    void disconnect() {
        if (active) return;
        ready = false; clearDeviceInfo();
        if (controller != null) controller.disconnect();
        changed();
    }
    void importFirmware(Uri uri) {
        if (active || importing || uri == null) return;
        firmware = null; importing = true; status(R.string.ota_importing, "");
        files.execute(() -> {
            File temporary = null;
            try {
                String name = null;
                try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                }
                BluetoothFirmwareFile.checkName(name);
                File directory = new File(getFilesDir(), "bluetooth_ota");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private OTA directory");
                temporary = File.createTempFile("import-", ".ufw", directory);
                long size;
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(temporary)) {
                    if (in == null) throw new IOException("Cannot open selected file");
                    size = BluetoothFirmwareFile.copyBounded(in, out);
                }
                String hash = BluetoothFirmwareFile.digest(temporary);
                File selected = new File(directory, "bluetooth-update.ufw");
                Files.move(temporary.toPath(), selected.toPath(), StandardCopyOption.REPLACE_EXISTING);
                String displayName = name;
                main.post(() -> {
                    firmware = new BluetoothFirmwareFile(selected, displayName, size, hash);
                    importing = false; status(R.string.ota_file_selected, "");
                });
            } catch (Exception failure) {
                if (temporary != null) temporary.delete();
                main.post(() -> { importing = false; status(R.string.ota_file_invalid, failure.getMessage()); });
            }
        });
    }
    boolean canStart() { return ready && controller != null && controller.isReady() && firmware != null && !active && !importing; }
    void requestStart() {
        if (!canStart()) return;
        try { ContextCompat.startForegroundService(this, new Intent(this, OtaUpdateService.class).setAction(START)); }
        catch (RuntimeException failure) { status(R.string.ota_failed, failure.getMessage()); }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !START.equals(intent.getAction()) || !canStart()) {
            if (!active) { startForeground(NOTIFICATION, notification()); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
            return START_NOT_STICKY;
        }
        active = true; progress = 0; clearDeviceInfo();
        message = getString(R.string.ota_checking_file);
        startForeground(NOTIFICATION, notification());
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NTN:BluetoothOTA");
        wakeLock.acquire(30 * 60 * 1000L);
        main.postDelayed(deadline, 30 * 60 * 1000L);
        changed();
        BluetoothFirmwareFile selected = firmware;
        files.execute(() -> {
            try {
                if (!selected.unchanged()) throw new IOException("Selected firmware changed after import");
                main.post(() -> {
                    try { if (controller == null || !controller.isReady()) throw new IllegalStateException("OTA link lost"); controller.start(selected); }
                    catch (RuntimeException failure) { finished(false, failure.getMessage()); }
                });
            } catch (Exception failure) { main.post(() -> finished(false, failure.getMessage())); }
        });
        return START_NOT_STICKY;
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, BluetoothOtaActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(getString(R.string.ota_title)).setContentText(message).setContentIntent(open)
                .setOnlyAlertOnce(true).setOngoing(active).setProgress(100, progress, progress == 0).build();
    }
    @Override public void device(BluetoothDevice device) {
        for (BluetoothDevice existing : devices) if (existing.getAddress().equals(device.getAddress())) return;
        if (devices.size() < 128) { devices.add(device); changed(); }
    }
    @Override public void status(int resource, String detail) {
        if (controller == null || !controller.isReady()) ready = false;
        if (!ready || active) clearDeviceInfo();
        message = getString(resource) + (detail == null || detail.isEmpty() ? "" : "\n" + detail);
        changed();
        if (active) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
    }
    @Override public void ready(TargetInfoResponse info) {
        ready = true;
        clearDeviceInfo();
        recoveryLoader = info.isMandatoryUpgrade();
        if (recoveryLoader) {
            BluetoothDevice loader = controller == null ? null : controller.device();
            deviceInfo = getString(R.string.ota_loader_device_info,
                    loader == null ? selectedAddress : loader.getAddress());
            status(R.string.ota_loader_ready, "");
        } else {
            String version = info.getVersionName();
            bluetoothVersion = version == null || version.trim().isEmpty()
                    ? getString(R.string.bt_firmware_unknown) : version.trim();
            deviceInfo = getString(R.string.ota_device_info, selectedAddress, bluetoothVersion, info.getVid(), info.getPid());
            status(R.string.ota_ready, "");
        }
    }
    @Override public void progress(int type, float value) {
        progress = Math.max(0, Math.min(100, Math.round(value)));
        message = getString(type == 0 ? R.string.ota_preparing_progress : R.string.ota_transfer_progress, progress);
        changed();
        if (active) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
    }
    @Override public void finished(boolean success, String detail) {
        active = false; ready = false; clearDeviceInfo();
        if (controller != null) { controller.close(); controller = null; }
        main.removeCallbacks(deadline);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        status(success ? R.string.ota_success : R.string.ota_failed, detail);
    }
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (controller != null) controller.close();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        files.shutdownNow(); observers.clear();
        super.onDestroy();
    }
}
