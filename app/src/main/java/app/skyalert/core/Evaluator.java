package app.skyalert.core;

import java.util.Locale;
import java.util.Map;

/** Turns raw ADS-B rows into sightings and decides what deserves an alert. */
public final class Evaluator {
    static final int FLAG_MILITARY = 1, FLAG_PIA = 4, FLAG_LADD = 8;

    private Evaluator() {}

    /** Describe any aircraft inside the radius, or null if outside or unusable. */
    public static Sighting describe(Map<String, Object> ac, double hereLat, double hereLon,
                                    double radiusKm, ModelDb models) {
        String hex = Json.str(ac.get("hex")).toLowerCase(Locale.ROOT);
        if (hex.isEmpty()) return null;
        Object lat = ac.get("lat"), lon = ac.get("lon");
        if (!Json.isNum(lat) || !Json.isNum(lon)) {
            Map<String, Object> lp = Json.obj(ac.get("lastPosition"));
            if (lp != null) { lat = lp.get("lat"); lon = lp.get("lon"); }
        }
        if (!Json.isNum(lat) || !Json.isNum(lon)) return null;
        double la = Json.num(lat, 0), lo = Json.num(lon, 0);
        double dist = Geo.distanceKm(hereLat, hereLon, la, lo);
        if (dist > radiusKm) return null;

        Sighting s = new Sighting();
        s.hex = hex;
        s.code = Json.str(ac.get("t")).toUpperCase(Locale.ROOT);
        s.distKm = dist;
        s.compass = Geo.compass(Geo.bearingDeg(hereLat, hereLon, la, lo));
        Object alt = ac.get("alt_baro");
        s.onGround = "ground".equals(alt);
        if (Json.isNum(alt)) s.altFt = (int) Math.round(Json.num(alt, 0));
        if (Json.isNum(ac.get("gs"))) s.groundSpeedKt = Json.num(ac.get("gs"), 0);
        s.reg = Json.str(ac.get("r"));
        s.callsign = Json.str(ac.get("flight"));
        int flags = (int) Json.num(ac.get("dbFlags"), 0);
        s.military = (flags & FLAG_MILITARY) != 0;
        s.hidden = (flags & (FLAG_PIA | FLAG_LADD)) != 0;

        Model m = s.code.isEmpty() ? null : models.get(s.code);
        if (m != null) {
            s.name = m.name;
            s.rarity = m.rarity;
            s.category = m.category;
            s.xp = m.xp;
            s.excluded = m.excluded;
        } else {
            String desc = Json.str(ac.get("desc"));
            s.name = !desc.isEmpty() ? desc : (!s.code.isEmpty() ? s.code : "Unknown type");
        }
        return s;
    }

    /** Returns the sighting with reasons filled in if it should alert, else null. */
    public static Sighting evaluate(Map<String, Object> ac, double hereLat, double hereLon,
                                    Settings cfg, ModelDb models) {
        Sighting s = describe(ac, hereLat, hereLon, cfg.radiusKm, models);
        if (s == null || s.code.isEmpty() || cfg.ignore.contains(s.code)) return null;
        boolean watched = cfg.watch.contains(s.code);
        if (s.onGround && !cfg.includeGround && !watched) return null;
        if (s.hidden && cfg.skipHidden && !watched) return null;
        if (s.military && !cfg.includeMilitary && !watched) return null;

        if (s.rarity == null) {
            if (watched) s.reasons.add("watchlist");
            else if (cfg.alertUnknown) s.reasons.add("unknown type");
            else return null;
            return s;
        }
        if (s.excluded && cfg.skipExcluded && !watched) return null;
        s.uncaught = cfg.collection != null && !cfg.collection.contains(s.code);
        if (watched) s.reasons.add("watchlist");
        if (s.uncaught && s.rarity >= cfg.uncaughtMinRarity) s.reasons.add("new card");
        if (!cfg.effectiveOnlyUncaught()) {
            if (s.rarity >= cfg.minRarity) s.reasons.add("rarity");
            else if (cfg.categories.contains(s.category)) s.reasons.add("category");
        }
        return s.reasons.isEmpty() ? null : s;
    }
}
