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
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.Objects;

final class KishiHaptics {
    private static final String TAG = "BloodborneKishi";
    private static final int FRAME_MS = KishiReports.FRAME_MS, SLOTS = KishiReports.SLOTS;
    private static final int CONTROL_INTERFACE = 3, HAPTICS_INTERFACE = 4;
    /** After a DeviceReset the Kishi re-enumerates (about 1 s); one that stayed attached is reclaimed after this. */
    private static final int REATTACH_MS = 3000;
    private static final int HEEL_FREQ = 10, HEEL_SLOTS = 8, HEEL_PERCENT = 40;
    // Without the game folder's tracks: (amplitude 0-63, frequency) per 7.5 ms slot.
    private static final int[] STONE = KishiReports.slots(20, 35);
    private static final int[] WOOD = KishiReports.slots(16, 10, 6, 14);
    private static final int[] GRAVEL = KishiReports.slots(15, 41, 8, 27);
    private static final int[] SOFT = KishiReports.slots(11, 11);
    private static final int[] WATER = KishiReports.slots(14, 28, 0, 0, 9, 41);
    private static final int[] METAL = KishiReports.slots(17, 45, 7, 37);
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
    private volatile FootstepTracks tracks;
    private final KishiIdleReset idle = new KishiIdleReset();
    private final Runnable recheck = this::refresh;
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
    private int footstepSlot, footstepScale;
    private boolean leftFoot;
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

    /** The game folder's footstep tracks (null: the built-in patterns). */
    void setTracks(FootstepTracks tracks) { this.tracks = tracks; }

    void step(int material, boolean running) {
        if (!ready || wantedLarge != 0 || wantedSmall != 0) return;
        FootstepTracks sounds = tracks;
        int[] pattern = sounds != null ? sounds.pick(material, running) : surfacePattern(material);
        if (pattern == null) return;
        worker.post(() -> {
            if (connection == null || large != 0 || small != 0) return;
            footstep = pattern;
            footstepSlot = 0;
            footstepScale = running ? 100 : 80;
            leftFoot = !leftFoot;
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

    /** A key event from MainActivity's dispatch (main thread): a gamepad key press counts as controller input. */
    void input(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return;
        if (e.isFromSource(InputDevice.SOURCE_GAMEPAD) || e.isFromSource(InputDevice.SOURCE_DPAD)
                || KeyEvent.isGamepadButton(e.getKeyCode())) controllerInput();
    }

    /** A motion event from MainActivity's dispatch (main thread): a stick or trigger moved, or the d-pad hat pressed. */
    void input(MotionEvent e) {
        if (!e.isFromSource(InputDevice.SOURCE_JOYSTICK) || e.getActionMasked() != MotionEvent.ACTION_MOVE) return;
        if (KishiIdleReset.active(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y),
                e.getAxisValue(MotionEvent.AXIS_Z), e.getAxisValue(MotionEvent.AXIS_RZ),
                Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)),
                Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)),
                e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y))) controllerInput();
    }

    /** Real controller input: the Kishi may get its DeviceReset now (KishiIdleReset), on the worker. */
    private void controllerInput() {
        if (worker == null) return;
        String why = idle.input(SystemClock.elapsedRealtime(), ready);
        if (why == null) return;
        UsbDevice target = device;
        worker.post(() -> resetDevice(target, why));
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
            if (candidate.getId() != HAPTICS_INTERFACE) continue;
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

    /** DeviceReset (KishiReports.deviceReset) on the control interface, claimed with force for it and released after;
     *  nothing else is sent there. The Kishi answers RESET_OK and re-enumerates about a second later: the haptics
     *  interface is let go now, the USB_DEVICE_DETACHED / ATTACHED broadcasts reconnect (refresh), and a Kishi that
     *  stayed attached is reclaimed after REATTACH_MS. One log line per reset. Worker thread. */
    private void resetDevice(UsbDevice target, String why) {
        String result;
        boolean sent = false;
        UsbInterface control = null;
        if (connection != null && connectedDevice == target) {
            for (int i = 0; i < target.getInterfaceCount(); i++) {
                if (target.getInterface(i).getId() == CONTROL_INTERFACE) control = target.getInterface(i);
            }
        }
        if (connection == null || connectedDevice != target) result = "not connected";
        else if (control == null) result = "no control interface";
        else if (!connection.claimInterface(control, true)) result = "control interface not claimed";
        else {
            try {
                byte[] report = KishiReports.deviceReset();
                int n = connection.controlTransfer(0x21, 0x09, 0x0300, CONTROL_INTERFACE, report, report.length, 500);
                sent = n == report.length;
                result = sent ? resetAnswer() : "SET_REPORT failed (" + n + ")";
            } finally {
                connection.releaseInterface(control);
            }
        }
        Log.i(TAG, "Kishi DeviceReset (" + why + "): " + result);
        if (!sent) return;
        disconnect();
        main.post(() -> {
            if (worker == null || device != target) return; // already detached: the attach broadcast reconnects
            attempted = false;
            setReady(false);
            main.removeCallbacks(recheck);
            main.postDelayed(recheck, REATTACH_MS);
        });
    }

    /** DeviceReset's answer (GET_REPORT), read again while the Kishi says busy. Worker thread, control interface claimed. */
    private String resetAnswer() {
        byte[] answer = new byte[KishiReports.RESET_SIZE];
        for (int i = 0; i < 10; i++) {
            SystemClock.sleep(20);
            int n = connection.controlTransfer(0xA1, 0x01, 0x0300, CONTROL_INTERFACE, answer, answer.length, 500);
            if (n <= 0) return "no answer (" + n + ")";
            if (answer[0] == KishiReports.RESET_OK) return "ok";
            if (answer[0] != KishiReports.RESET_BUSY) return "status " + (answer[0] & 0xff);
        }
        return "still busy";
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
            // The stepping foot's grip plays the track; the other one only the heel strike, low and weaker.
            int[] step = new int[SLOTS * 2], heel = new int[SLOTS * 2];
            for (int i = 0; i < SLOTS; i++, footstepSlot++) {
                if (footstepSlot * 2 >= footstep.length) break;
                step[i * 2] = footstep[footstepSlot * 2] * footstepScale / 100;
                step[i * 2 + 1] = footstep[footstepSlot * 2 + 1];
                if (footstepSlot < HEEL_SLOTS) {
                    heel[i * 2] = step[i * 2] * HEEL_PERCENT / 100;
                    heel[i * 2 + 1] = HEEL_FREQ;
                }
            }
            frame = leftFoot ? KishiReports.frame(step, heel) : KishiReports.frame(heel, step);
            if (footstepSlot * 2 >= footstep.length) footstep = null;
        } else {
            frame = KishiReports.frame(large, small);
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
                    byte[] stop = KishiReports.frame(0, 0);
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
        main.removeCallbacks(recheck);
        context.unregisterReceiver(events);
        device = null;
        ready = false;
        worker.post(this::disconnect);
        thread.quitSafely();
    }
}
