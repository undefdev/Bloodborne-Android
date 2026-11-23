package com.ducvd89.bloodborne;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test effects. */
final class Fx {
    private Fx() {}

    static Effect.Band band(double t0, double t1, double amp, double hz) {
        return new Effect.Band(new double[] {t0, t1}, new double[] {amp, amp}, new double[] {hz, hz});
    }

    static Effect.Grip grip(Effect.Band... bands) {
        return new Effect.Grip(Arrays.asList(bands), null);
    }

    static Effect.Grip transients(Effect.Transient... trs) {
        return new Effect.Grip(null, Arrays.asList(trs));
    }

    /** Left grip: one constant band; right grip empty. */
    static Effect left(String id, double amp, double hz, double length) {
        return new Effect(id, id, null, grip(band(0, length, amp, hz)), null);
    }

    static Effect withSound(Effect e, String file, float gain, int offsetMs) {
        return new Effect(e.id, e.name, new Effect.Sound(file, gain, offsetMs), e.left, e.right);
    }

    static List<String> hexes(List<byte[]> frames) {
        List<String> out = new ArrayList<>();
        for (byte[] f : frames) out.add(f == null ? null : Check.hex(f));
        return out;
    }
}
