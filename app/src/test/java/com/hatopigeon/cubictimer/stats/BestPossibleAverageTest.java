package com.hatopigeon.cubictimer.stats;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BestPossibleAverageTest {
    private static final long DNF = BestPossibleAverage.DNF;
    private static final long UNKNOWN = BestPossibleAverage.UNKNOWN;

    @Test
    public void ao5Basic() {
        assertEquals(11000, BestPossibleAverage.compute(new long[]{10000, 11000, 12000, 13000, 0}, 5, false));
    }

    @Test
    public void ao5OneDnfIsDropped() {
        assertEquals(11000, BestPossibleAverage.compute(new long[]{10000, DNF, 12000, 11000, 0}, 5, false));
    }

    @Test
    public void ao5TwoDnfsIsDnf() {
        assertEquals(DNF, BestPossibleAverage.compute(new long[]{10000, DNF, DNF, 11000, 0}, 5, false));
    }

    @Test
    public void ao5NotEnoughSolves() {
        assertEquals(UNKNOWN, BestPossibleAverage.compute(new long[]{10000, 11000, 12000, 0, 0}, 5, false));
    }

    @Test
    public void ao5Rounding() {
        assertEquals(10330, BestPossibleAverage.compute(new long[]{10000, 10000, 11000, 20000, 0}, 5, false));
        assertEquals(10333, BestPossibleAverage.compute(new long[]{10000, 10000, 11000, 20000, 0}, 5, true));
    }

    @Test
    public void ao12() {
        long[] t = new long[12];
        for (int i = 0; i < 11; i++) t[i] = (i + 1) * 1000L;
        assertEquals(5500, BestPossibleAverage.compute(t, 12, false));
        t[3] = DNF;
        assertEquals(6200, BestPossibleAverage.compute(t, 12, false));
        t[5] = DNF;
        assertEquals(DNF, BestPossibleAverage.compute(t, 12, false));
    }

    @Test
    public void ao12NotEnoughSolves() {
        assertEquals(UNKNOWN, BestPossibleAverage.compute(
                new long[]{1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000, 0, 0}, 12, false));
    }
}
