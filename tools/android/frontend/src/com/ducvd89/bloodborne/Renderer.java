package com.ducvd89.bloodborne;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns an effect into Kishi reports: one 15 ms frame per 15 ms, each band sampled at 4 points 3.75 ms apart,
 * transients in the frame they fall in (with their time offset in ms). Amplitudes go through the profile's
 * correction and quantization (like device.quantize); transients that quantize to 0 are left out.
 * From hapticsBench's player (player/src/hb, d7d60f9), unchanged but for the package; plain Java, tested on the host.
 */
public final class Renderer {
    public static final int FRAME_MS = 15, POINTS = FrameEncoder.POINTS;
    public static final double POINT_MS = FRAME_MS / (double) POINTS;
    private static final double EPS = 1e-9;

    private Renderer() {}

    /** What to play for one trigger: the frames, plus the sound to start soundDelayMs after the first frame. */
    public static final class Playback {
        /** One 64-byte report per FRAME_MS; null entries are silent frames (nothing is sent). */
        public final List<byte[]> frames;
        /** The same frames unencoded, {left, right} per frame (null when silent), so the scheduler can mix playbacks. */
        public final List<FrameEncoder.Grip[]> grips;
        /** Null when the effect has no sound. */
        public final String soundFile;
        public final float soundGain;
        public final int soundDelayMs;

        Playback(List<FrameEncoder.Grip[]> grips, String soundFile, float soundGain, int soundDelayMs) {
            List<byte[]> frames = new ArrayList<>(grips.size());
            for (FrameEncoder.Grip[] g : grips) frames.add(g == null ? null : FrameEncoder.encode(FRAME_MS, g[0], g[1]));
            this.grips = Collections.unmodifiableList(grips);
            this.frames = Collections.unmodifiableList(frames);
            this.soundFile = soundFile;
            this.soundGain = soundGain;
            this.soundDelayMs = soundDelayMs;
        }
    }

    /** Frames and sound cue; the sound starts audioOffsetMs + sound.offset_ms after the first frame. */
    public static Playback render(Effect e, float strength, boolean mirror, float otherGain, Profile p, int audioOffsetMs) {
        List<FrameEncoder.Grip[]> grips = gripsFor(e, strength, mirror, otherGain, p);
        if (e.sound == null || e.sound.file == null) return new Playback(grips, null, 0, 0);
        return new Playback(grips, e.sound.file, e.sound.gain, audioOffsetMs + e.sound.offsetMs);
    }

    /**
     * The effect's frames. mirror puts the effect's left grip on the right one and vice versa (a right-foot
     * step); otherGain scales the effect's right grip (the other foot) wherever it goes; strength scales both.
     * Trailing silent frames are dropped.
     */
    public static List<byte[]> framesFor(Effect e, float strength, boolean mirror, float otherGain, Profile p) {
        List<byte[]> frames = new ArrayList<>();
        for (FrameEncoder.Grip[] g : gripsFor(e, strength, mirror, otherGain, p))
            frames.add(g == null ? null : FrameEncoder.encode(FRAME_MS, g[0], g[1]));
        return frames;
    }

