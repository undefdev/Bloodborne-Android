package com.ducvd89.bloodborne;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** The workbench's design (HapticsTable, the game folder's haptics/haptics.json) played from the game's events on one
 *  frame clock (Scheduler, 15 ms frames):
 *  - A sound of the player plays its rule: a member of the rule's group, never the one picked last time when there is a
 *    choice. "step" sounds alternate feet, the left foot first after a pause of more than STEP_PAUSE_NS; the effect's
 *    left grip is the stepping foot, so a right-foot step plays it mirrored; at strength.steps. "event" sounds play as
 *    designed at strength.events. Each starts on its rule's channel: the same channel replaces, others layer.
 *  - While the game's motors (the rumble file, 0-255) are not both 0, a continuous translation is held on channel
 *    RUMBLE: per grip a band for its own motor (left heavy at heavy_hz, right light at light_hz) and one for the other
 *    motor times spread, amplitude = strength * (motor * scale) ^ curve, through the profile's correction (as
 *    hapticsbench/rumble.py translate()). While a rumble id of rumble.mute runs (between its R+ and R-), the
 *    translation is off: the sound-derived effects stand for it. A mute whose R- never came ends after MUTE_MAX_NS.
 *  Not thread-safe: KishiHaptics calls it on its worker. Plain Java, tested on the host (tests/android-mods/HapticsTest.java). */
final class GameHaptics {
    static final String RUMBLE = "rumble";
    static final long STEP_PAUSE_NS = 1_000_000_000L;
    static final long MUTE_MAX_NS = 10_000_000_000L;

    private final HapticsTable table;
    private final Scheduler scheduler;
    private final Random random;
    private final Map<String, String> lastPick = new HashMap<>();
    /** Rendered playbacks by effect, mirror and strength (immutable, shared by every trigger). */
    private final Map<String, Renderer.Playback> rendered = new HashMap<>();
    private boolean leftNext = true, stepped;
    private long lastStepNs;
    /** {rumble id, start ns} of each muting rumble running now. */
    private final List<long[]> muting = new ArrayList<>();
    private int large, small;

    GameHaptics(HapticsTable table, Scheduler scheduler, Random random) {
        this.table = table;
        this.scheduler = scheduler;
        this.random = random;
    }

    /** What a sound played (for the log). */
    static final class Played {
        final String sound, group, effect, channel, foot;
        final float strength;
        Played(String sound, String group, String effect, String channel, String foot, float strength) {
            this.sound = sound; this.group = group; this.effect = effect; this.channel = channel; this.foot = foot; this.strength = strength;
        }
        @Override public String toString() {
            return sound + " -> " + group + " " + effect + " on " + channel + (foot == null ? "" : " " + foot + " foot") + " at " + strength;
        }
    }

    /** A sound of the player at nowNs: its rule's effect starts on its channel. Null when no rule has it. */
    Played sound(String name, long nowNs) {
        HapticsTable.Rule rule = table.sounds.get(name);
        if (rule == null) return null;
        String id = pick(rule.group);
        Effect e = id == null ? null : table.effects.get(id);
        if (e == null) return null;
        String foot = null;
        float strength = table.eventStrength;
        if (rule.step()) {
            if (!stepped || nowNs - lastStepNs > STEP_PAUSE_NS) leftNext = true;
            foot = leftNext ? "left" : "right";
            leftNext = !leftNext;
            stepped = true;
            lastStepNs = nowNs;
            strength = table.stepStrength;
        }
        scheduler.start(playback(id, e, strength, "right".equals(foot)), nowNs, rule.channel);
        return new Played(name, rule.group, id, rule.channel, foot, strength);
    }

    /** Renders every rule's effects ahead (both feet for steps), so a trigger never waits for it. Returns how many. */
    int prepare() {
        for (HapticsTable.Rule rule : table.sounds.values()) {
            for (String id : table.groups.get(rule.group)) {
                Effect e = table.effects.get(id);
                playback(id, e, rule.step() ? table.stepStrength : table.eventStrength, false);
                if (rule.step()) playback(id, e, table.stepStrength, true);
            }
        }
        return rendered.size();
    }

    private Renderer.Playback playback(String id, Effect e, float strength, boolean mirror) {
        String key = id + (mirror ? " mirrored " : " ") + strength;
        Renderer.Playback pb = rendered.get(key);
        if (pb == null) rendered.put(key, pb = Renderer.render(e, strength, mirror, 1f, table.profile, 0));
        return pb;
    }

