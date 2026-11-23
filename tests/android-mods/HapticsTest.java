package com.ducvd89.bloodborne;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** The workbench's design in the game: HapticsTable (haptics.json), GameHaptics (sound rules, feet, channels, the rumble
 *  translation and its mutes), the Scheduler's held frames, HapticEvent (the event pipe's lines).
 *  Optional: BB_TEST_HAPTICS=<a haptics.json export> plays every rule of a real export once. */
final class HapticsTest {
    static final long MS = 1_000_000L;

    /** Frames as the Kishi would get them, with their times. */
    static final class Sink implements Scheduler.Sink {
        final List<byte[]> frames = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
        long now;
        @Override public void frame(byte[] report) { frames.add(report); times.add(now); }
        @Override public void sound(String file, float gain) {}
        List<String> hexes() { return Fx.hexes(frames); }
        void clear() { frames.clear(); times.clear(); }
    }

    static void run(Scheduler s, Sink sink, long fromNs, long toNs) {
        for (long t = fromNs; t <= toNs; t += MS) {
            sink.now = t;
            s.poll(t);
        }
    }

    // A flat profile (felt amplitude = drive) keeps the expected values simple; the real one is tested with the export.
    static final String TABLE = "{\"version\":1,\"generator\":\"test\",\"profile\":{\"response\":[[30,1.0],[400,1.0]]},"
        + "\"strength\":{\"steps\":0.5,\"events\":1.0},"
        + "\"effects\":{"
        + "\"w1\":{\"left\":{\"bands\":[[[0,0.8,100],[0.03,0.8,100]]],\"transients\":[]},\"right\":{\"bands\":[[[0,0.2,200],[0.03,0.2,200]]],\"transients\":[]}},"
        + "\"w2\":{\"left\":{\"bands\":[[[0.03,0.6,110],[0,0.6,110]]],\"transients\":[[0,0.9,100]]},\"right\":{\"bands\":[[[0,0.3,210],[0.03,0.3,210]]],\"transients\":[]}},"
        + "\"hit\":{\"left\":{\"bands\":[[[0,1.0,60],[0.045,1.0,60]]],\"transients\":[[0,1.0,100]]},\"right\":{\"bands\":[],\"transients\":[]}},"
        + "\"s1\":{\"left\":{\"bands\":[[[0,0.5,150],[0.06,0.5,150]]],\"transients\":[]}},"
        + "\"s2\":{\"left\":{\"bands\":[[[0,0.5,160],[0.06,0.5,160]]],\"transients\":[]}},"
        + "\"s3\":{\"right\":{\"bands\":[[[0,0.5,170],[0.06,0.5,170]]],\"transients\":[]}}},"
        + "\"groups\":{\"wood/walk\":[\"w1\",\"w2\"],\"hit/impact\":[\"hit\"],\"punch/swing\":[\"s1\",\"s2\",\"s3\"],\"gone\":[\"missing\"]},"
        + "\"sounds\":{\"c000001004\":{\"group\":\"wood/walk\",\"kind\":\"step\",\"channel\":\"step\"},"
        + "\"z000001058\":{\"group\":\"hit/impact\",\"kind\":\"event\",\"channel\":\"hit\"},"
        + "\"c000000750\":{\"group\":\"punch/swing\",\"kind\":\"event\",\"channel\":\"punch\"},"
        + "\"c000000751\":{\"group\":\"punch/swing\",\"kind\":\"event\",\"channel\":\"punch\"},"
        + "\"o240104000\":{\"group\":\"gone\",\"kind\":\"event\",\"channel\":\"world\"}},"
        + "\"rumble\":{\"translate\":{\"strength\":0.6,\"curve\":0.8,\"heavy_hz\":65,\"light_hz\":140,\"heavy_scale\":1.0,\"light_scale\":1.0,\"spread\":0.3},"
        + "\"mute\":[1,2]}}";

    static HapticsTable table() throws Exception { return HapticsTable.parse(TABLE); }

    static String invalid(String json) {
        try { HapticsTable.parse(json); } catch (HapticsTable.Invalid e) { return e.getMessage(); }
        throw new AssertionError("accepted: " + json);
    }

