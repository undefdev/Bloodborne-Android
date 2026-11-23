package com.ducvd89.bloodborne;

import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** The app's end of the runtime's event pipe (BB_EVENT_PIPE, src/runtime_events.c): a FIFO in the app's files folder,
 *  made with Os.mkfifo before the game starts. A reader thread holds it open read-write (as AudioBridge holds its FIFO),
 *  so the game's non-blocking open finds a reader at once and a restarted game writes into the same pipe; each line is
 *  parsed (HapticEvent) and handed to the listener on that thread. The process has one reader per FIFO (EventRoute):
 *  a second EventPipe on it (a second MainActivity) takes the lines over instead of starting a reader that would get
 *  every other line, and the reader keeps running for the next one after close(). */
final class EventPipe {
    private static final String TAG = "BloodborneKishi";

    interface Listener extends EventRoute.Listener {}

    private final File fifo;
    private final Listener listener;
    private EventRoute route;

    EventPipe(File fifo, Listener listener) {
        this.fifo = fifo;
        this.listener = listener;
    }

    /** Makes the FIFO if it is not there, starts the process's reader if none runs, and takes its lines (once).
     *  Returns the FIFO's path for BB_EVENT_PIPE, or null (logged) when it cannot. Any thread, before the game starts. */
    synchronized String open() {
        if (route != null) return fifo.getPath();
        EventRoute r = EventRoute.of(fifo.getPath());
        synchronized (EventRoute.class) {
            // A FIFO deleted since (the files folder cleaned): its old reader holds the unlinked one, so a new one starts.
            if (!fifo.exists()) r.readerEnded();
            if (r.attach(listener)) {
                try {
                    if (fifo.exists() && !isFifo(fifo) && !fifo.delete()) throw new IOException("cannot replace " + fifo);
                    if (!fifo.exists()) Os.mkfifo(fifo.getPath(), 0600);
                    RandomAccessFile pipe = new RandomAccessFile(fifo, "rw");
                    Thread reader = new Thread(() -> read(pipe, r), "Kishi events");
                    reader.setDaemon(true);
                    reader.start();
                } catch (Exception e) {
                    r.readerEnded();
                    r.detach(listener);
                    Log.w(TAG, "Haptics: event pipe " + fifo + " unavailable: " + e);
                    return null;
                }
            }
        }
        route = r;
        return fifo.getPath();
    }

    private static void read(RandomAccessFile file, EventRoute route) {
        try (RandomAccessFile owned = file;
             BufferedReader lines = new BufferedReader(new InputStreamReader(new FileInputStream(owned.getFD()), StandardCharsets.US_ASCII))) {
            for (String line; (line = lines.readLine()) != null; ) route.line(line);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Haptics: event pipe reader stopped: " + e);
        } finally {
            route.readerEnded();
        }
    }

    /** This listener gets no more lines; the reader stays for the next open (lines without a listener are dropped).
     *  Any thread. */
    synchronized void close() {
        if (route == null) return;
        route.detach(listener);
        route = null;
    }

    private static boolean isFifo(File file) {
        try { return OsConstants.S_ISFIFO(Os.stat(file.getPath()).st_mode); } catch (Exception e) { return false; }
    }
}
