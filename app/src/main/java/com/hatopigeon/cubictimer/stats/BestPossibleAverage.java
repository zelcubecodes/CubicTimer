package com.hatopigeon.cubictimer.stats;

/**
 * Best possible average (BPA) for a running trimmed average of N solves (Ao5, Ao12, ...).
 *
 * The BPA is the best average you can still finish with if your next solve is as fast as
 * possible. It uses the most recent N-1 solves. The next solve is assumed to be the fastest,
 * so it is discarded as the best time, and the slowest of the N-1 solves is discarded as the
 * worst time (a DNF counts as slowest). The remaining N-2 solves are averaged. If two or more
 * of the N-1 solves are DNF, the BPA is a DNF.
 *
 * Plain Java with no Android dependencies so it can be unit tested on the JVM.
 */
public final class BestPossibleAverage {
    /** Value used for a DNF in the input times and as the result. */
    public static final long DNF = -1L;
    /** Returned when there are not enough solves yet. */
    public static final long UNKNOWN = -2L;

    private BestPossibleAverage() {}

    /**
     * @param newestFirst
     *     Recent solve times in milliseconds, newest first. Entries of 0 (or fewer, other than
     *     {@link #DNF}) mean "no solve yet". A DNF is {@link #DNF}.
     * @param n
     *     The size of the average (5 or 12). Must be at least 3.
     * @param enableThousandth
     *     If false the result is rounded to the nearest centisecond, like the app's other
     *     averages.
     *
     * @return the BPA in milliseconds, {@link #DNF}, or {@link #UNKNOWN}.
     */
    public static long compute(long[] newestFirst, int n, boolean enableThousandth) {
        if (n < 3 || newestFirst == null || newestFirst.length < n - 1) {
            return UNKNOWN;
        }

        int dnfs = 0;
        long sum = 0L;
        long worst = 0L;
        for (int i = 0; i < n - 1; i++) {
            long t = newestFirst[i];
            if (t == DNF) {
                dnfs++;
            } else if (t <= 0L) {
                return UNKNOWN; // Not enough solves yet.
            } else {
                sum += t;
                worst = Math.max(worst, t);
            }
        }

        if (dnfs >= 2) {
            return DNF;
        }
        if (dnfs == 0) {
            sum -= worst; // Drop the slowest solve.
        } // With exactly one DNF, the DNF is the dropped worst solve.

        int divisor = n - 2;
        long avg = (sum + divisor / 2) / divisor;
        if (!enableThousandth) {
            avg = (avg + 5) / 10 * 10;
        }
        return avg;
    }
}
