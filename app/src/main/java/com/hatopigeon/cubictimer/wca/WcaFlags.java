package com.hatopigeon.cubictimer.wca;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a WCA nationality (ISO code like "PH" or a country name like "Philippines") into a flag
 * emoji. Anything that cannot be matched to a real country gives an empty string, so a wrong flag
 * is never shown.
 */
public final class WcaFlags {
    private static final Set<String> CODES = new HashSet<>();
    private static final Map<String, String> BY_NAME = new HashMap<>();

    static {
        for (String c : Locale.getISOCountries()) {
            CODES.add(c);
            String n = new Locale("", c).getDisplayCountry(Locale.ENGLISH);
            if (n != null && !n.isEmpty()) BY_NAME.put(norm(n), c);
        }
        CODES.add("XK"); // Kosovo
        String[] alias = {
                "korea", "KR", "south korea", "KR", "republic of korea", "KR",
                "democratic peoples republic of korea", "KP", "north korea", "KP",
                "czech republic", "CZ", "czechia", "CZ", "russia", "RU", "russian federation", "RU",
                "taiwan china", "TW", "taiwan", "TW", "hong kong china", "HK", "hong kong", "HK",
                "macau china", "MO", "macau", "MO", "macao", "MO", "palestine", "PS",
                "palestinian territories", "PS", "vietnam", "VN", "viet nam", "VN", "iran", "IR",
                "laos", "LA", "moldova", "MD", "syria", "SY", "tanzania", "TZ", "turkey", "TR",
                "turkiye", "TR", "swaziland", "SZ", "eswatini", "SZ", "cape verde", "CV",
                "cabo verde", "CV", "congo", "CG", "democratic republic of the congo", "CD",
                "dr congo", "CD", "ivory coast", "CI", "cote divoire", "CI", "brunei", "BN",
                "bolivia", "BO", "venezuela", "VE", "micronesia", "FM", "kosovo", "XK",
                "north macedonia", "MK", "macedonia", "MK", "myanmar", "MM", "burma", "MM",
                "united states", "US", "usa", "US", "united kingdom", "GB", "uk", "GB",
                "timor leste", "TL", "east timor", "TL", "sao tome and principe", "ST",
                "vatican city", "VA", "gambia", "GM", "the gambia", "GM", "bahamas", "BS"
        };
        for (int i = 0; i + 1 < alias.length; i += 2) BY_NAME.put(alias[i], alias[i + 1]);
    }

    private WcaFlags() {}

    private static String norm(String s) {
        String t = s.toLowerCase(Locale.ENGLISH).replace("&", "and");
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = t.replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        return t;
    }

    /** Returns an upper-case ISO code for a code or country name, or "" when unknown. */
    public static String code(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.isEmpty() || "null".equalsIgnoreCase(v)) return "";
        if (v.length() == 2) {
            String u = v.toUpperCase(Locale.US);
            return CODES.contains(u) ? u : "";
        }
        String c = BY_NAME.get(norm(v));
        return c == null ? "" : c;
    }

    /** Flag emoji for a code or country name, or "" when unknown. */
    public static String emoji(String value) {
        String c = code(value);
        if (c.length() != 2) return "";
        int a = Character.codePointAt(c, 0) - 'A' + 0x1F1E6;
        int b = Character.codePointAt(c, 1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }
}