    /** framesFor, unencoded: {left, right} per frame, null for a silent frame; trailing silent frames dropped. */
    public static List<FrameEncoder.Grip[]> gripsFor(Effect e, float strength, boolean mirror, float otherGain, Profile p) {
        Effect.Grip toLeft = mirror ? e.right : e.left, toRight = mirror ? e.left : e.right;
        double gainLeft = strength * (mirror ? otherGain : 1f), gainRight = strength * (mirror ? 1f : otherGain);
        int n = frameCount(e);
        List<FrameEncoder.Grip[]> frames = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            FrameEncoder.Grip l = grip(toLeft, gainLeft, k, p), r = grip(toRight, gainRight, k, p);
            frames.add(l.isEmpty() && r.isEmpty() ? null : new FrameEncoder.Grip[] {l, r});
        }
        while (!frames.isEmpty() && frames.get(frames.size() - 1) == null) frames.remove(frames.size() - 1);
        return frames;
    }

    /** Several grips' content in one: the MAX_BANDS strongest bands and MAX_TRANSIENTS strongest transients. */
    public static FrameEncoder.Grip mix(List<FrameEncoder.Grip> grips) {
        List<int[][]> bands = new ArrayList<>(); // {amps, idxs}
        List<int[]> trs = new ArrayList<>();
        for (FrameEncoder.Grip g : grips) {
            if (g == null) continue;
            for (int b = 0; b < g.bandAmp.length; b++) bands.add(new int[][] {g.bandAmp[b], g.bandIdx[b]});
            for (int[] t : g.transients) if (t[0] > 0) trs.add(t);
        }
        bands.sort((x, y) -> Integer.compare(peak(y[0]), peak(x[0])));
        trs.sort((x, y) -> Integer.compare(y[0], x[0]));
        int nb = Math.min(FrameEncoder.MAX_BANDS, bands.size()), nt = Math.min(FrameEncoder.MAX_TRANSIENTS, trs.size());
        int[][] amps = new int[nb][], idxs = new int[nb][];
        for (int i = 0; i < nb; i++) {
            amps[i] = bands.get(i)[0];
            idxs[i] = bands.get(i)[1];
        }
        List<int[]> keep = new ArrayList<>(trs.subList(0, nt));
        keep.sort((x, y) -> Integer.compare(x[2], y[2]));
        return new FrameEncoder.Grip(amps, idxs, keep.toArray(new int[0][]));
    }

    private static int peak(int[] a) {
        int m = 0;
        for (int v : a) m = Math.max(m, v);
        return m;
    }

    /** Frames covering every band up to its last point (exclusive) and every transient. */
    static int frameCount(Effect e) {
        int n = 0;
        for (Effect.Grip g : new Effect.Grip[] {e.left, e.right}) {
            for (Effect.Band b : bands(g)) {
                if (b.t.length == 0) continue;
                double endMs = b.t[b.t.length - 1] * 1000;
                n = Math.max(n, Math.max(1, (int) Math.ceil(endMs / FRAME_MS - EPS)));
            }
            for (Effect.Transient tr : g.transients) n = Math.max(n, frameOf(tr.t) + 1);
        }
        return n;
    }

    private static List<Effect.Band> bands(Effect.Grip g) {
        return g.bands.size() <= FrameEncoder.MAX_BANDS ? g.bands : g.bands.subList(0, FrameEncoder.MAX_BANDS);
    }

    private static int frameOf(double tSeconds) {
        return Math.max(0, (int) Math.floor(tSeconds * 1000 / FRAME_MS + EPS));
    }

    private static FrameEncoder.Grip grip(Effect.Grip g, double gain, int k, Profile p) {
        List<int[]> amps = new ArrayList<>(), idxs = new ArrayList<>();
        for (Effect.Band b : bands(g)) {
            int[] a = new int[POINTS], ix = new int[POINTS];
            boolean any = false;
            for (int j = 0; j < POINTS; j++) {
                double[] v = b.at((k * FRAME_MS + j * POINT_MS) / 1000.0);
                int[] q = p.quantize(v[0] * gain, v[1]);
                a[j] = q[0];
                ix[j] = q[1];
                any |= q[0] > 0;
            }
            if (any) {
                amps.add(a);
                idxs.add(ix);
            }
        }
        List<int[]> trs = new ArrayList<>();
        for (Effect.Transient tr : g.transients) { // sorted by t: the first two of the frame
            if (frameOf(tr.t) != k) continue;
            int[] q = p.quantize(tr.amp * gain, tr.hz);
            if (q[0] == 0) continue; // never send an empty transient
            int offset = (int) Math.floor(tr.t * 1000 - k * FRAME_MS + EPS);
            trs.add(new int[] {q[0], q[1], Math.max(0, Math.min(FRAME_MS - 1, offset))});
            if (trs.size() == FrameEncoder.MAX_TRANSIENTS) break;
        }
        return new FrameEncoder.Grip(amps.toArray(new int[0][]), idxs.toArray(new int[0][]), trs.toArray(new int[0][]));
    }
}
