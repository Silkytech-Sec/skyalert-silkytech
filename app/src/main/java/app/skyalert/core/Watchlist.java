package app.skyalert.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Extra places to watch (airports or any coordinates), plus how they are stored. */
public final class Watchlist {
    public static final int MAX = 8;
    public static final int MAX_NAME = 40;
    public static final double DEFAULT_RADIUS_KM = 25;
    public static final double MIN_RADIUS_KM = 5;
    public static final double MAX_RADIUS_KM = 460;

    private Watchlist() {}

    /** One place to watch. Always valid: the only ways to make one check the ranges. */
    public static final class Entry {
        public final String name;
        public final double lat;
        public final double lon;
        public final double radiusKm;

        private Entry(String name, double lat, double lon, double radiusKm) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.radiusKm = radiusKm;
        }

        /** Builds an entry from numbers. Throws IllegalArgumentException with a readable message. */
        public static Entry of(String rawName, double lat, double lon, double radiusKm) {
            String name = cleanName(rawName);
            if (name.isEmpty()) throw new IllegalArgumentException("Give the place a name");
            if (!(Math.abs(lat) <= 90)) throw new IllegalArgumentException("Latitude must be between -90 and 90");
            if (!(Math.abs(lon) <= 180)) throw new IllegalArgumentException("Longitude must be between -180 and 180");
            if (!(radiusKm >= MIN_RADIUS_KM && radiusKm <= MAX_RADIUS_KM))
                throw new IllegalArgumentException("Radius must be between 5 and 460 km");
            return new Entry(name, lat, lon, radiusKm);
        }

        /** Builds an entry from text fields. A blank radius means the default. */
        public static Entry fromInput(String name, String lat, String lon, String radius) {
            double la = number(lat, "latitude");
            double lo = number(lon, "longitude");
            double r = radius == null || radius.trim().isEmpty() ? DEFAULT_RADIUS_KM : number(radius, "radius");
            return of(name, la, lo, r);
        }

        /** Stable identity used for the per-place tracker and notification ids. */
        public String key() {
            return String.format(Locale.ROOT, "%.4f,%.4f", lat, lon);
        }

        public String describe() {
            String r = radiusKm == Math.rint(radiusKm) ? String.valueOf((long) radiusKm) : String.valueOf(radiusKm);
            return String.format(Locale.ROOT, "%.4f, %.4f  |  %s km radius", lat, lon, r);
        }
    }

    private static double number(String s, String what) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("Enter a " + what);
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("That " + what + " is not a number");
        }
    }

    static String cleanName(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return s.length() > MAX_NAME ? s.substring(0, MAX_NAME).trim() : s;
    }

    /** One entry per line, fields separated by tabs. Names can't contain either. */
    public static String encode(List<Entry> list) {
        StringBuilder b = new StringBuilder();
        for (Entry e : list) {
            if (b.length() > 0) b.append('\n');
            b.append(e.name).append('\t').append(e.lat).append('\t').append(e.lon).append('\t').append(e.radiusKm);
        }
        return b.toString();
    }

    /** Reads what encode wrote. Skips damaged lines and duplicates, and stops at MAX entries. */
    public static List<Entry> decode(String text) {
        List<Entry> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String line : text.split("\n")) {
            if (out.size() >= MAX) break;
            String[] p = line.split("\t", -1);
            if (p.length != 4) continue;
            try {
                Entry e = Entry.of(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]));
                boolean dup = false;
                for (Entry x : out) if (x.key().equals(e.key())) dup = true;
                if (!dup) out.add(e);
            } catch (IllegalArgumentException ignored) {
                // damaged line, skip it
            }
        }
        return out;
    }
}