    /** The frames one effect sends alone. */
    static List<String> alone(HapticsTable t, String id, float strength, boolean mirror) {
        return Fx.hexes(Renderer.framesFor(t.effects.get(id), strength, mirror, 1f, t.profile));
    }

    static String encode(FrameEncoder.Grip[] g) { return Check.hex(FrameEncoder.encode(Renderer.FRAME_MS, g[0], g[1])); }

    public static void main(String[] args) {
        T.test("haptics.json: effects, groups, rules, strengths, rumble, profile", () -> {
            HapticsTable t = table();
            T.eq(6, t.effects.size());
            T.eq(Arrays.asList("w1", "w2"), t.groups.get("wood/walk"));
            T.eq(Collections.emptyList(), t.groups.get("gone"), "ids without an effect are left out");
            T.eq(4, t.sounds.size(), "the rule whose group has no effect is dropped");
            T.eq(1, t.droppedRules);
            T.yes(t.sounds.get("c000001004").step());
            T.eq("hit", t.sounds.get("z000001058").channel);
            T.eq(0.5f, t.stepStrength);
            T.eq(1.0f, t.eventStrength);
            T.eq(65.0, t.translate.heavyHz);
            T.eq(0.3, t.translate.spread);
            T.eq(new HashSet<>(Arrays.asList(1, 2)), t.mute);
            T.eq(1.0, t.profile.response(250));
            Effect w2 = t.effects.get("w2");
            T.eq(0.0, w2.left.bands.get(0).t[0], "band points sorted by time");
            T.eq(1, w2.left.transients.size());
            T.yes(t.effects.get("s1").right.bands.isEmpty(), "a missing grip is empty");
            T.contains(t.summary(), "6 effects, 4 groups, 4 sound rules (1 steps, 3 events, 1 without effects dropped)");
        });
        T.test("haptics.json: defaults for what is left out", () -> {
            HapticsTable t = HapticsTable.parse("{\"version\":1,\"effects\":{\"a\":{\"left\":{\"bands\":[[[0,0.5,100],[0.03,0.5,100]]]}}},"
                + "\"groups\":{\"g\":[\"a\"]},\"sounds\":{\"c000000001\":{\"group\":\"g\",\"kind\":\"event\"}}}");
            T.eq(1.0f, t.stepStrength);
            T.eq(1.0f, t.eventStrength);
            T.eq(0.6, t.translate.strength);
            T.eq(140.0, t.translate.lightHz);
            T.yes(t.mute.isEmpty());
            T.yes(t.profile == Profile.DEFAULT);
            T.eq("g", t.sounds.get("c000000001").channel, "no channel: the group's own");
        });
        T.test("haptics.json: what makes it invalid (the app then plays as before)", () -> {
            T.contains(invalid("nope"), "not JSON");
            T.contains(invalid("[]"), "not an object");
            T.contains(invalid(TABLE.replace("\"version\":1", "\"version\":2")), "version 2");
            T.contains(invalid("{\"version\":1,\"effects\":[],\"groups\":{},\"sounds\":{}}"), "effects is not an object");
            T.contains(invalid(TABLE.replace("[0.03,0.8,100]", "[0.03,0.8]")), "is not [t, amp, hz]");
            T.contains(invalid(TABLE.replace("\"kind\":\"step\"", "\"kind\":\"walk\"")), "kind walk");
            T.contains(invalid(TABLE.replace("\"steps\":0.5", "\"steps\":\"x\"")), "strength.steps is not a number");
            T.contains(invalid("{\"version\":1,\"effects\":{},\"groups\":{\"g\":[\"a\"]},\"sounds\":{\"c000000001\":{\"group\":\"g\",\"kind\":\"event\"}}}"),
                "no sound rule has an effect");
            File dir = T.temp();
            try {
                HapticsTable.load(dir);
                throw new AssertionError("loaded without a file");
            } catch (HapticsTable.Invalid e) { T.eq("not in the game folder", e.getMessage()); }
            T.write(new File(dir, HapticsTable.FILE), TABLE);
            T.eq(4, HapticsTable.load(dir).sounds.size(), "loaded from <game>/haptics/haptics.json");
            T.delete(dir);
        });

        T.test("steps alternate feet, left first after a pause, mirrored on the right foot, at strength.steps", () -> {
            HapticsTable t = table();
            Sink sink = new Sink();
            Scheduler s = new Scheduler(sink);
            GameHaptics g = new GameHaptics(t, s, new Random(7));
            long[] at = {0, 400, 800, 1200, 2300, 2700, 3700, 4800};
            String[] feet = {"left", "right", "left", "right", "left", "right", "left", "left"};
            for (int i = 0; i < at.length; i++) {
                sink.clear();
                GameHaptics.Played p = g.sound("c000001004", at[i] * MS);
                T.eq(feet[i], p.foot, "step " + i + " at " + at[i] + " ms");
                T.eq(0.5f, p.strength);
                T.eq("step", p.channel);
                run(s, sink, at[i] * MS, (at[i] + 100) * MS);
                T.eq(alone(t, p.effect, 0.5f, "right".equals(p.foot)), sink.hexes(), "step " + i + ": the effect, mirrored on the right foot");
            }
            T.no(alone(t, "w1", 0.5f, false).equals(alone(t, "w1", 0.5f, true)), "mirroring changes the frames");
        });
        T.test("events play as designed at strength.events; unknown sounds play nothing", () -> {
            HapticsTable t = table();
            Sink sink = new Sink();
            Scheduler s = new Scheduler(sink);
            GameHaptics g = new GameHaptics(t, s, new Random(1));
            GameHaptics.Played p = g.sound("z000001058", 0);
            T.eq(null, p.foot);
            T.eq(1.0f, p.strength);
            run(s, sink, 0, 100 * MS);
            T.eq(alone(t, "hit", 1.0f, false), sink.hexes());
            T.eq(null, g.sound("c000009999", 200 * MS));
            T.eq(null, g.sound("o240104000", 200 * MS), "a dropped rule");
            T.yes(s.idle());
        });
        T.test("a group's pick never repeats the last one", () -> {
            GameHaptics g = new GameHaptics(table(), new Scheduler(new Sink()), new Random(3));
            String last = null;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 300; i++) {
                String id = g.sound(i % 2 == 0 ? "c000000750" : "c000000751", i * 1000 * MS).effect; // two sounds, one group
                T.no(id.equals(last), "pick " + i + " repeats " + id);
                seen.add(id);
                last = id;
            }
            T.eq(new HashSet<>(Arrays.asList("s1", "s2", "s3")), seen);
            GameHaptics two = new GameHaptics(table(), new Scheduler(new Sink()), new Random(5));
            String a = two.sound("c000001004", 0).effect, b = two.sound("c000001004", 300 * MS).effect, c = two.sound("c000001004", 600 * MS).effect;
            T.no(a.equals(b));
            T.eq(a, c, "two members alternate");
        });
        T.test("the same channel replaces, other channels layer", () -> {
            HapticsTable t = table();
            Sink sink = new Sink();
            Scheduler s = new Scheduler(sink);
            GameHaptics g = new GameHaptics(t, s, new Random(2));
            String first = g.sound("c000000750", 0).effect;
            run(s, sink, 0, 20 * MS);
            String second = g.sound("c000000751", 20 * MS).effect;
            run(s, sink, 21 * MS, 200 * MS);
            List<String> got = sink.hexes(), tail = alone(t, second, 1f, false);
            T.eq(tail, got.subList(got.size() - tail.size(), got.size()), "the punch channel's new swing alone");
            T.eq(alone(t, first, 1f, false).subList(0, 2), got.subList(0, 2), "the first swing until then");

            sink.clear();
            Scheduler s2 = new Scheduler(sink);
            GameHaptics g2 = new GameHaptics(t, s2, new Random(2));
            String step = g2.sound("c000001004", 0).effect;
            run(s2, sink, 0, 10 * MS);
            g2.sound("z000001058", 10 * MS);
            run(s2, sink, 11 * MS, 200 * MS);
            FrameEncoder.Grip[] a = Renderer.gripsFor(t.effects.get(step), 0.5f, false, 1f, t.profile).get(1);
            FrameEncoder.Grip[] b = Renderer.gripsFor(t.effects.get("hit"), 1f, false, 1f, t.profile).get(0);
            String mixed = Check.hex(FrameEncoder.encode(15, Renderer.mix(Arrays.asList(a[0], b[0])), Renderer.mix(Arrays.asList(a[1], b[1]))));
            T.eq(mixed, sink.hexes().get(1), "the step's second frame and the hit's first, mixed, at 15 ms");
            T.eq(Long.valueOf(15 * MS), sink.times.get(1), "on the step's clock");
            List<String> hitAlone = alone(t, "hit", 1f, false);
            T.eq(hitAlone.subList(1, hitAlone.size()), sink.hexes().subList(2, sink.frames.size()), "then the hit alone");
        });

