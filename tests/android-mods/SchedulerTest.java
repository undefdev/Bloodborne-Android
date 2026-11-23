package com.ducvd89.bloodborne;

import java.util.ArrayList;
import java.util.List;

public final class SchedulerTest {
    static final class Sink implements Scheduler.Sink {
        final List<byte[]> frames = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
        final List<String> sounds = new ArrayList<>();
        long now;

        @Override public void frame(byte[] report) {
            frames.add(report);
            times.add(now);
        }

        @Override public void sound(String file, float gain) {
            sounds.add(file);
        }
    }

    static void run(Scheduler s, Sink sink, long fromNs, long toNs) {
        for (long t = fromNs; t <= toNs; t += 1_000_000L) {
            sink.now = t;
            s.poll(t);
        }
    }

    static Renderer.Playback pb(Effect e) {
        return Renderer.render(e, 1f, false, 1f, Profile.DEFAULT, 0);
    }

    /** One playback alone: frame k exactly at start + k * 15 ms, unchanged bytes. */
    public static void singlePlaybackKeepsItsTiming() {
        Effect a = Fx.left("a", 0.6, 100, 0.06);
        Sink sink = new Sink();
        Scheduler s = new Scheduler(sink);
        s.start(pb(a), 3_000_000L, "step");
        run(s, sink, 0, 200_000_000L);
        List<byte[]> want = Renderer.framesFor(a, 1f, false, 1f, Profile.DEFAULT);
        Check.eq(Fx.hexes(want), Fx.hexes(sink.frames), "same frames");
        for (int k = 0; k < sink.times.size(); k++) Check.ok(3_000_000L + k * Scheduler.FRAME_NS == sink.times.get(k), "time " + k + ": " + sink.times.get(k));
        Check.ok(s.idle(), "idle afterwards");
    }

    /** Different channels layer: while both play, a frame carries both bands; the same channel replaces. */
    public static void channelsLayerOrReplace() {
        Effect low = Fx.left("low", 0.6, 70, 0.09), high = Fx.left("high", 0.6, 200, 0.09);
        Sink sink = new Sink();
        Scheduler s = new Scheduler(sink);
        s.start(pb(low), 0, "step");
        run(s, sink, 0, 20_000_000L);
        s.start(pb(high), 20_000_000L, "hit");
        run(s, sink, 21_000_000L, 200_000_000L);
        FrameEncoder.Grip[] lowG = Renderer.gripsFor(low, 1f, false, 1f, Profile.DEFAULT).get(2);
        FrameEncoder.Grip[] highG = Renderer.gripsFor(high, 1f, false, 1f, Profile.DEFAULT).get(0);
        List<FrameEncoder.Grip> l = new ArrayList<>();
        l.add(lowG[0]);
        l.add(highG[0]);
        String mixed = Fx.hexes(java.util.Collections.singletonList(FrameEncoder.encode(Renderer.FRAME_MS, Renderer.mix(l), Renderer.mix(java.util.Arrays.asList(lowG[1], highG[1]))))).get(0);
        Check.ok(Fx.hexes(sink.frames).contains(mixed), "a frame with both effects' bands");
        int before = sink.frames.size();
        // Same channel: the new effect replaces the old one at once.
        Sink sink2 = new Sink();
        Scheduler s2 = new Scheduler(sink2);
        s2.start(pb(low), 0, "step");
        run(s2, sink2, 0, 20_000_000L);
        s2.start(pb(high), 20_000_000L, "step");
        run(s2, sink2, 21_000_000L, 200_000_000L);
        List<String> highOnly = Fx.hexes(Renderer.framesFor(high, 1f, false, 1f, Profile.DEFAULT));
        List<String> got = Fx.hexes(sink2.frames);
        Check.eq(highOnly, got.subList(got.size() - highOnly.size(), got.size()), "the rest is the new effect alone");
        Check.ok(before > 0, "frames sent");
    }

    /** Mix keeps the strongest bands and transients within the device limits. */
    public static void mixKeepsTheStrongest() {
        List<FrameEncoder.Grip> gs = new ArrayList<>();
        for (int a = 10; a <= 50; a += 10) gs.add(new FrameEncoder.Grip(new int[][] {{a, a, a, a}}, new int[][] {{a, a, a, a}},
            new int[][] {{a, 24, a / 10}}));
        FrameEncoder.Grip m = Renderer.mix(gs);
        Check.eq(3, m.bandAmp.length, "three bands");
        Check.eq(50, m.bandAmp[0][0], "strongest first");
        Check.eq(30, m.bandAmp[2][0], "weakest kept is 30");
        Check.eq(2, m.transients.length, "two transients");
        Check.eq(4, m.transients[0][2], "ordered by time");
        Check.eq(40, m.transients[0][0], "the two strongest: 40");
        Check.eq(50, m.transients[1][0], "and 50");
    }
}
