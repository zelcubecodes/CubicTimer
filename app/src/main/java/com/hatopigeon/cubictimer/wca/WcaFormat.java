package com.hatopigeon.cubictimer.wca;

import java.util.Locale;

/** Event names, round names and result formatting shared by the WCA screens. */
public final class WcaFormat {
    private static final String[][] EVENTS = {
            {"333", "3x3x3 Cube"}, {"222", "2x2x2 Cube"}, {"444", "4x4x4 Cube"},
            {"555", "5x5x5 Cube"}, {"666", "6x6x6 Cube"}, {"777", "7x7x7 Cube"},
            {"333bf", "3x3x3 Blindfolded"}, {"333fm", "3x3x3 Fewest Moves"},
            {"333oh", "3x3x3 One-Handed"}, {"clock", "Clock"}, {"minx", "Megaminx"},
            {"pyram", "Pyraminx"}, {"skewb", "Skewb"}, {"sq1", "Square-1"},
            {"444bf", "4x4x4 Blindfolded"}, {"555bf", "5x5x5 Blindfolded"},
            {"333mbf", "3x3x3 Multi-Blind"}, {"fto", "FTO"}
    };

    private WcaFormat() {}

    public static String eventName(String id) {
        for (String[] e : EVENTS) {
            if (e[0].equals(id)) return e[1];
        }
        return id;
    }

    public static int eventOrder(String id) {
        for (int i = 0; i < EVENTS.length; i++) {
            if (EVENTS[i][0].equals(id)) return i;
        }
        return 1000;
    }

    public static String roundName(String roundTypeId) {
        switch (roundTypeId) {
            case "0": case "h": return "Qualification round";
            case "1": case "d": return "First round";
            case "2": case "e": return "Second round";
            case "3": case "g": return "Semi final";
            case "b": return "B final";
            case "c": case "f": return "Final";
            default: return roundTypeId;
        }
    }

    public static int roundRank(String roundTypeId) {
        switch (roundTypeId) {
            case "h": return 10;
            case "0": return 19;
            case "d": return 20;
            case "1": return 29;
            case "b": return 39;
            case "2": return 50;
            case "e": return 59;
            case "g": return 70;
            case "3": return 79;
            case "c": return 90;
            case "f": return 99;
            default: return 100;
        }
    }

    /** Formats a WCA result value. DNF is -1, DNS is -2 and 0 means no result. */
    public static String formatResult(long v, String eventId, boolean average) {
        if (v == -1) return "DNF";
        if (v == -2) return "DNS";
        if (v <= 0) return "";
        if ("333mbf".equals(eventId)) {
            long dd = v / 10000000L;
            long rest = v % 10000000L;
            long seconds = rest / 100;
            long missed = rest % 100;
            long points = 99 - dd;
            long solved = points + missed;
            long attempted = solved + missed;
            return solved + "/" + attempted + " " + formatSeconds(seconds);
        }
        if ("333fm".equals(eventId)) {
            if (average) {
                return String.format(Locale.US, "%d.%02d", v / 100, v % 100);
            }
            return String.valueOf(v);
        }
        return formatCentiseconds(v);
    }

    static String formatSeconds(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    static String formatCentiseconds(long v) {
        long totalSeconds = v / 100;
        long cs = v % 100;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs);
        if (m > 0) return String.format(Locale.US, "%d:%02d.%02d", m, s, cs);
        return String.format(Locale.US, "%d.%02d", s, cs);
    }
}
