package com.youyou.ntn_bt;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Classic Bluetooth SPP transport with a streaming CTRL2 decoder. */
public final class BluetoothSppClient {
    public interface Callback {
        void onState(String state);
        void onFrame(Ctrl2Codec.Frame frame);
        void onError(String message);
    }

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter adapter;
    private final Callback callback;
    private final Object socketLock = new Object();
    private final ExecutorService connectExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile BluetoothSocket socket;
    private volatile OutputStream output;

    public BluetoothSppClient(BluetoothAdapter adapter, Callback callback) {
        this.adapter = adapter;
        this.callback = callback;
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device) {
        final int token = generation.incrementAndGet();
        closeSocket();
        callback.onState("正在连接 " + safeName(device) + "…");
        connectExecutor.execute(() -> runConnection(device, token));
    }

    @SuppressLint("MissingPermission")
    private void runConnection(BluetoothDevice device, int token) {
        BluetoothSocket newSocket = null;
        try {
            adapter.cancelDiscovery();
            newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            newSocket.connect();
            synchronized (socketLock) {
                if (token != generation.get()) {
                    newSocket.close();
                    return;
                }
                socket = newSocket;
                output = newSocket.getOutputStream();
            }
            callback.onState("已连接 " + safeName(device));
            readLoop(newSocket, token);
        } catch (IOException | SecurityException e) {
            if (token == generation.get()) {
                callback.onError("连接失败：" + readableMessage(e));
            }
        } finally {
            if (token == generation.get()) {
                closeSocket();
                callback.onState("未连接");
            } else if (newSocket != null) {
                try {
                    newSocket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void readLoop(BluetoothSocket activeSocket, int token) throws IOException {
        Ctrl2Codec decoder = new Ctrl2Codec();
        InputStream input = activeSocket.getInputStream();
        byte[] buffer = new byte[512];
        while (token == generation.get()) {
            int count = input.read(buffer);
            if (count < 0) {
                return;
            }
            List<Ctrl2Codec.Frame> frames = decoder.feed(buffer, count);
            for (Ctrl2Codec.Frame frame : frames) {
                callback.onFrame(frame);
            }
        }
    }

    public boolean isConnected() {
        BluetoothSocket current = socket;
        return current != null && current.isConnected() && output != null;
    }

    public void send(byte[] frame) {
        final int token = generation.get();
        writeExecutor.execute(() -> {
            synchronized (socketLock) {
                if (token != generation.get() || output == null) {
                    callback.onError("尚未连接蓝牙终端");
                    return;
                }
                try {
                    output.write(frame);
                    output.flush();
                } catch (IOException e) {
                    callback.onError("发送失败：" + readableMessage(e));
                    disconnect();
                }
            }
        });
    }

    public void disconnect() {
        generation.incrementAndGet();
        closeSocket();
        callback.onState("未连接");
    }

    public void close() {
        disconnect();
        connectExecutor.shutdownNow();
        writeExecutor.shutdownNow();
    }

    private void closeSocket() {
        synchronized (socketLock) {
            output = null;
            BluetoothSocket current = socket;
            socket = null;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private static String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.isEmpty() ? device.getAddress() : name;
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    private static String readableMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
