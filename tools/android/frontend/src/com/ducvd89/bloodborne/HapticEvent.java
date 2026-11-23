package com.ducvd89.bloodborne;

/** One line of the runtime's event pipe (BB_EVENT_PIPE, src/runtime_events.c), ms on the boot clock
 *  (SystemClock.elapsedRealtime()):
 *    "<ms> H S1 S2 R1"   the pipe's hooks that are installed (first, once per game start)
 *    "<ms> S c000001004" a sound of the player character
 *    "<ms> R+ 1", "<ms> R- 1"  a rumble starts / ends
 *    "<ms> W 1", "<ms> W 0"    the player character entered / left a world (0: the title and its menus, loading)
 *  Plain Java, tested on the host. */
final class HapticEvent {
    enum Kind { HOOKS, SOUND, RUMBLE_START, RUMBLE_END, WORLD }

    final Kind kind;
    final long ms;
    /** HOOKS: the hook names, space-separated (may be empty); SOUND: the sound's name; else null. */
    final String text;
    /** RUMBLE_*: the rumble id; WORLD: 1 in a world, 0 not. */
    final int id;

    private HapticEvent(Kind kind, long ms, String text, int id) {
        this.kind = kind; this.ms = ms; this.text = text; this.id = id;
    }

    /** The event, or null for a line that is not one (a cut or unknown line is skipped). */
    static HapticEvent parse(String line) {
        if (line == null) return null;
        String[] part = line.trim().split(" ");
        if (part.length < 2) return null;
        long ms;
        try { ms = Long.parseLong(part[0]); } catch (NumberFormatException e) { return null; }
        if (ms < 0) return null;
        switch (part[1]) {
            case "H": {
                StringBuilder hooks = new StringBuilder();
                for (int i = 2; i < part.length; i++) hooks.append(i > 2 ? " " : "").append(part[i]);
                return new HapticEvent(Kind.HOOKS, ms, hooks.toString(), 0);
            }
            case "S":
                return part.length == 3 && part[2].length() == 10 ? new HapticEvent(Kind.SOUND, ms, part[2], 0) : null;
            case "R+": case "R-": {
                if (part.length != 3) return null;
                int id;
                try { id = Integer.parseInt(part[2]); } catch (NumberFormatException e) { return null; }
                return new HapticEvent(part[1].equals("R+") ? Kind.RUMBLE_START : Kind.RUMBLE_END, ms, null, id);
            }
            case "W":
                return part.length == 3 && (part[2].equals("0") || part[2].equals("1"))
                    ? new HapticEvent(Kind.WORLD, ms, null, part[2].equals("1") ? 1 : 0) : null;
            default:
                return null;
        }
    }

    /** HOOKS: whether the hook is among them. */
    boolean hook(String name) {
        for (String h : text.split(" ")) if (h.equals(name)) return true;
        return false;
    }
}
