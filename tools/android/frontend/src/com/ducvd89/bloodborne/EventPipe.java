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
 *  parsed (HapticEvent) and handed to the listener on that thread. */
final class EventPipe {
    private static final String TAG = "BloodborneKishi";

    interface Listener { void event(HapticEvent e); }

    private final File fifo;
    private final Listener listener;
    private RandomAccessFile pipe;
    private volatile boolean running;

    EventPipe(File fifo, Listener listener) {
        this.fifo = fifo;
        this.listener = listener;
    }

    /** Makes the FIFO if it is not there and starts the reader (once). Returns the FIFO's path for BB_EVENT_PIPE, or null
     *  (logged) when it cannot. Any thread, before the game starts. */
    synchronized String open() {
        if (pipe != null) return fifo.getPath();
        try {
            if (fifo.exists() && !isFifo(fifo) && !fifo.delete()) throw new IOException("cannot replace " + fifo);
            if (!fifo.exists()) Os.mkfifo(fifo.getPath(), 0600);
            pipe = new RandomAccessFile(fifo, "rw");
        } catch (Exception e) {
            Log.w(TAG, "Haptics: event pipe " + fifo + " unavailable: " + e);
            return null;
        }
        running = true;
        final RandomAccessFile in = pipe;
        Thread reader = new Thread(() -> read(in), "Kishi events");
        reader.setDaemon(true);
        reader.start();
        return fifo.getPath();
    }

    private void read(RandomAccessFile file) {
        try (RandomAccessFile owned = file;
             BufferedReader lines = new BufferedReader(new InputStreamReader(new FileInputStream(owned.getFD()), StandardCharsets.US_ASCII))) {
            for (String line; running && (line = lines.readLine()) != null; ) {
                HapticEvent e = HapticEvent.parse(line);
                if (e != null && running) listener.event(e);
            }
        } catch (IOException | RuntimeException e) {
            if (running) Log.w(TAG, "Haptics: event pipe reader stopped: " + e);
        }
    }

    /** Stops the reader: a newline written into the pipe wakes it, and it closes the pipe. Any thread. */
    synchronized void close() {
        if (pipe == null) return;
        running = false;
        try { pipe.write('\n'); } catch (IOException ignored) { /* the reader is gone already */ }
        pipe = null;
    }

    private static boolean isFifo(File file) {
        try { return OsConstants.S_ISFIFO(Os.stat(file.getPath()).st_mode); } catch (Exception e) { return false; }
    }
}
