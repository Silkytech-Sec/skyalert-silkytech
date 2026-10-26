package app.skyalert.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Decides which places get checked and how fast. Plain Java so it can be tested on a JVM. */
public final class WatchPlan {
    public static final String HOME = "home";
    /** Never send requests to the traffic API more often than this, however many places there are. */
    public static final long MIN_STEP_MS = 15_000L;

    private WatchPlan() {}

    public static final class Target {
        public final String key;
        public final String label;
        public final double lat;
        public final double lon;
        public final double radiusKm;

        Target(String key, String label, double lat, double lon, double radiusKm) {
            this.key = key;
            this.label = label;
            this.lat = lat;
            this.lon = lon;
            this.radiusKm = radiusKm;
        }

        public boolean isHome() {
            return HOME.equals(key);
        }
    }

    /**
     * The places to check this round: your own position first (when wanted and known),
     * then each watchlist entry. A missing position is simply skipped, never an error.
     */
    public static List<Target> targets(boolean watchHome, double[] home, double homeRadiusKm, List<Watchlist.Entry> list) {
        List<Target> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (watchHome && home != null) {
            out.add(new Target(HOME, "My location", home[0], home[1], homeRadiusKm));
            seen.add(HOME);
        }
        for (Watchlist.Entry e : list) {
            if (seen.add(e.key())) out.add(new Target(e.key(), e.name, e.lat, e.lon, e.radiusKm));
        }
        return out;
    }

    /** Pause between two consecutive checks (each check covers one place, taking turns). */
    public static long stepMs(int pollSeconds, int count) {
        long base = pollSeconds * 1000L;
        if (count <= 1) return base;
        return Math.max(MIN_STEP_MS, base / count);
    }

    /** How long a given place waits between its own checks. */
    public static int perLocationSeconds(int pollSeconds, int count) {
        return (int) Math.max(pollSeconds, stepMs(pollSeconds, count) * Math.max(1, count) / 1000);
    }
}
