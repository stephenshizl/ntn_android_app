package com.youyou.ntn_bt;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.jieli.jl_bt_ota.constant.StateCode;
import com.jieli.jl_bt_ota.impl.BluetoothOTAManager;
import com.jieli.jl_bt_ota.interfaces.BtEventCallback;
import com.jieli.jl_bt_ota.interfaces.IUpgradeCallback;
import com.jieli.jl_bt_ota.model.BluetoothOTAConfigure;
import com.jieli.jl_bt_ota.model.base.BaseError;
import com.jieli.jl_bt_ota.model.response.TargetInfoResponse;
import java.util.Collections;
import java.util.UUID;

/** Dedicated BLE RCSP channel. No OTA bytes are passed through MCU CTRL2/SPP. */
@SuppressLint("MissingPermission")
final class BleOtaController {
    static final UUID SERVICE = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb");
    private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    interface Listener {
        void device(BluetoothDevice device);
        void status(int message, String detail);
        void ready(TargetInfoResponse info);
        void progress(int type, float progress);
        void finished(boolean success, String detail);
    }
    private final Context context;
    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OtaWriteQueue writes = new OtaWriteQueue(64 * 1024);
    private final Manager manager;
    private volatile BluetoothGatt gatt;
    private BluetoothGattCharacteristic writer;
    private volatile BluetoothDevice connected;
    private volatile boolean ready, updating, closed;
    private boolean scanning;
    private int mtu = 23, generation;
    private volatile int phase;
    private final Runnable writeTimeout = () -> transportFailure(R.string.ota_write_failed);
    private final Runnable scanTimeout = this::stopScan;

    BleOtaController(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        adapter = context.getSystemService(BluetoothManager.class).getAdapter();
        manager = new Manager(this.context);
        manager.configure(BluetoothOTAConfigure.createDefault()
                .setPriority(BluetoothOTAConfigure.PREFER_BLE).setUseAuthDevice(true)
                .setUseReconnect(false).setNeedChangeMtu(false).setMtu(23).setTimeoutMs(5000));
        manager.registerBluetoothCallback(new BtEventCallback() {
            @Override public void onConnection(BluetoothDevice device, int status) {
                main.post(() -> { if (status == StateCode.CONNECTION_OK) protocolReady(device); });
            }
            @Override public void onMandatoryUpgrade(BluetoothDevice device) {
                // Single-bank loader sessions use this callback instead of onConnection.
                main.post(() -> {
                    android.util.Log.i("NtnOta", "Authenticated mandatory recovery loader");
                    protocolReady(device);
                });
            }
            @Override public void onError(BaseError error) {
                main.post(() -> { if (!closed && !updating) listener.status(R.string.ota_protocol_error, error.toString()); });
            }
        });
    }

    private void protocolReady(BluetoothDevice device) {
        if (closed || device == null || connected == null
                || !connected.getAddress().equals(device.getAddress())) return;
        TargetInfoResponse info = manager.getDeviceInfo();
        if (info != null) { ready = true; listener.ready(info); }
    }

    boolean isReady() { return ready && gatt != null; }
    boolean isUpdating() { return updating; }
    BluetoothDevice device() { return connected; }

    void scan() {
        if (closed || updating) return;
        stopScan();
        if (adapter == null || !adapter.isEnabled() || adapter.getBluetoothLeScanner() == null) {
            listener.status(R.string.ota_enable_bluetooth, ""); return;
        }
        scanning = true;
        // Advertisements may omit the RCSP service UUID. Validate GATT after explicit selection.
        adapter.getBluetoothLeScanner().startScan(Collections.emptyList(),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        listener.status(R.string.ota_scanning, ""); main.postDelayed(scanTimeout, 15000);
    }
    void stopScan() {
        main.removeCallbacks(scanTimeout);
        if (scanning && adapter != null && adapter.getBluetoothLeScanner() != null)
            adapter.getBluetoothLeScanner().stopScan(scanCallback);
        scanning = false;
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            main.post(() -> { if (!closed && scanning) listener.device(result.getDevice()); });
        }
        @Override public void onScanFailed(int error) {
            main.post(() -> { scanning = false; listener.status(R.string.ota_scan_failed, String.valueOf(error)); });
        }
    };