        T.test("rumble translation: as hapticsbench/rumble.py translate() and device.quantize", () -> {
            HapticsTable.Translate d = HapticsTable.Translate.DEFAULT;
            Profile p = Profile.DEFAULT;
            // Expected {amp63, index} per band (main motor first), from the workbench's Python with its default profile.
            int[][][] want = {
                /* 255,0   */ {{63, 12}}, {{25, 12}},
                /* 0,255   */ {{14, 37}}, {{47, 37}},
                /* 128,64  */ {{47, 12}, {5, 37}}, {{16, 37}, {14, 12}},
                /* 147,229 */ {{53, 12}, {13, 37}}, {{43, 37}, {16, 12}},
                /* 10,3    */ {{6, 12}}, {{1, 37}, {2, 12}},
                /* 255,255 */ {{63, 12}, {14, 37}}, {{47, 37}, {25, 12}},
            };
            int[][] motors = {{255, 0}, {0, 255}, {128, 64}, {147, 229}, {10, 3}, {255, 255}};
            for (int m = 0; m < motors.length; m++) {
                FrameEncoder.Grip[] g = GameHaptics.translate(d, p, motors[m][0], motors[m][1]);
                for (int side = 0; side < 2; side++) {
                    int[][] bands = want[m * 2 + side];
                    String where = motors[m][0] + "," + motors[m][1] + (side == 0 ? " left" : " right");
                    T.eq(bands.length, g[side].bandAmp.length, where + ": bands");
                    for (int b = 0; b < bands.length; b++) {
                        T.yes(Arrays.equals(new int[] {bands[b][0], bands[b][0], bands[b][0], bands[b][0]}, g[side].bandAmp[b]), where + " amp " + Arrays.toString(g[side].bandAmp[b]));
                        T.yes(Arrays.equals(new int[] {bands[b][1], bands[b][1], bands[b][1], bands[b][1]}, g[side].bandIdx[b]), where + " index");
                    }
                }
            }
            T.eq(0.3457, GameHaptics.amp(d, 128 / 255.0, 1, 1), "amp rounded as rumble.py");
            T.eq(0.0, GameHaptics.amp(new HapticsTable.Translate(0.6, 0.8, 65, 140, 1, 1, 0), 1, 1, 0), "spread 0: no band");
        });
        T.test("rumble: a continuous tone on its own channel while the motors run, layered with sounds", () -> {
            HapticsTable t = table();
            Sink sink = new Sink();
            Scheduler s = new Scheduler(sink);
            GameHaptics g = new GameHaptics(t, s, new Random(1));
            g.motors(200, 100, 3 * MS);
            run(s, sink, 3 * MS, 100 * MS);
            String tone = encode(GameHaptics.translate(t.translate, t.profile, 200, 100));
            T.eq(7, sink.frames.size(), "a frame every 15 ms");
            for (int i = 0; i < sink.frames.size(); i++) {
                T.eq(tone, sink.hexes().get(i));
                T.eq(Long.valueOf((3 + 15 * i) * MS), sink.times.get(i));
            }
            sink.clear();
            g.motors(100, 0, 101 * MS);
            run(s, sink, 101 * MS, 110 * MS);
            T.eq(encode(GameHaptics.translate(t.translate, t.profile, 100, 0)), sink.hexes().get(0), "new values at the next tick");
            T.eq(Long.valueOf(108 * MS), sink.times.get(0), "the clock goes on");
            sink.clear();
            g.sound("z000001058", 111 * MS);
            run(s, sink, 111 * MS, 125 * MS);
            FrameEncoder.Grip[] hit = Renderer.gripsFor(t.effects.get("hit"), 1f, false, 1f, t.profile).get(0);
            FrameEncoder.Grip[] rumble = GameHaptics.translate(t.translate, t.profile, 100, 0);
            T.eq(Check.hex(FrameEncoder.encode(15, Renderer.mix(Arrays.asList(hit[0], rumble[0])), Renderer.mix(Arrays.asList(hit[1], rumble[1])))),
                sink.hexes().get(0), "a hit over the rumble: mixed");
            g.motors(0, 0, 130 * MS);
            run(s, sink, 126 * MS, 300 * MS);
            sink.clear();
            run(s, sink, 300 * MS, 400 * MS);
            T.eq(0, sink.frames.size(), "motors at 0: nothing");
            T.yes(s.idle());
        });
        T.test("rumble: muted between R+ and R- of a mute id", () -> {
            HapticsTable t = table();
            Sink sink = new Sink();
            Scheduler s = new Scheduler(sink);
            GameHaptics g = new GameHaptics(t, s, new Random(1));
            g.motors(200, 100, 0);
            g.rumble(true, 0, 0); // not a mute id
            run(s, sink, 0, 50 * MS);
            T.eq(4, sink.frames.size(), "rumble 0 plays its translation");
            g.rumble(true, 1, 51 * MS);
            T.yes(g.muted(51 * MS));
            sink.clear();
            g.motors(150, 80, 60 * MS);
            run(s, sink, 51 * MS, 200 * MS);
            T.eq(0, sink.frames.size(), "rumble 1 runs: the translation is off");
            g.rumble(true, 2, 201 * MS);
            g.rumble(true, 1, 202 * MS); // the same id again (another queue slot)
            g.rumble(false, 1, 203 * MS);
            g.rumble(false, 2, 204 * MS);
            run(s, sink, 201 * MS, 250 * MS);
            T.eq(0, sink.frames.size(), "one rumble 1 still runs");
            g.rumble(false, 7, 251 * MS); // an end without a start: nothing
            g.rumble(false, 1, 252 * MS);
            T.no(g.muted(252 * MS));
            run(s, sink, 251 * MS, 300 * MS);
            T.yes(sink.frames.size() >= 3, "back after the last R-");
            T.eq(encode(GameHaptics.translate(t.translate, t.profile, 150, 80)), sink.hexes().get(0), "with the motors' current values");
            g.rumble(false, 1, 301 * MS); // a stray R-
            T.no(g.muted(301 * MS));
            // An R- that never comes: the mute ends after MUTE_MAX_NS.
            g.rumble(true, 2, 400 * MS);
            T.yes(g.muted(400 * MS + GameHaptics.MUTE_MAX_NS));
            T.no(g.muted(401 * MS + GameHaptics.MUTE_MAX_NS));
        });
        T.test("silence stops effects and the rumble; the mutes stay", () -> {
            HapticsTable t = table();
            Sink sink = new Sink();
            Scheduler s = new Scheduler(sink);
            GameHaptics g = new GameHaptics(t, s, new Random(1));
            g.motors(200, 100, 0);
            g.sound("z000001058", 0);
            g.rumble(true, 1, 0);
            run(s, sink, 0, 10 * MS);
            g.silence();
            T.yes(s.idle());
            sink.clear();
            run(s, sink, 11 * MS, 200 * MS);
            T.eq(0, sink.frames.size());
            T.yes(g.muted(200 * MS));
        });
        T.test("rendered ahead: the same frames as rendered on demand", () -> {
            HapticsTable t = table();
            Sink a = new Sink(), b = new Sink();
            Scheduler sa = new Scheduler(a), sb = new Scheduler(b);
            GameHaptics ga = new GameHaptics(t, sa, new Random(4)), gb = new GameHaptics(t, sb, new Random(4));
            T.eq(8, ga.prepare(), "w1 and w2 for both feet, hit, s1-s3");
            String[] sounds = {"c000001004", "c000001004", "z000001058", "c000000750", "c000001004"};
            for (int i = 0; i < sounds.length; i++) {
                ga.sound(sounds[i], i * 300 * MS);
                gb.sound(sounds[i], i * 300 * MS);
                run(sa, a, i * 300 * MS, (i * 300 + 299) * MS);
                run(sb, b, i * 300 * MS, (i * 300 + 299) * MS);
            }
            T.eq(b.hexes(), a.hexes());
            T.yes(a.frames.size() > 10);
        });
        T.test("the footstep file's event as the sound S1 reports", () -> {
            T.eq("c000001004", GameHaptics.footstepSound(4, 1001));
            T.eq("c000002038", GameHaptics.footstepSound(38, 2001));
            T.eq("c000001001", GameHaptics.footstepSound(1, 1001));
        });

