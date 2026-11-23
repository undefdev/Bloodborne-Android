package com.ducvd89.bloodborne;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A library effect (immutable): time in seconds, amplitude 0-1 felt strength, frequency in Hz. For footsteps,
 * {@code left} is the stepping foot and {@code right} the other foot.
 * From hapticsBench's player (player/src/hb, d7d60f9), unchanged but for the package; plain Java, tested on the host.
 */
public final class Effect {
    public final String id, name;
    /** Null when the effect has no sound. */
    public final Sound sound;
    public final Grip left, right;

    public Effect(String id, String name, Sound sound, Grip left, Grip right) {
        this.id = id;
        this.name = name == null ? id : name;
        this.sound = sound;
        this.left = left == null ? Grip.EMPTY : left;
        this.right = right == null ? Grip.EMPTY : right;
    }

    /** End of the last band point or transient, in seconds (0 for an empty effect). */
    public double length() {
        return Math.max(left.length(), right.length());
    }

    public static final class Sound {
        /** Relative to the library, e.g. "sounds/foot-wood-r1.wav". */
        public final String file;
        public final float gain;
        public final int offsetMs;

        public Sound(String file, float gain, int offsetMs) {
            this.file = file;
            this.gain = gain;
            this.offsetMs = offsetMs;
        }
    }

    public static final class Grip {
        public static final Grip EMPTY = new Grip(null, null);
        /** At most 3 (more are ignored by the renderer). */
        public final List<Band> bands;
        public final List<Transient> transients;

        public Grip(List<Band> bands, List<Transient> transients) {
            this.bands = bands == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(bands));
            List<Transient> tr = transients == null ? new ArrayList<>() : new ArrayList<>(transients);
            tr.sort((a, b) -> Double.compare(a.t, b.t));
            this.transients = Collections.unmodifiableList(tr);
        }

        double length() {
            double end = 0;
            for (Band b : bands) if (b.t.length > 0) end = Math.max(end, b.t[b.t.length - 1]);
            for (Transient tr : transients) end = Math.max(end, tr.t);
            return end;
        }

        boolean isEmpty() {
            return bands.isEmpty() && transients.isEmpty();
        }
    }

    /** Points ordered by t, linearly interpolated, zero outside the first and last point. */
    public static final class Band {
        public final double[] t, amp, hz;

        public Band(double[] t, double[] amp, double[] hz) {
            if (t.length != amp.length || t.length != hz.length) throw new IllegalArgumentException("band arrays differ in length");
            this.t = t.clone();
            this.amp = amp.clone();
            this.hz = hz.clone();
        }

        /** {amp, hz} at time s; amp is 0 outside the band. */
        double[] at(double s) {
            int n = t.length;
            if (n == 0 || s < t[0] || s > t[n - 1]) return new double[] {0, n == 0 ? 0 : hz[0]};
            for (int k = 1; k < n; k++) {
                if (s <= t[k]) {
                    double span = t[k] - t[k - 1];
                    double f = span <= 0 ? 1 : (s - t[k - 1]) / span;
                    return new double[] {amp[k - 1] + (amp[k] - amp[k - 1]) * f, hz[k - 1] + (hz[k] - hz[k - 1]) * f};
                }
            }
            return new double[] {amp[n - 1], hz[n - 1]}; // n == 1 and s == t[0]
        }
    }

    public static final class Transient {
        public final double t, amp, hz;

        public Transient(double t, double amp, double hz) {
            this.t = t;
            this.amp = amp;
            this.hz = hz;
        }
    }
}
