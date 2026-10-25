package app.skyalert.core;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** All tunable behaviour. Plain object so core logic is testable without Android. */
public final class Settings {
    public double radiusKm = 100;
    public int pollSeconds = 60;
    public int queryDecimals = 2;

    public double minRarity = 8.0;
    public Set<String> categories = new HashSet<>();
    public double uncaughtMinRarity = 0;
    public boolean onlyUncaught = false;
    public Set<String> watch = new HashSet<>();
    public Set<String> ignore = new HashSet<>();
    public boolean includeGround = true;
    public boolean includeMilitary = true;
    public boolean skipHidden = true;
    public boolean skipExcluded = true;
    public boolean alertUnknown = false;
    /** Models you own. null means no collection configured. */
    public Set<String> collection = null;

    public long cooldownMs = 45 * 60_000L;
    public boolean liveUpdate = true;
    public boolean clearWhenGone = true;
    public String quietHours = "";
    public boolean quietPause = false;

    public static Set<String> codes(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        for (String t : text.split("[\\s,;]+")) {
            t = t.trim().toUpperCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static Set<String> lower(String text) {
        Set<String> out = new HashSet<>();
        for (String c : codes(text)) out.add(c.toLowerCase(Locale.ROOT));
        return out;
    }

    /** onlyUncaught needs a collection. */
    public boolean effectiveOnlyUncaught() {
        return onlyUncaught && collection != null;
    }
}
