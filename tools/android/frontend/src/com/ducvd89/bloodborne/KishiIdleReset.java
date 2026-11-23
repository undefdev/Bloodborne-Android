package com.ducvd89.bloodborne;

/** When KishiHaptics gives the Kishi a DeviceReset. After about 15 minutes without a button press (seen with a charger on
 *  its passthrough port) the Kishi keeps echoing frames but no longer vibrates, and it sometimes still answers the 0x90
 *  query, so it can't be detected reliably. A DeviceReset fixes it, and a reset holds only with physical input around it.
 *  So the reset goes with real controller input: the first one after IDLE_MS without any, and the first one at all (the
 *  time before it is unknown). Either is done at the first input while the haptics interface is claimed; a reclaim after
 *  the reset itself does not ask for another one. Main thread; plain Java, tested on the host (tests/android-mods/KishiTest.java). */
final class KishiIdleReset {
    static final long IDLE_MS = 10 * 60 * 1000L;
    /** A stick or analog trigger counts from this far (0..1) off its rest. */
    static final float MOVED = 0.2f;
    private long last = -1; // elapsedRealtime of the last real controller input; -1: none yet
    private String due;     // why a reset is waiting for the next input while claimed

    /** A real controller input at now (SystemClock.elapsedRealtime ms); claimed: the haptics interface is held.
     *  Returns why to reset the Kishi now, or null. */
    String input(long now, boolean claimed) {
        if (last < 0) due = "first controller input";
        else if (now - last >= IDLE_MS) due = "first controller input after " + (now - last) / 60000 + " min without";
        last = now;
        if (due == null || !claimed) return null;
        String why = due;
        due = null;
        return why;
    }

    /** A joystick motion event's axes (sticks -1..1, triggers 0..1, hat -1/0/1): true when a stick or trigger is moved
     *  past MOVED or the d-pad hat is pressed. */
    static boolean active(float lx, float ly, float rx, float ry, float l2, float r2, float hatX, float hatY) {
        return lx * lx + ly * ly >= MOVED * MOVED || rx * rx + ry * ry >= MOVED * MOVED
            || l2 >= MOVED || r2 >= MOVED || Math.abs(hatX) >= 0.5f || Math.abs(hatY) >= 0.5f;
    }
}
