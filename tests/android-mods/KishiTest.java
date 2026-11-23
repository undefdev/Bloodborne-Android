package com.ducvd89.bloodborne;

import java.util.Arrays;

/** KishiReports: the padded stream report. */
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
        System.exit(T.finish());
    }
}
