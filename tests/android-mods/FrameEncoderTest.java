package com.ducvd89.bloodborne;

import com.ducvd89.bloodborne.FrameEncoder.Grip;

public final class FrameEncoderTest {
    /** The two wire-verified reports from tests/test_kishi.py, zero-padded to 64 bytes. */
    public static void encodeMatchesPython() {
        String a = "021300010e3dfc57e2bf15f8a3f8afc57e2bf140";
        String b = "021300010e3da0c50628314183418a0c50628300";
        Check.eq(padHex(a), Check.hex(FrameEncoder.encode(30, Grip.tone(63, 10), Grip.tone(63, 10))), "63@10 frame");
        Check.eq(padHex(b), Check.hex(FrameEncoder.encode(30, Grip.tone(40, 24), Grip.tone(40, 24))), "40@24 frame");
        // test_empty_grips and test_razer_size_three_bands_one_transient
        Check.eq("3c00", Check.hex(FrameEncoder.frame(30, Grip.empty(), Grip.empty())), "empty grips");
        int[][] amp = {{10, 10, 10, 10}, {10, 10, 10, 10}, {10, 10, 10, 10}};
        int[][] idx = {{24, 24, 24, 24}, {24, 24, 24, 24}, {24, 24, 24, 24}};
        Grip g = new Grip(amp, idx, new int[][] {{63, 24, 0}});
        Check.eq(47, FrameEncoder.frame(60, g, g).length, "3 bands + 1 transient size");
    }

    /** A transient with amplitude 0 mutes the whole frame on the Kishi: it must not be encoded at all. */
    public static void zeroAmpTransientOmitted() {
        Grip with = new Grip(new int[][] {{40, 40, 40, 40}}, new int[][] {{24, 24, 24, 24}}, new int[][] {{0, 24, 3}, {50, 30, 7}});
        Grip without = new Grip(new int[][] {{40, 40, 40, 40}}, new int[][] {{24, 24, 24, 24}}, new int[][] {{50, 30, 7}});
        Check.eq(Check.hex(FrameEncoder.encode(15, without, Grip.empty())), Check.hex(FrameEncoder.encode(15, with, Grip.empty())),
            "amp-0 transient left out");
        Grip onlyZero = new Grip(null, null, new int[][] {{0, 24, 0}, {0, 24, 5}});
        Check.eq(Check.hex(FrameEncoder.encode(15, Grip.empty(), Grip.empty())), Check.hex(FrameEncoder.encode(15, onlyZero, onlyZero)),
            "only amp-0 transients = none");
        // Three transients of which one is empty still fit in two slots.
        Grip three = new Grip(null, null, new int[][] {{10, 1, 0}, {0, 1, 1}, {20, 2, 2}});
        FrameEncoder.encode(15, three, three);
    }

    public static void paddedTo64() {
        int[][] amp = {{63, 1, 2, 3}, {4, 5, 6, 7}, {8, 9, 10, 11}};
        int[][] idx = {{127, 126, 125, 124}, {0, 1, 2, 3}, {50, 51, 52, 53}};
        int[][] tr = {{63, 127, 127}, {1, 0, 0}};
        Grip full = new Grip(amp, idx, tr);
        byte[][] reports = {
            FrameEncoder.encode(127, full, full),
            FrameEncoder.encode(1, Grip.empty(), Grip.empty()),
            FrameEncoder.encode(30, Grip.tone(63, 10), Grip.empty()),
        };
        for (byte[] r : reports) {
            Check.eq(64, r.length, "report length");
            int frameLen = (r[1] & 0xff) - 4;
            Check.eq("02", String.format("%02x", r[0]), "report id");
            Check.eq("0e", String.format("%02x", r[4]), "stream command");
            for (int k = 5 + frameLen; k < 64; k++) Check.eq(0, r[k], "padding byte " + k);
        }
        // Largest possible frame: 7 + 2 x (3 x 53 + 2 x 21) bits = 409 bits = 52 bytes.
        Check.eq(52 + 4, reports[0][1] & 0xff, "largest frame length byte");
        Check.eq(64, FrameEncoder.pad64(new byte[] {2, 6, 0, 1, (byte) 0x90, 0, 0}).length, "pad64");
    }

    public static void rejectsOverflow() {
        expectThrows(() -> FrameEncoder.encode(128, Grip.empty(), Grip.empty()), "duration 128");
        expectThrows(() -> FrameEncoder.encode(30, Grip.tone(64, 10), Grip.empty()), "amp 64");
        expectThrows(() -> FrameEncoder.encode(30, Grip.tone(10, 128), Grip.empty()), "index 128");
        int[][] four = {{1, 1, 1, 1}, {1, 1, 1, 1}, {1, 1, 1, 1}, {1, 1, 1, 1}};
        expectThrows(() -> FrameEncoder.encode(30, new Grip(four, four, null), Grip.empty()), "4 bands");
        expectThrows(() -> FrameEncoder.encode(30, new Grip(null, null, new int[][] {{1, 1, 0}, {1, 1, 1}, {1, 1, 2}}), Grip.empty()),
            "3 transients");
    }

    public static void freqIndexMatchesPython() {
        Check.eq(9, FrameEncoder.freqIndex(59), "59 Hz");
        Check.eq(24, FrameEncoder.freqIndex(30 + 2.913385826771654 * 24 + 1e-9), "index 24");
        Check.eq(0, FrameEncoder.freqIndex(10), "below range");
        Check.eq(127, FrameEncoder.freqIndex(1000), "above range");
        Check.eq(25, FrameEncoder.freqIndex(105), "105 Hz");
    }

    static String padHex(String h) {
        StringBuilder s = new StringBuilder(h);
        while (s.length() < 128) s.append("00");
        return s.toString();
    }

    static void expectThrows(Runnable r, String what) {
        try {
            r.run();
        } catch (IllegalArgumentException e) {
            return;
        }
        throw new AssertionError(what + ": expected IllegalArgumentException");
    }
}
