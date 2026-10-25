package app.skyalert.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses windows like "21-7" or "22:30-06:45". */
public final class QuietHours {
    private static final Pattern P = Pattern.compile("^(\\d{1,2})(?::(\\d{2}))?\\s*-\\s*(\\d{1,2})(?::(\\d{2}))?$");
    public final int startMin, endMin;

    private QuietHours(int s, int e) {
        startMin = s;
        endMin = e;
    }

    /** null for blank input. Throws IllegalArgumentException when malformed. */
    public static QuietHours parse(String spec) {
        if (spec == null || spec.trim().isEmpty()) return null;
        Matcher m = P.matcher(spec.trim());
        if (!m.matches()) throw new IllegalArgumentException("Quiet hours should look like 21-7 or 22:30-06:45");
        int sh = Integer.parseInt(m.group(1)), eh = Integer.parseInt(m.group(3));
        int sm = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        int em = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
        if (sh > 24 || eh > 24 || sm > 59 || em > 59) throw new IllegalArgumentException("Quiet hours out of range");
        return new QuietHours((sh % 24) * 60 + sm, (eh % 24) * 60 + em);
    }

    public static QuietHours parseOrNull(String spec) {
        try {
            return parse(spec);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean contains(int minuteOfDay) {
        if (startMin == endMin) return false;
        if (startMin < endMin) return minuteOfDay >= startMin && minuteOfDay < endMin;
        return minuteOfDay >= startMin || minuteOfDay < endMin;
    }
}