        T.test("Scheduler: a held frame every tick until released, playbacks join its clock", () -> {
            Sink sink = new Sink();
            Scheduler s = new Scheduler(sink);
            FrameEncoder.Grip[] held = {FrameEncoder.Grip.tone(30, 12), FrameEncoder.Grip.tone(20, 37)};
            s.hold("rumble", held, 3 * MS);
            run(s, sink, 3 * MS, 40 * MS);
            T.eq(Arrays.asList(3 * MS, 18 * MS, 33 * MS), sink.times, "from the hold on, every 15 ms");
            T.eq(encode(held), sink.hexes().get(0));
            Effect e = Fx.left("a", 0.6, 100, 0.03);
            s.start(Renderer.render(e, 1f, false, 1f, Profile.DEFAULT, 0), 40 * MS, "step");
            sink.clear();
            run(s, sink, 40 * MS, 80 * MS);
            T.eq(Arrays.asList(48 * MS, 63 * MS, 78 * MS), sink.times, "a playback started meanwhile keeps the clock");
            FrameEncoder.Grip[] first = Renderer.gripsFor(e, 1f, false, 1f, Profile.DEFAULT).get(0);
            T.eq(Check.hex(FrameEncoder.encode(15, Renderer.mix(Arrays.asList(first[0], held[0])), Renderer.mix(Arrays.asList(first[1], held[1])))),
                sink.hexes().get(0), "layered");
            s.hold("rumble", null, 80 * MS);
            sink.clear();
            run(s, sink, 81 * MS, 200 * MS);
            T.eq(0, sink.frames.size(), "released (the playback had ended)");
            T.yes(s.idle());
            s.hold("rumble", held, 300 * MS);
            s.start(Renderer.render(e, 1f, false, 1f, Profile.DEFAULT, 0), 300 * MS, "step");
            s.clear();
            T.yes(s.idle());
            run(s, sink, 300 * MS, 400 * MS);
            T.eq(0, sink.frames.size(), "clear stops both");
        });

