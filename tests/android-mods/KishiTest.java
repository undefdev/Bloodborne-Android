package com.ducvd89.bloodborne;

import java.util.Arrays;

/** KishiReports (the padded stream report, DeviceReset) and KishiIdleReset (when the Kishi is reset). */
final class KishiTest {
    private static String hex(byte[] b, int from, int to) {
        StringBuilder s = new StringBuilder();
        for (int i = from; i < to; i++) s.append(String.format("%02x", b[i]));
        return s.toString();
    }

    /** The report holds the unpadded 20 bytes (as the encoder wrote them before padding) followed by zeros to 64. */
    private static void padded(byte[] report, String unpadded) {
        T.eq(KishiReports.REPORT_SIZE, report.length, "padded to 64 bytes");
        T.eq(unpadded, hex(report, 0, 20), "frame content");
        T.eq(19, (int) report[1], "length byte: the real length");
        for (int i = 20; i < report.length; i++) T.eq(0, (int) report[i], "padding at " + i);
    }

    public static void main(String[] args) {
        // Expected bytes: the 20-byte reports of the encoder before padding (KishiHaptics at 7e65802).
        T.test("rumble frames are padded, content unchanged", () -> {
            padded(KishiReports.frame(0, 0), "021300010e3d0000000000000200000000000000");
            padded(KishiReports.frame(170, 170), "021300010e3da85542aa1550a3523a91d48ea460");
            padded(KishiReports.frame(255, 255), "021300010e3dfc57e2bf15f8a3fa3fd1fe8ff460");
            padded(KishiReports.frame(200, 0), "021300010e3dc45622b11588a200000000000000");
        });
        T.test("footstep frames are padded, content unchanged", () -> {
            padded(KishiReports.frame(new int[]{63, 127, 20, 35, 0, 99, 1, 1}, new int[]{8, 10, 8, 10, 0, 0, 0, 0}),
                "021300010e3dfffa88c000081240a20500000000");
        });
        T.test("slots repeat each frame's pair per slot", () -> {
            T.yes(Arrays.equals(KishiReports.slots(20, 35), new int[]{20, 35, 20, 35, 20, 35, 20, 35}));
            T.eq(16, KishiReports.slots(1, 2, 3, 4).length);
        });
        T.test("DeviceReset report", () -> {
            byte[] r = KishiReports.deviceReset();
            T.eq(90, r.length);
            T.eq(0, (int) r[5], "size");
            T.eq(0, (int) r[6], "class");
            T.eq(0x0B, (int) r[7], "id");
            byte x = 0;
            for (int i = 2; i < 88; i++) x ^= r[i];
            T.eq((int) x, (int) r[88], "XOR of bytes 2..87");
            T.eq(0x0B, (int) r[88]);
            for (int i = 0; i < r.length; i++) if (i != 7 && i != 88) T.eq(0, (int) r[i], "byte " + i);
        });

        final long min = 60_000L;
        T.test("the first input resets once, only while claimed", () -> {
            KishiIdleReset idle = new KishiIdleReset();
            T.eq(null, idle.input(1000, false), "not claimed yet");
            T.eq(null, idle.input(2000, false), "still not claimed");
            T.eq("first controller input", idle.input(3000, true), "first input after the claim");
            T.eq(null, idle.input(3100, true), "done");
            T.eq(null, idle.input(5000, true), "the reclaim after the reset asks for nothing");
        });
        T.test("the first input after 10 minutes without resets", () -> {
            KishiIdleReset idle = new KishiIdleReset();
            T.yes(idle.input(0, true) != null, "first");
            T.eq(null, idle.input(9 * min, true), "9 min");
            T.eq(null, idle.input(18 * min, true), "9 min again: the gap counts from the last input");
            T.eq("first controller input after 10 min without", idle.input(28 * min, true));
            T.eq(null, idle.input(28 * min + 50, true), "the next input");
            T.yes(idle.input(28 * min + 50 + KishiIdleReset.IDLE_MS, true) != null, "exactly IDLE_MS later");
        });
        T.test("a reset due while not claimed waits for an input while claimed", () -> {
            KishiIdleReset idle = new KishiIdleReset();
            idle.input(0, true);
            T.eq(null, idle.input(15 * min, false), "idle, but the interface is not held");
            T.eq(null, idle.input(15 * min + 10, false), "still not held");
            T.eq("first controller input after 15 min without", idle.input(15 * min + 20, true), "claimed again");
            T.eq(null, idle.input(15 * min + 30, true));
        });
        T.test("controller motion: sticks and triggers past 0.2, the d-pad hat", () -> {
            T.no(KishiIdleReset.active(0, 0, 0, 0, 0, 0, 0, 0), "at rest");
            T.no(KishiIdleReset.active(0.1f, 0.15f, -0.1f, 0.1f, 0.1f, 0.19f, 0.2f, -0.2f), "noise");
            T.yes(KishiIdleReset.active(0.2f, 0, 0, 0, 0, 0, 0, 0), "left stick x");
            T.yes(KishiIdleReset.active(0.15f, -0.15f, 0, 0, 0, 0, 0, 0), "left stick diagonal");
            T.yes(KishiIdleReset.active(0, 0, 0, -0.3f, 0, 0, 0, 0), "right stick y");
            T.yes(KishiIdleReset.active(0, 0, 0, 0, 0.5f, 0, 0, 0), "left trigger");
            T.yes(KishiIdleReset.active(0, 0, 0, 0, 0, 1, 0, 0), "right trigger");
            T.yes(KishiIdleReset.active(0, 0, 0, 0, 0, 0, -1, 0), "hat left");
            T.yes(KishiIdleReset.active(0, 0, 0, 0, 0, 0, 0, 1), "hat down");
        });
        System.exit(T.finish());
    }
}
