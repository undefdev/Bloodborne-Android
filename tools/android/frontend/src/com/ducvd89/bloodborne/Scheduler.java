package com.ducvd89.bloodborne;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Plays playbacks on its own thread on one frame clock: every 15 ms while anything plays, the current frames of all
 * active playbacks are mixed (Renderer.mix: the strongest bands and transients per grip) into one report. A new
 * playback replaces an active one on the same channel (the next footstep cuts the previous step's tail) and layers
 * over the others (a hit over a footstep, the parts of a dodge). Starting while idle starts the clock at the
 * playback's start, so a single effect keeps its exact timing; started while the clock runs, its frames join at the
 * next tick (at most one frame later). Sounds play at their own delays. A tick more than one frame period late is
 * skipped rather than sent in a burst (e.g. after the Kishi link was blocked by a reset).
 * From hapticsBench's player (player/src/hb, d7d60f9), unchanged but for the package; plain Java, tested on the host.
 */
public final class Scheduler implements Runnable {
    public static final long FRAME_NS = Renderer.FRAME_MS * 1_000_000L;
    public static final String DEFAULT_CHANNEL = "default";

    public interface Sink {
        /** Send one report; called on the scheduler thread. */
        void frame(byte[] report);

        /** Start a sound now. */
        void sound(String file, float gain);
    }

    private static final class Active {
        final String channel;
        final long startNs;
        final List<FrameEncoder.Grip[]> grips;
        String sound;
        final float gain;
        final long soundAtNs;

        Active(String channel, long startNs, Renderer.Playback pb) {
            this.channel = channel;
            this.startNs = startNs;
            this.grips = pb.grips;
            this.sound = pb.soundFile;
            this.gain = pb.soundGain;
            this.soundAtNs = startNs + pb.soundDelayMs * 1_000_000L;
        }

        boolean framesLeft(long tickNs) {
            return tickNs < startNs + grips.size() * FRAME_NS;
        }
    }

    private final Sink sink;
    private final List<Active> active = new ArrayList<>();
    private long tickNs = -1; // the next frame tick, -1 while no frames are queued
    private volatile boolean running = true;

    public Scheduler(Sink sink) {
        this.sink = sink;
    }

    /** start(pb, nowNs, DEFAULT_CHANNEL). */
    public synchronized long start(Renderer.Playback pb, long nowNs) {
        return start(pb, nowNs, DEFAULT_CHANNEL);
    }

    /** Starts pb on a channel; returns the time of its first frame. A negative sound delay plays the sound first. */
    public synchronized long start(Renderer.Playback pb, long nowNs, String channel) {
        long lead = Math.max(0, -pb.soundDelayMs) * 1_000_000L;
        long startNs = nowNs + lead;
        active.removeIf(a -> a.channel.equals(channel));
        Active a = new Active(channel, startNs, pb);
        boolean clockRunning = tickNs >= 0 && othersHaveFrames(tickNs, a);
        active.add(a);
        if (!pb.grips.isEmpty() && !clockRunning) tickNs = startNs;
        notifyAll();
        return startNs;
    }

    private boolean othersHaveFrames(long t, Active except) {
        for (Active a : active) if (a != except && a.framesLeft(t)) return true;
        return false;
    }

    /** Does what is due at nowNs. Returns the next due time, or Long.MAX_VALUE when nothing is queued. */
    public long poll(long nowNs) {
        List<String> sounds = new ArrayList<>();
        List<Float> gains = new ArrayList<>();
        byte[] frame = null;
        synchronized (this) {
            for (Active a : active) {
                if (a.sound != null && nowNs >= a.soundAtNs) {
                    sounds.add(a.sound);
                    gains.add(a.gain);
                    a.sound = null;
                }
            }
            while (tickNs >= 0 && nowNs >= tickNs) {
                long t = tickNs;
                List<FrameEncoder.Grip> left = new ArrayList<>(), right = new ArrayList<>();
                for (Active a : active) {
                    if (t < a.startNs) continue;
                    int k = (int) ((t - a.startNs) / FRAME_NS);
                    if (k >= a.grips.size()) continue;
                    FrameEncoder.Grip[] g = a.grips.get(k);
                    if (g == null) continue;
                    left.add(g[0]);
                    right.add(g[1]);
                }
                tickNs = anyFramesAfter(t) ? t + FRAME_NS : -1;
                if (!left.isEmpty() && nowNs - t < FRAME_NS) {
                    frame = left.size() == 1 ? FrameEncoder.encode(Renderer.FRAME_MS, left.get(0), right.get(0))
                        : FrameEncoder.encode(Renderer.FRAME_MS, Renderer.mix(left), Renderer.mix(right));
                    break;
                }
            }
            long horizon = tickNs < 0 ? Long.MAX_VALUE / 2 : tickNs;
            for (Iterator<Active> it = active.iterator(); it.hasNext(); ) {
                Active a = it.next();
                if (a.sound == null && !a.framesLeft(horizon)) it.remove();
            }
        }
        for (int i = 0; i < sounds.size(); i++) sink.sound(sounds.get(i), gains.get(i));
        if (frame != null) sink.frame(frame);
        return nextDue();
    }

    private boolean anyFramesAfter(long t) {
        for (Active a : active) if (a.framesLeft(t + FRAME_NS)) return true;
        return false;
    }

    public synchronized boolean idle() {
        return active.isEmpty();
    }

    private synchronized long nextDue() {
        long due = tickNs < 0 ? Long.MAX_VALUE : tickNs;
        for (Active a : active) if (a.sound != null) due = Math.min(due, a.soundAtNs);
        return due;
    }

    public void stop() {
        running = false;
        synchronized (this) {
            notifyAll();
        }
    }

    @Override public void run() {
        while (running) {
            long due;
            try {
                due = poll(System.nanoTime());
            } catch (RuntimeException e) {
                e.printStackTrace(System.out);
                due = System.nanoTime() + FRAME_NS;
            }
            synchronized (this) {
                due = Math.min(nextDue(), due); // start() may have queued something meanwhile
                if (!running) break;
                try {
                    if (due == Long.MAX_VALUE) {
                        wait(1000);
                    } else {
                        long wait = due - System.nanoTime();
                        if (wait <= 0) continue;
                        if (wait > 1_000_000_000L) wait(1000);
                        else wait(wait / 1_000_000L, (int) (wait % 1_000_000L));
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }
}
