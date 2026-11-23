package com.ducvd89.bloodborne;

/** The left stick as the d-pad where the game has no world (the title and its menus, which take only the d-pad): the
 *  stick's stronger axis presses its direction from PRESS off the center and holds it until that axis falls back under
 *  RELEASE, so a stick resting near the threshold does not flutter; the game repeats a held direction itself.
 *  PadBridge.menuStick() turns it on and off (the runtime's "W 0|1" lines). Plain Java, tested on the host. */
final class MenuStick {
    /** Of the 128 steps from the center to the edge. */
    static final int PRESS = 64, RELEASE = 40;

    private MenuStick() {}

    /** dx, dy: the stick from its center, -128..127, y growing down; held: the direction held now, or null.
     *  Returns the direction to hold ("up", "down", "left", "right"), or null. */
    static String direction(int dx, int dy, String held) {
        boolean horizontal = Math.abs(dx) >= Math.abs(dy);
        String now = horizontal ? (dx >= PRESS ? "right" : dx <= -PRESS ? "left" : null)
            : (dy >= PRESS ? "down" : dy <= -PRESS ? "up" : null);
        if (now != null || held == null) return now;
        switch (held) {
            case "right": return dx >= RELEASE ? held : null;
            case "left": return dx <= -RELEASE ? held : null;
            case "down": return dy >= RELEASE ? held : null;
            case "up": return dy <= -RELEASE ? held : null;
            default: return null;
        }
    }
}
