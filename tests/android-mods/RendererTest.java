package com.ducvd89.bloodborne;

import com.ducvd89.bloodborne.FrameEncoder.Grip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RendererTest {
    static final Profile P = Profile.DEFAULT;

    /**
     * An effect without a sound renders and plays its frames with no sound cue; one with a sound still plays all its
     * frames, and the sink gets the cue (the game ignores it: it plays its own sounds). Ported from the workbench
     * player's test without its audio Mixer, which the app does not have.
     */
    public static void effectWithoutSound() {
        Effect e = new Effect("thud", "Thud", null, Fx.grip(Fx.band(0, 0.06, 0.5, 100)), Fx.transients(new Effect.Transient(0, 0.8, 100)));
        Renderer.Playback pb = Renderer.render(e, 1f, false, 1f, P, 20);
        Check.ok(pb.soundFile == null, "no sound cue");
        Check.eq(4, pb.frames.size(), "frames");
        for (byte[] f : pb.frames) Check.eq(64, f.length, "padded report");

        Effect missing = Fx.withSound(e, "sounds/gone.wav", 1f, 5);
        Renderer.Playback withCue = Renderer.render(missing, 1f, false, 1f, P, 20);
        Check.eq("sounds/gone.wav", withCue.soundFile, "sound cue");
        Check.eq(25, withCue.soundDelayMs, "audio_offset_ms + offset_ms");

        List<byte[]> sent = new ArrayList<>();
        List<String> cues = new ArrayList<>();
        Scheduler s = new Scheduler(new Scheduler.Sink() {
            @Override public void frame(byte[] report) {
                sent.add(report);
            }

            @Override public void sound(String file, float gain) {
                cues.add(file);
            }
        });
        for (int trigger = 0; trigger < 2; trigger++) {
            long t0 = s.start(withCue, 1_000_000_000L * (trigger + 1));
            for (long t = t0; !s.idle(); t += 1_000_000L) s.poll(t);
        }
        Check.eq(8, sent.size(), "all frames of both triggers played");
        Check.eq(Arrays.asList("sounds/gone.wav", "sounds/gone.wav"), cues, "a cue per trigger");
    }

    public static void frameCountMatchesLength() {
        Check.eq(7, Renderer.framesFor(Fx.left("a", 0.5, 100, 0.100), 1f, false, 1f, P).size(), "100 ms band: ceil(100/15)");
        Check.eq(6, Renderer.framesFor(Fx.left("a", 0.5, 100, 0.090), 1f, false, 1f, P).size(), "90 ms band: 6 frames");
        Check.eq(1, Renderer.framesFor(Fx.left("a", 0.5, 100, 0.0), 1f, false, 1f, P).size(), "single point");
        Effect late = new Effect("t", "t", null, Fx.transients(new Effect.Transient(0.05, 0.8, 100)), null);
        List<byte[]> f = Renderer.framesFor(late, 1f, false, 1f, P);
        Check.eq(4, f.size(), "transient at 50 ms lands in frame 3");
        Check.ok(f.get(0) == null && f.get(1) == null && f.get(2) == null, "silent frames before it are null");
        Effect empty = new Effect("e", "e", null, null, null);
        Check.eq(0, Renderer.framesFor(empty, 1f, false, 1f, P).size(), "empty effect");
        Check.eq(0, Renderer.framesFor(Fx.left("a", 0.5, 100, 0.1), 0f, false, 1f, P).size(), "strength 0: nothing");
    }

    public static void framesSampleBandsAndCorrect() {
        // 1.0 at 100 Hz (response 1.0) is 63 at index 24 on all four points; 0.42 at 59 Hz is corrected to 63 too.
        List<byte[]> f = Renderer.framesFor(Fx.left("a", 1.0, 100, 0.03), 1f, false, 1f, P);
        Check.eq(Check.hex(FrameEncoder.encode(15, Grip.tone(63, 24), Grip.empty())), Check.hex(f.get(0)), "flat 63@24");
        f = Renderer.framesFor(Fx.left("a", 0.42, 59, 0.03), 1f, false, 1f, P);
        Check.eq(Check.hex(FrameEncoder.encode(15, Grip.tone(63, 9), Grip.empty())), Check.hex(f.get(0)), "corrected 59 Hz");
        // A ramp: points 3.75 ms apart.
        Effect ramp = new Effect("r", "r", null, Fx.grip(new Effect.Band(new double[] {0, 0.015}, new double[] {0, 1}, new double[] {100, 100})), null);
        f = Renderer.framesFor(ramp, 1f, false, 1f, P);
        Grip g = new Grip(new int[][] {{0, 16, 32, 47}}, new int[][] {{24, 24, 24, 24}}, null);
        Check.eq(Check.hex(FrameEncoder.encode(15, g, Grip.empty())), Check.hex(f.get(0)), "ramp samples");
        // Transients: in their frame with their offset; amp-0 ones and the third in a frame are left out.
        Effect clicks = new Effect("c", "c", null, Fx.transients(new Effect.Transient(0.020, 0.5, 100),
            new Effect.Transient(0.022, 0.0, 100), new Effect.Transient(0.025, 1.0, 100), new Effect.Transient(0.028, 1.0, 100)), null);
        f = Renderer.framesFor(clicks, 1f, false, 1f, P);
        Check.eq(2, f.size(), "frames");
        Grip expected = new Grip(null, null, new int[][] {{32, 24, 5}, {63, 24, 10}});
        Check.eq(Check.hex(FrameEncoder.encode(15, expected, Grip.empty())), Check.hex(f.get(1)), "transients at 5 and 10 ms");
    }

    public static void mirrorSwapsGrips() {
        Effect e = new Effect("m", "m", null, Fx.grip(Fx.band(0, 0.03, 1.0, 100)), Fx.grip(Fx.band(0, 0.03, 0.5, 200)));
        int[] a = P.quantize(1.0, 100), b = P.quantize(0.5, 200), bHalf = P.quantize(0.25, 200);
        String straight = Check.hex(FrameEncoder.encode(15, Grip.tone(a[0], a[1]), Grip.tone(b[0], b[1])));
        String mirrored = Check.hex(FrameEncoder.encode(15, Grip.tone(b[0], b[1]), Grip.tone(a[0], a[1])));
        Check.eq(straight, Check.hex(Renderer.framesFor(e, 1f, false, 1f, P).get(0)), "as designed");
        Check.eq(mirrored, Check.hex(Renderer.framesFor(e, 1f, true, 1f, P).get(0)), "mirrored");
        // other-foot gain follows the effect's right grip to whichever side it goes
        String half = Check.hex(FrameEncoder.encode(15, Grip.tone(bHalf[0], bHalf[1]), Grip.tone(a[0], a[1])));
        Check.eq(half, Check.hex(Renderer.framesFor(e, 1f, true, 0.5f, P).get(0)), "mirrored, other foot at 0.5");
    }
}
