package com.ducvd89.bloodborne;

import android.content.Context;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/** The game's rumble ("<large> <small>" in bbport's atomic rumble file) -> the pad's motors, else the device's own.
 *  No polling: a FileObserver wakes it when the runtime renames a new file in.
 *  With the game folder's haptics/haptics.json (the workbench's design, HapticsTable), the Kishi plays it (KishiHaptics,
 *  GameHaptics): the player's sounds and the rumbles' starts and ends come from the runtime's event pipe (EventPipe,
 *  BB_EVENT_PIPE), the motors from the rumble file. Footsteps from the footstep file then play only while the pipe has
 *  not said it sends the sounds (its hook S1 installed, or any sound), so none plays twice. */
final class RumbleBridge {
    private static final String TAG="BloodborneKishi";
    private static final int ON_THRESHOLD=64; // without amplitude control: on at or above this
    /** A pipe sound older than this (ms) is not played any more. */
    private static final long SOUND_MAX_AGE_MS=200;
    private final Context context;
    private final File file, footsteps;
    private final KishiHaptics kishi;
    private final EventPipe pipe;
    private boolean pipeSounds; // this game start's sounds come from the pipe (main thread)
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Vibrator> active = new ArrayList<>();
    private FileObserver observer;
    private boolean enabled=true, drawer, paused;
    private int large, small;
    private long lastFootstep;
    private boolean testing;
    private final Runnable endTest = this::stopTest;
    /** Told on the main thread whether the game has a world (the pipe's "W" lines; true again at each game start). */
    interface WorldListener { void world(boolean present); }
    private WorldListener world;

    RumbleBridge(Context context, File file) {
        this.context=context;
        this.file=file;
        footsteps=new File(file.getParentFile(),"android-footstep.state");
        kishi=new KishiHaptics(context,() -> { if (enabled && !drawer && !paused) apply(); });
        pipe=new EventPipe(new File(context.getFilesDir(),"haptics-events.fifo"),e -> handler.post(() -> event(e)));
    }

    /** Who hears the pipe's world state (MainActivity: the menus' stick). Main thread. */
    void setWorldListener(WorldListener l) { world=l; }

    /** Starts watching; the file is not read (an old value must not replay). Main thread. */
    void start() {
        File dir=file.getParentFile();
        if (observer!=null || dir==null) return;
        dir.mkdirs(); file.delete(); footsteps.delete();
        final String name=file.getName();
        observer=new FileObserver(dir.getPath(),FileObserver.MOVED_TO|FileObserver.CLOSE_WRITE) {
            @Override public void onEvent(int event, String path) {
                if (name.equals(path)) handler.post(RumbleBridge.this::read);
                else if (footsteps.getName().equals(path)) handler.post(RumbleBridge.this::readFootstep);
            }
        };
        observer.startWatching();
        kishi.start();
    }
    void close() {
        if (observer!=null) { observer.stopWatching(); observer=null; }
        enabled=false;
        reset();
        pipe.close();
        kishi.close();
    }
    /** At each game start, the game folder's haptics (any thread): its haptics/haptics.json when it is there and valid,
     *  else, as before, its footstep tracks (FootstepTracks) and the motor rumble; one log line says which. Returns the
     *  event pipe for BB_EVENT_PIPE when the design plays and the pipe could be made, else null (the runtime then runs
     *  without it, as before). */
    String setGame(File gameDir) {
        HapticsTable table=null;
        String why="no game folder";
        if (gameDir!=null) {
            try { table=HapticsTable.load(gameDir); }
            catch (HapticsTable.Invalid e) { why=e.getMessage(); }
        }
        handler.post(() -> { pipeSounds=false; if (world!=null) world.world(true); });
        if (table==null) {
            FootstepTracks tracks=gameDir==null ? null : FootstepTracks.load(gameDir);
            kishi.setTable(null);
            kishi.setTracks(tracks);
            android.util.Log.i(TAG,"Haptics: "+HapticsTable.FILE+" not used ("+why+"): footsteps from "
                +(tracks!=null ? FootstepTracks.FILE : "the built-in patterns")+", rumble as motor frames");
            // The pipe still carries the world state (the menus' stick); its sounds are not played without the design.
            return pipe.open();
        }
        kishi.setTracks(null);
        kishi.setTable(table);
        String path=pipe.open();
        android.util.Log.i(TAG,"Haptics: "+HapticsTable.FILE+" loaded: "+table.summary()
            +(path==null ? "; no event pipe: footsteps from the footstep file" : ""));
        return path;
    }
    void setForeground(boolean foreground) {
        if (!foreground) stopTest();
        kishi.setForeground(foreground);
    }

    /** Controller input as MainActivity dispatches it, all of it (also with the drawer open or the game held): the
     *  Kishi's idle recovery (KishiIdleReset). Main thread. */
    void input(KeyEvent e) { kishi.input(e); }
    void input(MotionEvent e) { kishi.input(e); }

    boolean testKishi() {
        if (!enabled || !drawer || !kishi.play(170,170)) return false;
        testing=true;
        handler.removeCallbacks(endTest);
        handler.postDelayed(endTest,220);
        return true;
    }

    private void stopTest() {
        if (!testing) return;
        handler.removeCallbacks(endTest);
        testing=false;
        kishi.play(0,0);
    }

