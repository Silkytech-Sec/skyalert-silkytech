package app.skyalert.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Format {
    private Format() {}

    public static String title(Sighting s) {
        String r = s.rarity != null ? String.format(Locale.ROOT, "%.2f", s.rarity) : "?";
        return (s.uncaught ? "NEW " : "") + s.category.toUpperCase(Locale.ROOT) + " " + r + " | " + s.name;
    }

    public static String body(Sighting s, String trend) {
        List<String> p = new ArrayList<>();
        p.add(Math.round(s.distKm) + " km " + s.compass + trend);
        if (s.onGround) {
            p.add("on ground");
        } else {
            if (s.altFt != null) p.add(String.format(Locale.US, "%,d ft", s.altFt));
            if (s.groundSpeedKt != null) p.add(Math.round(s.groundSpeedKt) + " kt");
        }
        String ident = join(" / ", s.callsign, s.reg);
        p.add(ident.isEmpty() ? s.hex.toUpperCase(Locale.ROOT) : ident);
        if (s.xp > 0) p.add(String.format(Locale.US, "%,d XP", s.xp));
        if (s.military) p.add("military");
        if (s.reasons.contains("watchlist")) p.add("watchlist");
        return String.join(" | ", p);
    }

    static String join(String sep, String... parts) {
        StringBuilder b = new StringBuilder();
        for (String x : parts) {
            if (x == null || x.isEmpty()) continue;
            if (b.length() > 0) b.append(sep);
            b.append(x);
        }
        return b.toString();
    }

    public static String trend(Double prevKm, double nowKm) {
        if (prevKm == null) return "";
        if (prevKm - nowKm > 0.5) return ", closing";
        if (nowKm - prevKm > 0.5) return ", moving away";
        return "";
    }
}
