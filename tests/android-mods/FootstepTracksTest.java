package com.ducvd89.bloodborne;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

final class FootstepTracksTest {
    private static FootstepTracks parse(String text) throws IOException {
        return FootstepTracks.parse(new BufferedReader(new StringReader(text)));
    }

    public static void main(String[] args) {
        T.test("material -> surface -> gait", () -> {
            FootstepTracks t = parse("# c\nm 4 wood\nm 6 gravel\nt wood walk 10:15 20:16\nt wood run 30:17\nt gravel walk 40:40\n");
            T.yes(Arrays.equals(t.pick(4, false), new int[]{10, 15, 20, 16}), "wood walk");
            T.yes(Arrays.equals(t.pick(4, true), new int[]{30, 17}), "wood run");
            T.yes(Arrays.equals(t.pick(6, true), new int[]{40, 40}), "a missing gait uses the other one");
            T.yes(t.pick(9, false) == null, "unknown material");
        });
        T.test("values are clamped", () -> {
            int[] track = parse("m 1 stone\nt stone walk 99:200 -5:-1\n").pick(1, false);
            T.yes(Arrays.equals(track, new int[]{63, 127, 0, 0}), Arrays.toString(track));
        });
        T.test("no repeat of the last variant", () -> {
            FootstepTracks t = parse("m 1 stone\nt stone walk 1:1\nt stone walk 2:2\nt stone walk 3:3\n");
            int[] last = t.pick(1, false);
            for (int i = 0; i < 200; i++) {
                int[] next = t.pick(1, false);
                T.yes(next != last, "repeated at " + i);
                last = next;
            }
        });
        T.test("no tracks or a bad pair is refused", () -> {
            T.yes(throwsOn("m 1 stone\n"), "no tracks");
            T.yes(throwsOn("m 1 stone\nt stone walk 1-2\n"), "bad pair");
        });
        T.test("load: a missing file is null", () -> {
            File dir = T.temp();
            T.yes(FootstepTracks.load(dir) == null, "missing");
            File file = new File(dir, FootstepTracks.FILE);
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), "m 3 soil\nt soil walk 5:9\n".getBytes(StandardCharsets.US_ASCII));
            T.yes(Arrays.equals(FootstepTracks.load(dir).pick(3, true), new int[]{5, 9}), "loaded");
            Files.write(file.toPath(), "garbage".getBytes(StandardCharsets.US_ASCII));
            T.yes(FootstepTracks.load(dir) == null, "unparsable");
        });
        System.exit(T.finish());
    }

    private static boolean throwsOn(String text) {
        try { parse(text); return false; } catch (IOException | RuntimeException e) { return true; }
    }
}
