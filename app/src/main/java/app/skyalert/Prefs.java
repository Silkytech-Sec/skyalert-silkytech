package app.skyalert;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import app.skyalert.core.QuietHours;
import app.skyalert.core.Settings;

/** SharedPreferences keys, defaults, and mapping to core Settings. */
public final class Prefs {
    public static final String FILE = "skyalert";

    public static final String ENABLED = "enabled";
    public static final String MIN_RARITY = "min_rarity";
    public static final String RADIUS_KM = "radius_km";
    public static final String POLL_SECONDS = "poll_seconds";
    public static final String CATEGORIES = "categories";
    public static final String ONLY_UNCAUGHT = "only_uncaught";
    public static final String UNCAUGHT_MIN = "uncaught_min_rarity";
    public static final String COLLECTION = "collection";
    public static final String WATCH = "watch_types";
    public static final String IGNORE = "ignore_types";
    public static final String INCLUDE_GROUND = "include_ground";
    public static final String INCLUDE_MILITARY = "include_military";
    public static final String QUIET_HOURS = "quiet_hours";
    public static final String QUIET_PAUSE = "quiet_pause";
    public static final String FIXED_LOCATION = "fixed_location";
    public static final String PRECISE = "precise_location";
    public static final String LAT = "lat";
    public static final String LON = "lon";
    public static final String STATUS = "status";
    public static final String STATUS_TIME = "status_time";
    public static final String LOG = "log";

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static double dbl(SharedPreferences p, String key, double def) {
        try {
            String v = p.getString(key, "");
            return v == null || v.trim().isEmpty() ? def : Double.parseDouble(v.trim());
        } catch (NumberFormatException | ClassCastException e) {
            return def;
        }
    }

    public static Settings toSettings(SharedPreferences p) {
        Settings s = new Settings();
        s.minRarity = dbl(p, MIN_RARITY, 8.0);
        s.radiusKm = clamp(dbl(p, RADIUS_KM, 100), 5, 460);
        s.pollSeconds = (int) clamp(dbl(p, POLL_SECONDS, 60), 20, 900);
        s.categories = Settings.lower(p.getString(CATEGORIES, ""));
        s.onlyUncaught = p.getBoolean(ONLY_UNCAUGHT, false);
        s.uncaughtMinRarity = dbl(p, UNCAUGHT_MIN, 0);
        String coll = p.getString(COLLECTION, "");
        s.collection = coll == null || coll.trim().isEmpty() ? null : Settings.codes(coll);
        s.watch = Settings.codes(p.getString(WATCH, ""));
        s.ignore = Settings.codes(p.getString(IGNORE, ""));
        s.includeGround = p.getBoolean(INCLUDE_GROUND, true);
        s.includeMilitary = p.getBoolean(INCLUDE_MILITARY, true);
        String q = p.getString(QUIET_HOURS, "");
        s.quietHours = QuietHours.parseOrNull(q) == null ? "" : q;
        s.quietPause = p.getBoolean(QUIET_PAUSE, false);
        return s;
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Returns {lat, lon} or null if not set or invalid. */
    public static double[] fixed(SharedPreferences p) {
        double lat = dbl(p, LAT, Double.NaN), lon = dbl(p, LON, Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
        return new double[]{lat, lon};
    }

    /** Append to a short rolling activity log shown in the app. */
    public static synchronized void log(Context c, String line) {
        SharedPreferences p = get(c);
        String stamp = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date());
        List<String> lines = new ArrayList<>(Arrays.asList(p.getString(LOG, "").split("\n")));
        lines.removeIf(String::isEmpty);
        lines.add(0, stamp + "  " + line);
        while (lines.size() > 40) lines.remove(lines.size() - 1);
        p.edit().putString(LOG, String.join("\n", lines)).apply();
    }
}
