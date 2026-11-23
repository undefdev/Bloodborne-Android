package com.ducvd89.bloodborne;

/**
 * Razer Kishi V3 Pro HD haptics stream frames, the same bits as hapticsbench/kishi.py.
 *
 * Packed MSB first: 7-bit duration (ms), then per grip (left, then right) up to 3 bands of
 * [1] + 4 x (6-bit amplitude, 7-bit frequency index), closed with [0] if fewer than 3, then up to 2 transients
 * of [1] + 6-bit amplitude + 7-bit index + 7-bit time offset (ms), closed with [0] if fewer than 2.
 * The frame goes out as output report 2, [0x02][length from here on][0x00][fragment 1][0x0E][frame],
 * always padded to 64 bytes: shorter writes make the Kishi reject any later, larger frame until it is reset.
 * From hapticsBench's player (player/src/hb, d7d60f9), unchanged but for the package; plain Java, tested on the host.
 */
public final class FrameEncoder {
    public static final int REPORT_SIZE = 64, MAX_BANDS = 3, POINTS = 4, MAX_TRANSIENTS = 2, STREAM = 0x0E;
    public static final double FREQ_MIN = 30, FREQ_MAX = 400;

    private FrameEncoder() {}

    /** One grip's content for one frame. Null arrays mean none. */
    public static final class Grip {
        /** [band][4] amplitudes 0-63. */
        public final int[][] bandAmp;
        /** [band][4] frequency indices 0-127. */
        public final int[][] bandIdx;
        /** {amp 0-63, index 0-127, time offset ms 0-127}; entries with amp 0 are left out of the frame. */
        public final int[][] transients;

        public Grip(int[][] bandAmp, int[][] bandIdx, int[][] transients) {
            this.bandAmp = bandAmp == null ? new int[0][] : bandAmp;
            this.bandIdx = bandIdx == null ? new int[0][] : bandIdx;
            this.transients = transients == null ? new int[0][] : transients;
            if (this.bandAmp.length != this.bandIdx.length) throw new IllegalArgumentException("bandAmp and bandIdx differ in length");
        }

        public static Grip empty() {
            return new Grip(null, null, null);
        }

        /** One band holding the same (amplitude, index) on all four points. */
        public static Grip tone(int amp, int idx) {
            return new Grip(new int[][] {{amp, amp, amp, amp}}, new int[][] {{idx, idx, idx, idx}}, null);
        }

        boolean isEmpty() {
            if (bandAmp.length > 0) return false;
            for (int[] t : transients) if (t[0] != 0) return false;
            return true;
        }
    }

    /** Kishi frequency index for hz: min(127, trunc((clamp(hz, 30, 400) - 30) * 127 / 370)). */
    public static int freqIndex(double hz) {
        double f = Math.min(Math.max(hz, FREQ_MIN), FREQ_MAX);
        return Math.min(127, (int) ((f - FREQ_MIN) * 127 / (FREQ_MAX - FREQ_MIN)));
    }

    /** The 64-byte output report carrying one frame. */
    public static byte[] encode(int durationMs, Grip left, Grip right) {
        byte[] frame = frame(durationMs, left, right);
        byte[] r = new byte[REPORT_SIZE];
        r[0] = 0x02;
        r[1] = (byte) (frame.length + 4);
        r[2] = 0x00;
        r[3] = 0x01;
        r[4] = STREAM;
        System.arraycopy(frame, 0, r, 5, frame.length);
        return r;
    }

    /** The frame payload without the report header (what kishi.encode_frame returns). */
    public static byte[] frame(int durationMs, Grip left, Grip right) {
        Bits bits = new Bits();
        bits.put(durationMs, 7);
        for (Grip g : new Grip[] {left == null ? Grip.empty() : left, right == null ? Grip.empty() : right}) {
            if (g.bandAmp.length > MAX_BANDS) throw new IllegalArgumentException("at most 3 bands per grip");
            for (int b = 0; b < g.bandAmp.length; b++) {
                int[] amp = g.bandAmp[b], idx = g.bandIdx[b];
                if (amp.length != POINTS || idx.length != POINTS) throw new IllegalArgumentException("a band has exactly 4 points");
                bits.put(1, 1);
                for (int p = 0; p < POINTS; p++) {
                    bits.put(amp[p], 6);
                    bits.put(amp[p] != 0 ? idx[p] : 0, 7);
                }
            }
            if (g.bandAmp.length < MAX_BANDS) bits.put(0, 1);
            int written = 0;
            for (int[] t : g.transients) {
                if (t[0] == 0) continue; // an empty transient mutes the whole frame: leave it out
                if (++written > MAX_TRANSIENTS) throw new IllegalArgumentException("at most 2 transients per grip");
                bits.put(1, 1);
                bits.put(t[0], 6);
                bits.put(t[1], 7);
                bits.put(t[2], 7);
            }
            if (written < MAX_TRANSIENTS) bits.put(0, 1);
        }
        byte[] out = bits.bytes();
        if (out.length > REPORT_SIZE - 5) throw new IllegalArgumentException("frame too long for one report");
        return out;
    }

    /** A copy of r zero-padded to 64 bytes. */
    public static byte[] pad64(byte[] r) {
        if (r.length > REPORT_SIZE) throw new IllegalArgumentException("report longer than 64 bytes");
        byte[] out = new byte[REPORT_SIZE];
        System.arraycopy(r, 0, out, 0, r.length);
        return out;
    }

    private static final class Bits {
        private final byte[] buf = new byte[REPORT_SIZE];
        private int n;

        void put(int value, int count) {
            if (value < 0 || value >= (1 << count)) throw new IllegalArgumentException(value + " does not fit in " + count + " bits");
            for (int k = count - 1; k >= 0; k--, n++) {
                if (n >= buf.length * 8) throw new IllegalArgumentException("frame too long");
                if (((value >> k) & 1) != 0) buf[n / 8] |= (byte) (1 << (7 - n % 8));
            }
        }

        byte[] bytes() {
            byte[] out = new byte[(n + 7) / 8];
            System.arraycopy(buf, 0, out, 0, out.length);
            return out;
        }
    }
}