    /** A rumble starts (R+) or ends (R-) at nowNs; only rumble.mute's ids matter. */
    void rumble(boolean start, int id, long nowNs) {
        if (!table.mute.contains(id)) return;
        if (start) muting.add(new long[] {id, nowNs});
        else {
            for (Iterator<long[]> it = muting.iterator(); it.hasNext(); ) {
                if (it.next()[0] == id) { it.remove(); break; }
            }
        }
        updateRumble(nowNs);
    }

    /** The game's motors now (0-255 each, heavy and light). */
    void motors(int large, int small, long nowNs) {
        this.large = Math.max(0, Math.min(255, large));
        this.small = Math.max(0, Math.min(255, small));
        updateRumble(nowNs);
    }

    /** True while a muting rumble runs. */
    boolean muted(long nowNs) {
        for (Iterator<long[]> it = muting.iterator(); it.hasNext(); ) if (nowNs - it.next()[1] > MUTE_MAX_NS) it.remove();
        return !muting.isEmpty();
    }

    /** Nothing plays any more (pause, drawer, Vibration off); the next motor values and sounds play again. The running
     *  rumbles stay known, so their mutes hold. */
    void silence() {
        large = small = 0;
        scheduler.clear();
    }

    private void updateRumble(long nowNs) {
        FrameEncoder.Grip[] grips = large == 0 && small == 0 || muted(nowNs) ? null : translate(table.translate, table.profile, large, small);
        if (grips != null && grips[0].bandAmp.length == 0 && grips[1].bandAmp.length == 0) grips = null;
        scheduler.hold(RUMBLE, grips, nowNs);
    }

    /** {left, right} for the motors (0-255): left heavy at heavy_hz plus light * spread at light_hz, right the other way
     *  round; amp = min(1, strength * gain * max(0, motor * scale) ^ curve) rounded to 4 decimals as rumble.py does, then
     *  the profile's correction; bands that come out as 0 are left out. */
    static FrameEncoder.Grip[] translate(HapticsTable.Translate t, Profile p, int large, int small) {
        double heavy = large / 255.0, light = small / 255.0;
        return new FrameEncoder.Grip[] {
            grip(p, amp(t, heavy, t.heavyScale, 1), t.heavyHz, amp(t, light, t.lightScale, t.spread), t.lightHz),
            grip(p, amp(t, light, t.lightScale, 1), t.lightHz, amp(t, heavy, t.heavyScale, t.spread), t.heavyHz),
        };
    }

    static double amp(HapticsTable.Translate t, double motor, double scale, double gain) {
        if (gain <= 0) return 0;
        double a = Math.min(1.0, t.strength * gain * Math.pow(Math.max(0.0, motor * scale), t.curve));
        return Math.round(a * 10000) / 10000.0;
    }

    private static FrameEncoder.Grip grip(Profile p, double mainAmp, double mainHz, double otherAmp, double otherHz) {
        List<int[]> amps = new ArrayList<>(), idxs = new ArrayList<>();
        for (double[] band : new double[][] {{mainAmp, mainHz}, {otherAmp, otherHz}}) {
            int[] q = p.quantize(band[0], band[1]);
            if (q[0] == 0) continue;
            amps.add(new int[] {q[0], q[0], q[0], q[0]});
            idxs.add(new int[] {q[1], q[1], q[1], q[1]});
        }
        return new FrameEncoder.Grip(amps.toArray(new int[0][]), idxs.toArray(new int[0][]), null);
    }

    /** A member of the group, not the one picked last time if there is a choice (as the workbench's walk scene picks). */
    private String pick(String group) {
        List<String> ids = table.groups.get(group);
        if (ids == null || ids.isEmpty()) return null;
        List<String> choices = new ArrayList<>(ids);
        String last = lastPick.get(group);
        if (choices.size() > 1) choices.remove(last);
        String id = choices.get(random.nextInt(choices.size()));
        lastPick.put(group, id);
        return id;
    }

    /** The footstep file's event (floor material, base sound 1001 walk / 2001 run) as the sound S1 reports for it: the
     *  base's thousands plus the material, e.g. material 4 (wood) walking is c000001004. */
    static String footstepSound(int material, int sound) {
        return String.format(java.util.Locale.ROOT, "c%09d", sound / 1000 * 1000 + material);
    }
}
