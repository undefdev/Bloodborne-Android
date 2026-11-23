package com.ducvd89.bloodborne;

import java.util.Objects;

/** Assertions for the desktop tests. */
final class Check {
    private Check() {}

    static void ok(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    static void eq(Object expected, Object actual, String msg) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(msg + ": expected <" + expected + "> but was <" + actual + ">");
    }

    static void eq(long expected, long actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + ": expected <" + expected + "> but was <" + actual + ">");
    }

    static void near(double expected, double actual, double tol, String msg) {
        if (Math.abs(expected - actual) > tol) throw new AssertionError(msg + ": expected <" + expected + "> but was <" + actual + ">");
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    static byte[] unhex(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int k = 0; k < out.length; k++) out[k] = (byte) Integer.parseInt(h.substring(2 * k, 2 * k + 2), 16);
        return out;
    }
}