        T.test("event pipe lines", () -> {
            HapticEvent h = HapticEvent.parse("123456 H S1 S2 R1");
            T.eq(HapticEvent.Kind.HOOKS, h.kind);
            T.eq(123456L, h.ms);
            T.yes(h.hook("S1") && h.hook("R1"));
            T.no(h.hook("R4"));
            T.no(HapticEvent.parse("5 H").hook("S1"), "no hooks");
            HapticEvent s = HapticEvent.parse("98765 S c000001004");
            T.eq(HapticEvent.Kind.SOUND, s.kind);
            T.eq("c000001004", s.text);
            T.eq(HapticEvent.Kind.RUMBLE_START, HapticEvent.parse("1 R+ 80").kind);
            T.eq(80, HapticEvent.parse("1 R+ 80").id);
            T.eq(HapticEvent.Kind.RUMBLE_END, HapticEvent.parse("2 R- 0").kind);
            for (String bad : new String[] {null, "", "\n", "S c000001004", "x S c000001004", "-1 S c000001004", "12 S c00000", "12 S", "12 R+",
                "12 R+ x", "12 R* 1", "12 Q 1", "12 S c000001004 extra"}) T.eq(null, HapticEvent.parse(bad), "<" + bad + ">");
        });

        T.test("event route: one reader per FIFO, its lines for the last listener", () -> {
            EventRoute r = EventRoute.of("/test/route-a.fifo");
            T.yes(r == EventRoute.of("/test/route-a.fifo"), "one route per path");
            T.no(r == EventRoute.of("/test/route-b.fifo"), "another path, another route");
            List<String> first = new ArrayList<>(), second = new ArrayList<>();
            EventRoute.Listener one = e -> first.add(e.text), two = e -> second.add(e.text);
            T.yes(r.attach(one), "the first open starts the reader");
            r.line("10 S c000002001");
            T.yes(r.attach(two) == false, "a second open (another MainActivity) starts no second reader");
            for (int i = 0; i < 10; ++i) r.line((20 + i) + " S c000002001");
            T.eq(1, first.size(), "the old listener gets nothing after the new one attached");
            T.eq(10, second.size(), "every step goes to the new listener, none is lost");
            r.detach(one);
            r.line("40 S c000001001");
            T.eq(11, second.size(), "detaching the old listener leaves the new one");
            r.line("bad line");
            r.line("41 Q 1");
            T.eq(11, second.size(), "unparsable lines are dropped");
            r.detach(two);
            r.line("42 S c000001001");
            T.eq(11, second.size(), "no listener: dropped");
            T.yes(r.attach(one) == false, "the reader still runs");
            r.readerEnded();
            T.yes(r.attach(two), "after the reader ended, the next open starts one again");
        });