    /** The setting: off silences it at once; on picks up what the game has set. */
    void setEnabled(boolean on) {
        enabled=on;
        if (!on) silence();
        kishi.setEnabled(on);
        if (on && observer!=null) read();
    }
    /** The drawer is open or the game is held: nothing vibrates then. The file always holds the game's current
     *  motors (the runtime writes only changes), so it is read again when that ends. Any thread. */
    void setDrawer(boolean open) { handler.post(() -> { drawer=open; if (open) silence(); else { stopTest(); read(); } }); }
    void setPaused(boolean hold) { handler.post(() -> { if (paused==hold) return; paused=hold; if (hold) silence(); else read(); }); }
    /** The game ended or restarts: also the file goes, so the next game does not replay it. */
    void reset() { file.delete(); footsteps.delete(); silence(); }
    private void silence() {
        stopTest();
        large=small=0;
        kishi.cancelStep();
        kishi.play(0,0);
        cancelAndroidVibration();
    }

    private void cancelAndroidVibration() {
        for (Vibrator v:active) v.cancel();
        active.clear();
    }

    private void read() {
        if (!enabled || drawer || paused) return;
        int l, s;
        try (BufferedReader in=new BufferedReader(new FileReader(file))) {
            String[] part=in.readLine().trim().split("\\s+");
            l=Math.max(0,Math.min(255,Integer.parseInt(part[0]))); s=Math.max(0,Math.min(255,Integer.parseInt(part[1])));
        } catch (Exception e) { l=s=0; } // no file: the game set nothing (or 0 0)
        if (l==large && s==small) return;
        large=l; small=s;
        apply();
    }

    private void readFootstep() {
        if (!enabled || drawer || paused) return;
        try (BufferedReader in=new BufferedReader(new FileReader(footsteps))) {
            String line=in.readLine();
            if (line==null) return;
            String[] parts=line.split(" ");
            if (parts.length!=3) return;
            long timestamp=Long.parseLong(parts[0]);
            long age=SystemClock.elapsedRealtime()-timestamp;
            if (timestamp<=lastFootstep || age<0 || age>120) return;
            lastFootstep=timestamp;
            if (!kishi.effects()) kishi.step(Integer.parseInt(parts[1]),"2001".equals(parts[2]));
            else if (!pipeSounds) kishi.sound(GameHaptics.footstepSound(Integer.parseInt(parts[1]),Integer.parseInt(parts[2])));
        } catch (java.io.IOException | NumberFormatException e) {
            // A reset may delete a pending event.
        }
    }

    /** A line from the event pipe (main thread). Rumble starts and ends always count (the design's mutes); sounds play
     *  like the footsteps: not with Vibration off, the drawer open or the game held, and not when late. */
    private void event(HapticEvent e) {
        switch (e.kind) {
            case HOOKS: if (e.hook("S1")) pipeSounds=true; break;
            case RUMBLE_START: case RUMBLE_END: kishi.rumble(e.kind==HapticEvent.Kind.RUMBLE_START,e.id); break;
            case WORLD: if (world!=null) world.world(e.id==1); break;
            case SOUND:
                pipeSounds=true;
                long age=SystemClock.elapsedRealtime()-e.ms;
                if (!enabled || drawer || paused || age>SOUND_MAX_AGE_MS || age<-SOUND_MAX_AGE_MS) return;
                kishi.sound(e.text);
                break;
        }
    }

    /** The pad's vibrators (two: large, small; one: the stronger), else the device's own at the stronger. */
    private void apply() {
        if (kishi.play(large,small) || (large==0 && small==0)) {
            cancelAndroidVibration();
            return;
        }
        List<Vibrator> targets=new ArrayList<>(); int[] amps;
        List<Vibrator> pad=padVibrators();
        if (pad.size()>=2) { targets.add(pad.get(0)); targets.add(pad.get(1)); amps=new int[]{large,small}; }
        else {
            if (!pad.isEmpty()) targets.add(pad.get(0));
            else { Vibrator own=deviceVibrator(); if (own!=null) targets.add(own); }
            amps=new int[]{Math.max(large,small)};
        }
        for (Vibrator v:active) if (!targets.contains(v)) v.cancel();
        active.clear();
        for (int i=0;i<targets.size();i++) {
            Vibrator v=targets.get(i); int amp=amps[Math.min(i,amps.length-1)];
            if (amp==0) { v.cancel(); continue; }
            try {
                if (Build.VERSION.SDK_INT>=26) {
                    if (v.hasAmplitudeControl()) v.vibrate(VibrationEffect.createWaveform(new long[]{1000},new int[]{amp},0));
                    else if (amp>=ON_THRESHOLD) v.vibrate(VibrationEffect.createWaveform(new long[]{0,1000},0));
                    else { v.cancel(); continue; }
                } else if (amp>=ON_THRESHOLD) v.vibrate(new long[]{0,1000},0);
                else { v.cancel(); continue; }
                active.add(v);
            } catch (RuntimeException e) { /* no vibration permission or a vanished device */ }
        }
    }

    private static List<Vibrator> padVibrators() {
        List<Vibrator> out=new ArrayList<>();
        for (int id:InputDevice.getDeviceIds()) {
            InputDevice d=InputDevice.getDevice(id);
            if (d==null || (d.getSources()&(InputDevice.SOURCE_GAMEPAD|InputDevice.SOURCE_JOYSTICK))==0) continue;
            if (Build.VERSION.SDK_INT>=31) {
                VibratorManager vm=d.getVibratorManager();
                for (int vid:vm.getVibratorIds()) out.add(vm.getVibrator(vid));
            } else {
                Vibrator v=d.getVibrator();
                if (v!=null && v.hasVibrator()) out.add(v);
            }
            if (!out.isEmpty()) break;
        }
        return out;
    }
    private Vibrator deviceVibrator() {
        Vibrator v;
        if (Build.VERSION.SDK_INT>=31) {
            VibratorManager vm=(VibratorManager)context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            v=vm==null ? null : vm.getDefaultVibrator();
        } else v=(Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);
        return v!=null && v.hasVibrator() ? v : null;
    }
}
