package com.ducvd89.bloodborne;

/** The Kishi V3 Pro's USB reports (KishiHaptics), plain Java so the host tests cover them (tests/android-mods/KishiTest.java).
 *  - Haptics interface 4, endpoint 0x04: Protocol 2.5 stream reports, always zero-padded to REPORT_SIZE bytes; the length
 *    byte keeps the real length. After a shorter write the Kishi (firmware 2.1) rejects every frame larger than the last one
 *    it accepted until it is reset, so Razer's own app pads too.
 *  - Control interface 3 ("Razer Protocol2.5"): DeviceReset, a feature report. */
final class KishiReports {
    static final int REPORT_SIZE = 64;
    static final int FRAME_MS = 30, SLOTS = 4; // a frame plays four (amplitude, frequency) slots per grip
    /** DeviceReset's report size, and the first byte of its answer: busy (read again) or done. */
    static final int RESET_SIZE = 90, RESET_BUSY = 1, RESET_OK = 2;

    private KishiReports() {}

    /** (amplitude, frequency) per frame -> the same per slot. */
    static int[] slots(int... perFrame) {
        int[] out = new int[perFrame.length * SLOTS];
        for (int i = 0; i < out.length; i += 2) {
            out[i] = perFrame[i / (SLOTS * 2) * 2];
            out[i + 1] = perFrame[i / (SLOTS * 2) * 2 + 1];
        }
        return out;
    }

    // Protocol 2.5 stream report, based on Mokomis/rumblebridge (MIT).
    static byte[] frame(int large, int small) {
        return frame(slots(large * 63 / 255, 10), slots(small * 63 / 255, 35));
    }

    /** Left and right grips: SLOTS (amplitude 0-63, frequency 0-127) pairs each. A 20-byte report padded to REPORT_SIZE. */
    static byte[] frame(int[] left, int[] right) {
        byte[] report = new byte[REPORT_SIZE];
        report[0] = 2;
        report[1] = 19; // the length from here on, without the padding
        report[3] = 1;
        report[4] = 0x0e;
        putBits(report, 40, FRAME_MS, 7);
        band(report, 47, left);
        band(report, 102, right);
        return report;
    }

    private static void band(byte[] report, int bit, int[] slots) {
        putBits(report, bit++, 1, 1);
        for (int i = 0; i < SLOTS; i++, bit += 13) {
            int level = slots[i * 2];
            putBits(report, bit, level, 6);
            putBits(report, bit + 6, level == 0 ? 0 : slots[i * 2 + 1], 7);
        }
    }

    private static void putBits(byte[] report, int bit, int value, int count) {
        for (int i = count - 1; i >= 0; i--, bit++) {
            if (((value >> i) & 1) != 0) report[bit / 8] |= 1 << (7 - bit % 8);
        }
    }

    /** DeviceReset: data size 0 at [5], command class 0x00 at [6], command id 0x0B at [7], XOR of bytes 2..87 at [88].
     *  Sent with SET_REPORT (feature report 0, interface 3); the Kishi answers RESET_OK and re-enumerates about 1 s later. */
    static byte[] deviceReset() {
        byte[] report = new byte[RESET_SIZE];
        report[5] = 0x00;
        report[6] = 0x00;
        report[7] = 0x0B;
        byte check = 0;
        for (int i = 2; i < 88; i++) check ^= report[i];
        report[88] = check;
        return report;
    }
}