        String export = System.getenv("BB_TEST_HAPTICS");
        T.test("a real export: loads, and every rule plays padded reports", () -> {
            if (export == null || export.isEmpty()) T.skip("set BB_TEST_HAPTICS to a haptics.json export");
            long t0 = System.nanoTime();
            HapticsTable t = HapticsTable.parse(new String(java.nio.file.Files.readAllBytes(new File(export).toPath()), java.nio.charset.StandardCharsets.UTF_8));
            long parsed = System.nanoTime() - t0;
            System.out.println("     " + t.summary() + "; parsed in " + parsed / 1_000_000 + " ms");
            Sink sink = new Sink();
            Scheduler s = new Scheduler(sink);
            GameHaptics g = new GameHaptics(t, s, new Random(9));
            long p0 = System.nanoTime();
            int prepared = g.prepare();
            System.out.println("     " + prepared + " playbacks rendered ahead in " + (System.nanoTime() - p0) / 1_000_000 + " ms");
            T.yes(prepared >= t.effects.size(), "every used effect rendered");
            long now = 0, worst = 0;
            int played = 0;
            List<String> names = new ArrayList<>(t.sounds.keySet());
            Collections.sort(names);
            for (String name : names) {
                long r0 = System.nanoTime();
                GameHaptics.Played p = g.sound(name, now);
                worst = Math.max(worst, System.nanoTime() - r0);
                T.yes(p != null, name);
                ++played;
                run(s, sink, now, now + 2000 * MS);
                now += 2001 * MS;
            }
            for (byte[] f : sink.frames) T.eq(64, f.length);
            System.out.println("     " + played + " rules, " + sink.frames.size() + " frames; slowest trigger " + worst / 1000 + " us");
            T.yes(sink.frames.size() > played, "frames sent");
            g.motors(180, 90, now);
            g.rumble(true, t.mute.isEmpty() ? -1 : t.mute.iterator().next(), now);
            sink.clear();
            run(s, sink, now, now + 100 * MS);
            T.eq(t.mute.isEmpty() ? 7 : 0, sink.frames.size(), "the export's mute ids mute the rumble");
        });
        System.exit(T.finish());
    }
}
