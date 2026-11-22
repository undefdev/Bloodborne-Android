package com.ducvd89.bloodborne;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Objects;

final class KishiHaptics {
    private static final String TAG = "BloodborneKishi";
    private static final int FRAME_MS = 30;
    // Pairs of amplitude and frequency, one pair per frame.
    private static final int[] STONE = {82, 35};
    private static final int[] WOOD = {65, 10, 24, 14};
    private static final int[] GRAVEL = {60, 41, 32, 27};
    private static final int[] SOFT = {46, 11};
    private static final int[] WATER = {57, 28, 0, 0, 37, 41};
    private static final int[] METAL = {70, 45, 28, 37};
    private final Context context;
    private final UsbManager usb;
    private final Runnable routeChanged;
    private final String permissionAction;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("Kishi haptics");
    private Handler worker;

    // Main-thread device and routing state.
    private UsbDevice attached, device;
    private boolean requested, attempted, ready, foreground, enabled = true;
    private int wantedLarge, wantedSmall;
    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) { refresh(); }
    };

    // The worker owns the claimed haptics interface.
    private UsbDevice connectedDevice;
    private UsbDeviceConnection connection;
    private UsbInterface port;
    private UsbEndpoint out, in;
    private int large, small, missedReplies;
    private boolean playing;
    private int[] footstep;
    private int footstepIndex, footstepScale;
    private final byte[] reply = new byte[64];
    private final Runnable tick = this::sendFrame;

    KishiHaptics(Context context, Runnable routeChanged) {
        this.context = context;
        this.routeChanged = routeChanged;
        usb = context.getSystemService(UsbManager.class);
        permissionAction = context.getPackageName() + ".KISHI_USB_PERMISSION";
    }

    void start() {
        if (usb == null || worker != null) return;
        thread.start();
        worker = new Handler(thread.getLooper());
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(events, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(events, filter);
        refresh();
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        refresh();
    }

    void setForeground(boolean foreground) {
        this.foreground = foreground;
        refresh();
    }

    private void refresh() {
        if (worker == null) return;
        UsbDevice found = null;
        for (UsbDevice candidate : usb.getDeviceList().values()) {
            if (candidate.getVendorId() == 0x1532 && candidate.getProductId() == 0x0724) {
                found = candidate;
                break;
            }
        }
        if (!Objects.equals(attached, found)) {
            attached = found;
            requested = false;
        }
        UsbDevice target = enabled && foreground ? attached : null;
        if (device != target) {
            device = target;
            attempted = false;
            worker.post(this::disconnect);
            setReady(false);
        }
        if (target == null) return;
        if (usb.hasPermission(target)) {
            if (attempted) return;
            attempted = true;
            worker.post(() -> connect(target));
        } else if (!requested) {
            requested = true;
            Intent signal = new Intent(permissionAction).setPackage(context.getPackageName());
            try {
                usb.requestPermission(target, PendingIntent.getBroadcast(context, 0, signal, PendingIntent.FLAG_IMMUTABLE));
            } catch (SecurityException ex) {
                Log.w(TAG, "Could not request access to Kishi haptics", ex);
            }
        }
    }

    private void setReady(boolean value) {
        if (ready == value) return;
        ready = value;
        wantedLarge = wantedSmall = 0;
        routeChanged.run();
    }

    boolean play(int l, int s) {
        if (!ready) return false;
        if (l != wantedLarge || s != wantedSmall) {
            wantedLarge = l;
            wantedSmall = s;
            worker.post(() -> {
                large = l;
                small = s;
                footstep = null;
                worker.removeCallbacks(tick);
                sendFrame();
            });
        }
        return true;
    }

    void step(int material, boolean running) {
        if (!ready || wantedLarge != 0 || wantedSmall != 0) return;
        int[] pattern = surfacePattern(material);
        if (pattern == null) return;
        worker.post(() -> {
            if (connection == null || large != 0 || small != 0) return;
            footstep = pattern;
            footstepIndex = 0;
            footstepScale = running ? 100 : 75;
            worker.removeCallbacks(tick);
            sendFrame();
        });
    }

    void cancelStep() {
        if (!ready) return;
        worker.post(() -> {
            if (footstep == null) return;
            footstep = null;
            worker.removeCallbacks(tick);
            sendFrame();
        });
    }

    private static int[] surfacePattern(int material) {
        switch (material) {
            case 1: case 2: case 14: case 15: case 16: case 32: return STONE;
            case 4: case 30: case 38: return WOOD;
            case 6: case 13: return GRAVEL;
            case 3: case 5: case 7: case 8: case 10: case 12: case 17: case 24: case 28: return SOFT;
            case 18: case 19: case 20: case 21: case 23: case 25: case 31:
            case 33: case 35: case 36: case 37: case 39: return WATER;
            case 9: return METAL;
            default: return null;
        }
    }

    private void connect(UsbDevice target) {
        for (int i = 0; i < target.getInterfaceCount(); i++) {
            UsbInterface candidate = target.getInterface(i);
            if (candidate.getId() != 4) continue;
            UsbEndpoint output = endpoint(candidate, 0x04);
            UsbEndpoint input = endpoint(candidate, 0x84);
            if (output == null || input == null) break;
            try {
                connection = usb.openDevice(target);
                if (connection == null || !connection.claimInterface(candidate, true)) break;
                connectedDevice = target;
                port = candidate;
                out = output;
                in = input;
                Log.i(TAG, "Kishi haptics interface claimed");
                main.post(() -> { if (worker != null && device == target) setReady(true); });
                return;
            } catch (SecurityException ex) {
                Log.w(TAG, "Kishi USB permission unavailable", ex);
                break;
            }
        }
        disconnect();
        Log.w(TAG, "Could not claim the Kishi haptics interface");
    }

    private static UsbEndpoint endpoint(UsbInterface port, int address) {
        for (int i = 0; i < port.getEndpointCount(); i++) {
            UsbEndpoint ep = port.getEndpoint(i);
            if (ep.getAddress() == address && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) return ep;
        }
        return null;
    }

    private void sendFrame() {
        boolean active = large != 0 || small != 0 || footstep != null;
        if (connection == null || (!active && !playing)) return;
        long start = SystemClock.uptimeMillis();
        byte[] frame;
        if (footstep != null) {
            int amplitude = footstep[footstepIndex++] * footstepScale / 100;
            int frequency = footstep[footstepIndex++];
            frame = frame(amplitude, amplitude * 3 / 4, frequency, frequency);
            if (footstepIndex == footstep.length) footstep = null;
        } else {
            frame = frame(large, small);
        }
        // usbfs also accepts interrupt endpoints for these bounded transfers.
        int sent = connection.bulkTransfer(out, frame, frame.length, 40);
        boolean answered = sent == frame.length && connection.bulkTransfer(in, reply, reply.length, 25) > 0;
        if (answered) missedReplies = 0;
        if (sent != frame.length || (!answered && active && ++missedReplies >= 4)) {
            Log.w(TAG, "Kishi haptic transfer failed, reverting to Android vibration");
            UsbDevice target = connectedDevice;
            disconnect();
            main.post(() -> { if (worker != null && device == target) setReady(false); });
            return;
        }
        playing = active;
        if (active) worker.postAtTime(tick, start + FRAME_MS);
    }

    private void disconnect() {
        worker.removeCallbacks(tick);
        if (connection != null) {
            if (port != null) {
                if (playing) {
                    byte[] stop = frame(0, 0);
                    connection.bulkTransfer(out, stop, stop.length, 40);
                }
                connection.releaseInterface(port);
            }
            connection.close();
        }
        connection = null;
        connectedDevice = null;
        port = null;
        playing = false;
        footstep = null;
        large = small = missedReplies = 0;
    }

    void close() {
        if (worker == null) return;
        context.unregisterReceiver(events);
        device = null;
        ready = false;
        worker.post(this::disconnect);
        thread.quitSafely();
    }

    // Protocol 2.5 stream report, based on Mokomis/rumblebridge (MIT).
    static byte[] frame(int large, int small) {
        return frame(large, small, 10, 35);
    }

    private static byte[] frame(int large, int small, int leftFrequency, int rightFrequency) {
        byte[] report = {2, 19, 0, 1, 0x0e, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        putBits(report, 40, FRAME_MS, 7);
        band(report, 47, large, leftFrequency);
        band(report, 102, small, rightFrequency);
        return report;
    }

    private static void band(byte[] report, int bit, int amplitude, int frequency) {
        putBits(report, bit++, 1, 1);
        int level = amplitude * 63 / 255;
        for (int i = 0; i < 4; i++, bit += 13) {
            putBits(report, bit, level, 6);
            putBits(report, bit + 6, level == 0 ? 0 : frequency, 7);
        }
    }

    private static void putBits(byte[] report, int bit, int value, int count) {
        for (int i = count - 1; i >= 0; i--, bit++) {
            if (((value >> i) & 1) != 0) report[bit / 8] |= 1 << (7 - bit % 8);
        }
    }
}
