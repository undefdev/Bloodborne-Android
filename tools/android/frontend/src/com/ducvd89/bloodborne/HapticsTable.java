package com.ducvd89.bloodborne;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The workbench's export (hapticsBench, hapticsbench/game_export.py) in the game folder's haptics/haptics.json: effects,
 *  variant groups, sound rules, strengths, the rumble translation and its mutes. GameHaptics plays it. Read with MiniJson,
 *  so it is plain Java (tested on the host); the counterpart of the workbench player's LibraryJson for this format:
 *  {"version": 1, "profile": {"response": [[hz, strength], ...]}, "strength": {"steps", "events"},
 *   "effects": {id: {"left"|"right": {"bands": [[[t, amp, hz], ...], ...], "transients": [[t, amp, hz], ...]}}},
 *   "groups": {name: [ids]}, "sounds": {name: {"group", "kind": "step"|"event", "channel"}},
 *   "rumble": {"translate": {strength, curve, heavy_hz, light_hz, heavy_scale, light_scale, spread}, "mute": [ids]}}
 *  Anything that does not fit this makes the whole file invalid (the app then plays as without it); a rule whose group has
 *  no effect is dropped and counted. */
final class HapticsTable {
    static final String FILE = "haptics/haptics.json";
    static final String STEP = "step", EVENT = "event";

    /** A sound rule: play a member of group; "step" alternates feet, "event" plays as designed. */
    static final class Rule {
        final String group, kind, channel;
        Rule(String group, String kind, String channel) { this.group = group; this.kind = kind; this.channel = channel; }
        boolean step() { return STEP.equals(kind); }
    }

    /** The rumble translation (hapticsbench/rumble.py DEFAULT_RUMBLE and translate()). */
    static final class Translate {
        final double strength, curve, heavyHz, lightHz, heavyScale, lightScale, spread;
        Translate(double strength, double curve, double heavyHz, double lightHz, double heavyScale, double lightScale, double spread) {
            this.strength = strength; this.curve = curve; this.heavyHz = heavyHz; this.lightHz = lightHz;
            this.heavyScale = heavyScale; this.lightScale = lightScale; this.spread = spread;
        }
        static final Translate DEFAULT = new Translate(0.6, 0.8, 65, 140, 1.0, 1.0, 0.3);
    }

    final Map<String, Effect> effects;
    /** group -> the ids of its effects that exist. */
    final Map<String, List<String>> groups;
    final Map<String, Rule> sounds;
    final float stepStrength, eventStrength;
    final Translate translate;
    final Set<Integer> mute;
    final Profile profile;
    /** Rules dropped because their group has no effect. */
    final int droppedRules;

    private HapticsTable(Map<String, Effect> effects, Map<String, List<String>> groups, Map<String, Rule> sounds, float stepStrength,
                         float eventStrength, Translate translate, Set<Integer> mute, Profile profile, int droppedRules) {
        this.effects = Collections.unmodifiableMap(effects);
        this.groups = Collections.unmodifiableMap(groups);
        this.sounds = Collections.unmodifiableMap(sounds);
        this.stepStrength = stepStrength;
        this.eventStrength = eventStrength;
        this.translate = translate;
        this.mute = Collections.unmodifiableSet(mute);
        this.profile = profile;
        this.droppedRules = droppedRules;
    }

    /** Why the game folder has no usable table. */
    static final class Invalid extends Exception { Invalid(String why) { super(why); } }