    void connect(BluetoothDevice device) { if (!updating) connectInternal(device); }
    private void connectInternal(BluetoothDevice device) {
        if (closed || device == null) return;
        stopScan(); closeGatt(true);
        connected = device; ready = false; phase = 1; mtu = 23;
        int token = ++generation;
        listener.status(R.string.ota_connecting, device.getAddress());
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        main.postDelayed(() -> { if (!closed && token == generation && phase < 5) transportFailure(R.string.ota_connect_timeout); }, 20000);
    }
    void disconnect() { if (!updating) { closeGatt(true); listener.status(R.string.ota_disconnected, ""); } }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt current, int status, int state) {
            main.post(() -> {
                if (current != gatt || closed) return;
                if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    phase = 2;
                    if (!current.discoverServices()) transportFailure(R.string.ota_connect_timeout);
                } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS)
                    transportFailure(R.string.ota_disconnected);
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt current, int status) {
            main.post(() -> {
                if (current != gatt || closed) return;
                BluetoothGattService service = current.getService(SERVICE);
                writer = service == null ? null : service.getCharacteristic(WRITE);
                BluetoothGattCharacteristic notify = service == null ? null : service.getCharacteristic(NOTIFY);
                if (status != BluetoothGatt.GATT_SUCCESS || writer == null || notify == null
                        || (writer.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0
                        || (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                    transportFailure(R.string.ota_unsupported); return;
                }
                phase = 3;
                if (!current.requestMtu(247)) subscribe(current);
                else main.postDelayed(() -> { if (current == gatt && phase == 3) subscribe(current); }, 3000);
            });
        }
        @Override public void onMtuChanged(BluetoothGatt current, int value, int status) {
            main.post(() -> {
                if (current != gatt || closed) return;
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = Math.max(23, Math.min(value, 517));
                if (phase == 3) subscribe(current);
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt current, BluetoothGattDescriptor descriptor, int status) {
            main.post(() -> {
                if (current != gatt || closed || phase != 4 || !CCC.equals(descriptor.getUuid())) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { transportFailure(R.string.ota_notify_failed); return; }
                phase = 5;
                manager.onMtuChanged(current, mtu, BluetoothGatt.GATT_SUCCESS);
                manager.onBtDeviceConnection(current.getDevice(), StateCode.CONNECTION_OK);
                listener.status(R.string.ota_authenticating, current.getDevice().getAddress());
                int token = generation;
                main.postDelayed(() -> { if (!closed && token == generation && !ready && !updating)
                    transportFailure(R.string.ota_protocol_error); }, 20000);
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic characteristic) {
            received(current, characteristic, characteristic.getValue());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic characteristic, byte[] value) {
            received(current, characteristic, value);
        }
        @Override public void onCharacteristicWrite(BluetoothGatt current, BluetoothGattCharacteristic characteristic, int status) {
            main.post(() -> {
                if (current != gatt || closed || !WRITE.equals(characteristic.getUuid())) return;
                main.removeCallbacks(writeTimeout);
                if (!writes.complete(status == BluetoothGatt.GATT_SUCCESS)) { transportFailure(R.string.ota_write_failed); return; }
                drain();
            });
        }
    };
    private void received(BluetoothGatt current, BluetoothGattCharacteristic characteristic, byte[] value) {
        if (!NOTIFY.equals(characteristic.getUuid()) || value == null) return;
        byte[] copy = value.clone();
        main.post(() -> { if (!closed && current == gatt && phase == 5) manager.onReceiveDeviceData(current.getDevice(), copy); });
    }
    private void subscribe(BluetoothGatt current) {
        if (current != gatt || phase != 3) return;
        phase = 4;
        BluetoothGattCharacteristic notify = current.getService(SERVICE).getCharacteristic(NOTIFY);
        BluetoothGattDescriptor ccc = notify.getDescriptor(CCC);
        if (ccc == null || !current.setCharacteristicNotification(notify, true)) { transportFailure(R.string.ota_notify_failed); return; }
        boolean accepted;
        if (Build.VERSION.SDK_INT >= 33) accepted = current.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
        else { ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); accepted = current.writeDescriptor(ccc); }
        if (!accepted) transportFailure(R.string.ota_notify_failed);
    }
    private void drain() {
        if (closed || gatt == null || writer == null || phase != 5) return;
        byte[] data = writes.next(mtu - 3);
        if (data == null) return;
        int type = (writer.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        boolean accepted;
        if (Build.VERSION.SDK_INT >= 33) accepted = gatt.writeCharacteristic(writer, data, type) == BluetoothStatusCodes.SUCCESS;
        else { writer.setWriteType(type); writer.setValue(data); accepted = gatt.writeCharacteristic(writer); }
        if (!accepted) { writes.complete(false); transportFailure(R.string.ota_write_failed); }
        else { main.removeCallbacks(writeTimeout); main.postDelayed(writeTimeout, 8000); }
    }

    void start(BluetoothFirmwareFile file) {
        if (!isReady() || updating) throw new IllegalStateException("OTA device is not ready");
        manager.getBluetoothOption().setFirmwareFilePath(file.file.getAbsolutePath());
        updating = true;
        manager.startOTA(new IUpgradeCallback() {
            @Override public void onStartOTA() { main.post(() -> listener.status(R.string.ota_running, "")); }
            @Override public void onNeedReconnect(String address, boolean isNewReconnectWay) {
                main.post(() -> {
                    if (!BluetoothAdapter.checkBluetoothAddress(address)) { finish(false, "Invalid reconnect address"); return; }
                    listener.status(R.string.ota_reconnecting, address);
                    // The vendor SDK owns legacy/new-loader discovery and calls our
                    // connectBluetoothDevice override with the verified reconnect target.
                });
            }
            @Override public void onProgress(int type, float value) { main.post(() -> listener.progress(type, value)); }
            @Override public void onStopOTA() { main.post(() -> finish(true, "")); }
            @Override public void onCancelOTA() { main.post(() -> finish(false, "OTA canceled")); }
            @Override public void onError(BaseError error) { main.post(() -> finish(false, error.toString())); }
        });
    }
    private void finish(boolean success, String detail) {
        if (closed || !updating) return;
        updating = false; ready = false;
        closeGatt(true); listener.finished(success, detail);
    }
    private void transportFailure(int reason) {
        if (closed) return;
        closeGatt(true); listener.status(reason, "");
    }
    private void closeGatt(boolean notifyManager) {
        BluetoothGatt previous;
        BluetoothDevice previousDevice;
        synchronized (writes) {
            previous = gatt; previousDevice = connected;
            gatt = null; connected = null; writer = null; ready = false; phase = 0; generation++;
            writes.clear();
        }
        main.removeCallbacks(writeTimeout);
        if (previous != null) { previous.disconnect(); previous.close(); }
        if (notifyManager && previousDevice != null) manager.onBtDeviceConnection(previousDevice, StateCode.CONNECTION_DISCONNECT);
    }
    void close() {
        if (closed) return;
        stopScan(); closed = true; main.removeCallbacksAndMessages(null); closeGatt(false); manager.release();
    }
    private final class Manager extends BluetoothOTAManager {
        Manager(Context context) { super(context); }
        @Override public BluetoothDevice getConnectedDevice() { return connected; }
        @Override public BluetoothGatt getConnectedBluetoothGatt() { return gatt; }
        @Override public void connectBluetoothDevice(BluetoothDevice device) { main.post(() -> connectInternal(device)); }
        @Override public void disconnectBluetoothDevice(BluetoothDevice device) { main.post(() -> closeGatt(true)); }
        @Override public boolean sendDataToDevice(BluetoothDevice device, byte[] data) {
            synchronized (writes) {
                BluetoothDevice current = connected;
                if (closed || phase != 5 || device == null || current == null
                        || !current.getAddress().equals(device.getAddress()) || !writes.offer(data)) return false;
                int token = generation;
                main.post(() -> { if (token == generation) drain(); });
                return true;
            }
        }
    }
}
