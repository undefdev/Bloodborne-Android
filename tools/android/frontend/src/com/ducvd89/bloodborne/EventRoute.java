package com.ducvd89.bloodborne;

import java.util.HashMap;
import java.util.Map;

/** Who gets the event pipe's lines: one reader per FIFO in the process, its lines for the listener that opened it last.
 *  Two readers on one FIFO would each get some of the lines. That happens when a second MainActivity runs in the
 *  process (an explicit start that is not the launcher's intent stacks a new one on the old), and the old one, which no
 *  longer has the Kishi, would take every other footstep. Plain Java, tested on the host (tests/android-mods/HapticsTest.java). */
final class EventRoute {
    interface Listener { void event(HapticEvent e); }

    private static final Map<String, EventRoute> ROUTES = new HashMap<>();

    private Listener listener;
    private boolean reading;

    /** The route of the FIFO at path (one per path in the process). */
    static synchronized EventRoute of(String path) {
        EventRoute r = ROUTES.get(path);
        if (r == null) ROUTES.put(path, r = new EventRoute());
        return r;
    }

    /** From now on l gets the lines (the listener before it no longer). Returns true when no reader runs yet: the
     *  caller starts one, and calls readerEnded() if it cannot. */
    synchronized boolean attach(Listener l) {
        listener = l;
        if (reading) return false;
        reading = true;
        return true;
    }

    /** l no longer gets lines, if it still does; the lines that come while no one listens are dropped. */
    synchronized void detach(Listener l) {
        if (listener == l) listener = null;
    }

    /** The reader stopped (or never started): the next attach() asks for a new one. */
    synchronized void readerEnded() {
        reading = false;
    }

    /** One line the reader read: parsed, to the listener now attached. Reader thread. */
    void line(String text) {
        HapticEvent e = HapticEvent.parse(text);
        Listener l;
        synchronized (this) { l = listener; }
        if (e != null && l != null) l.event(e);
    }
}
