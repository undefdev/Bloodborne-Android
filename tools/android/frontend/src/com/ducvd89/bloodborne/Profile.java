package com.ducvd89.bloodborne;

/**
 * The device profile: the Kishi's relative strength per frequency, and the correction from felt amplitude to
 * frame amplitude. Same as hapticsbench/device.py: drive = amp / max(response(hz), 0.25), clipped to 1, then
 * amp63 = round half up (63 * drive); response() is linear in Hz between points and holds the end values.
 * From hapticsBench's player (player/src/hb, d7d60f9), unchanged but for the package; plain Java, tested on the host.
 */
public final class Profile {
    public static final double MIN_RESPONSE = 0.25;
    public static final Profile DEFAULT = new Profile(new double[][] {
        {30, 0.002}, {42, 0.02}, {53, 0.045}, {59, 0.42}, {65, 0.46}, {76, 0.48}, {88, 0.8}, {100, 1.0}, {111, 1.0},
        {123, 0.95}, {132, 0.83}, {152, 0.77}, {192, 0.65}, {216, 0.6}, {239, 0.5}, {300, 0.35}, {400, 0.15},
    });

    private final double[] hz, strength;

    /** points: {hz, relative strength} ordered by hz. */
    public Profile(double[][] points) {
        if (points.length == 0) throw new IllegalArgumentException("empty response curve");
        hz = new double[points.length];
        strength = new double[points.length];
        for (int k = 0; k < points.length; k++) {
            hz[k] = points[k][0];
            strength[k] = points[k][1];
        }
    }

    public double response(double f) {
        if (f <= hz[0]) return strength[0];
        for (int k = 1; k < hz.length; k++)
            if (f <= hz[k]) return strength[k - 1] + (strength[k] - strength[k - 1]) * (f - hz[k - 1]) / (hz[k] - hz[k - 1]);
        return strength[hz.length - 1];
    }

    /** {amp63 0-63, frequency index 0-127} for a felt amplitude 0-1 at f Hz. */
    public int[] quantize(double amp, double f) {
        double a = Math.min(Math.max(amp, 0), 1);
        double drive = Math.min(1, a / Math.max(response(f), MIN_RESPONSE));
        return new int[] {(int) (63 * drive + 0.5), FrameEncoder.freqIndex(f)};
    }
}