    /** The game folder's table; Invalid says why there is none (missing, unreadable, or not the expected format). */
    static HapticsTable load(File gameDir) throws Invalid {
        File file = new File(gameDir, FILE);
        if (!file.isFile()) throw new Invalid("not in the game folder");
        String text;
        try { text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
        catch (IOException | OutOfMemoryError e) { throw new Invalid(FILE + " unreadable: " + e); }
        return parse(text);
    }

    static HapticsTable parse(String text) throws Invalid {
        Object root;
        try { root = MiniJson.parse(text); }
        catch (MiniJson.ParseException e) { throw new Invalid("not JSON: " + e.getMessage()); }
        try { return parse(map(root, "the file")); }
        catch (ClassCastException | IllegalArgumentException e) { throw new Invalid(e.getMessage()); }
    }

    private static HapticsTable parse(Map<String, Object> root) throws Invalid {
        Object version = root.get("version");
        if (!(version instanceof Number) || ((Number) version).doubleValue() != 1) throw new Invalid("version " + version + ", not 1");
        Map<String, Effect> effects = new HashMap<>();
        for (Map.Entry<String, Object> e : map(root.get("effects"), "effects").entrySet()) effects.put(e.getKey(), effect(e.getKey(), e.getValue()));
        Map<String, List<String>> groups = new HashMap<>();
        for (Map.Entry<String, Object> g : map(root.get("groups"), "groups").entrySet()) {
            List<String> ids = new ArrayList<>();
            for (Object id : list(g.getValue(), "group " + g.getKey())) if (id instanceof String && effects.containsKey(id)) ids.add((String) id);
            groups.put(g.getKey(), Collections.unmodifiableList(ids));
        }
        Map<String, Rule> sounds = new HashMap<>();
        int dropped = 0;
        for (Map.Entry<String, Object> s : map(root.get("sounds"), "sounds").entrySet()) {
            Map<String, Object> r = map(s.getValue(), "sound " + s.getKey());
            String group = string(r.get("group"), "sound " + s.getKey() + " group");
            String kind = string(r.get("kind"), "sound " + s.getKey() + " kind");
            if (!kind.equals(STEP) && !kind.equals(EVENT)) throw new Invalid("sound " + s.getKey() + ": kind " + kind);
            Object channel = r.get("channel");
            List<String> members = groups.get(group);
            if (members == null || members.isEmpty()) { ++dropped; continue; }
            sounds.put(s.getKey(), new Rule(group, kind, channel instanceof String ? (String) channel : group));
        }
        if (sounds.isEmpty()) throw new Invalid("no sound rule has an effect");
        Map<String, Object> strength = optMap(root.get("strength"), "strength");
        float steps = (float) number(strength.get("steps"), 1.0, "strength.steps");
        float events = (float) number(strength.get("events"), 1.0, "strength.events");
        Map<String, Object> rumble = optMap(root.get("rumble"), "rumble");
        Map<String, Object> t = optMap(rumble.get("translate"), "rumble.translate");
        Translate d = Translate.DEFAULT;
        Translate translate = new Translate(number(t.get("strength"), d.strength, "translate.strength"), number(t.get("curve"), d.curve, "translate.curve"),
            number(t.get("heavy_hz"), d.heavyHz, "translate.heavy_hz"), number(t.get("light_hz"), d.lightHz, "translate.light_hz"),
            number(t.get("heavy_scale"), d.heavyScale, "translate.heavy_scale"), number(t.get("light_scale"), d.lightScale, "translate.light_scale"),
            number(t.get("spread"), d.spread, "translate.spread"));
        Set<Integer> mute = new LinkedHashSet<>();
        if (rumble.get("mute") != null) for (Object id : list(rumble.get("mute"), "rumble.mute")) mute.add((int) number(id, 0, "rumble.mute"));
        Profile profile = Profile.DEFAULT;
        if (root.get("profile") != null) {
            List<Object> response = list(map(root.get("profile"), "profile").get("response"), "profile.response");
            double[][] points = new double[response.size()][];
            for (int i = 0; i < points.length; i++) {
                List<Object> p = list(response.get(i), "profile.response");
                if (p.size() != 2) throw new Invalid("profile.response: a point is not [hz, strength]");
                points[i] = new double[] {number(p.get(0), 0, "profile hz"), number(p.get(1), 0, "profile strength")};
            }
            if (points.length == 0) throw new Invalid("profile.response is empty");
            Arrays.sort(points, (x, y) -> Double.compare(x[0], y[0]));
            profile = new Profile(points);
        }
        return new HapticsTable(effects, groups, sounds, steps, events, translate, mute, profile, dropped);
    }

    private static Effect effect(String id, Object value) throws Invalid {
        Map<String, Object> o = map(value, "effect " + id);
        return new Effect(id, id, null, grip(id + " left", o.get("left")), grip(id + " right", o.get("right")));
    }

    private static Effect.Grip grip(String where, Object value) throws Invalid {
        if (value == null) return Effect.Grip.EMPTY;
        Map<String, Object> g = map(value, where);
        List<Effect.Band> bands = new ArrayList<>();
        if (g.get("bands") != null) {
            for (Object b : list(g.get("bands"), where + " bands")) {
                List<Object> points = list(b, where + " band");
                double[][] p = new double[points.size()][];
                for (int i = 0; i < p.length; i++) p[i] = triple(points.get(i), where + " band point");
                Arrays.sort(p, (x, y) -> Double.compare(x[0], y[0]));
                double[] t = new double[p.length], amp = new double[p.length], hz = new double[p.length];
                for (int i = 0; i < p.length; i++) { t[i] = p[i][0]; amp[i] = p[i][1]; hz[i] = p[i][2]; }
                bands.add(new Effect.Band(t, amp, hz));
            }
        }
        List<Effect.Transient> transients = new ArrayList<>();
        if (g.get("transients") != null) {
            for (Object tr : list(g.get("transients"), where + " transients")) {
                double[] v = triple(tr, where + " transient");
                transients.add(new Effect.Transient(v[0], v[1], v[2]));
            }
        }
        return new Effect.Grip(bands, transients);
    }

    /** [t s, amp 0-1, hz]. */
    private static double[] triple(Object value, String where) throws Invalid {
        List<Object> v = list(value, where);
        if (v.size() != 3) throw new Invalid(where + " is not [t, amp, hz]");
        return new double[] {number(v.get(0), 0, where), number(v.get(1), 0, where), number(v.get(2), 0, where)};
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String what) throws Invalid {
        if (!(value instanceof Map)) throw new Invalid(what + " is not an object");
        return (Map<String, Object>) value;
    }
    private static Map<String, Object> optMap(Object value, String what) throws Invalid {
        return value == null ? Collections.<String, Object>emptyMap() : map(value, what);
    }
    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value, String what) throws Invalid {
        if (!(value instanceof List)) throw new Invalid(what + " is not a list");
        return (List<Object>) value;
    }
    private static String string(Object value, String what) throws Invalid {
        if (!(value instanceof String)) throw new Invalid(what + " is not a string");
        return (String) value;
    }
    /** A finite number; fallback when absent (null). */
    private static double number(Object value, double fallback, String what) throws Invalid {
        if (value == null) return fallback;
        if (!(value instanceof Number)) throw new Invalid(what + " is not a number");
        double d = ((Number) value).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new Invalid(what + " is not finite");
        return d;
    }

    /** The one line the app logs: what was loaded. */
    String summary() {
        int steps = 0;
        for (Rule r : sounds.values()) if (r.step()) ++steps;
        return effects.size() + " effects, " + groups.size() + " groups, " + sounds.size() + " sound rules (" + steps + " steps, "
            + (sounds.size() - steps) + " events" + (droppedRules > 0 ? ", " + droppedRules + " without effects dropped" : "")
            + "), strength steps " + stepStrength + " events " + eventStrength + ", rumble mute " + mute;
    }
}
