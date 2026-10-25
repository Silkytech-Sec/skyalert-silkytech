package app.skyalert.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Alert state machine. Decides when to alert loudly, when to update
 * silently, and when to clear, then calls the Sink. Platform independent.
 */
public final class Tracker {
    public interface Sink {
        void alert(Sighting s, String title, String body, boolean loud);
        void update(Sighting s, String title, String body);
        void clear(String hex);
    }

    public static final class Summary {
        public int inRange;
        public int active;
        public Sighting rarest;
        public String text(double radiusKm, String source, boolean quiet) {
            String t = inRange + " aircraft within " + Math.round(radiusKm) + " km | "
                    + active + (active == 1 ? " alert" : " alerts") + " active";
            if (rarest != null && rarest.rarity != null)
                t += String.format(Locale.ROOT, " | top %s %.2f", rarest.code, rarest.rarity);
            if (source != null && !source.isEmpty()) t += " | " + source;
            if (quiet) t += " | quiet hours";
            return t;
        }
    }

    static final class State {
        boolean active;
        boolean dismissed;
        long lastAlert;
        long lastSeen;
        double dist;
        String title;
    }

    private final Sink sink;
    private final Logger log;
    private final Map<String, State> state = new HashMap<>();

    public Tracker(Sink sink, Logger log) {
        this.sink = sink;
        this.log = log;
    }

    /** User swiped the notification away. Stop updating it until it leaves and returns. */
    public synchronized void dismiss(String hex) {
        State st = state.get(hex);
        if (st != null) st.dismissed = true;
    }

    public synchronized int activeCount() {
        int n = 0;
        for (State st : state.values()) if (st.active) n++;
        return n;
    }

    public synchronized Summary tick(List<Map<String, Object>> aircraft, double lat, double lon,
                                     Settings cfg, ModelDb models, long now, boolean quiet, int pollSeconds) {
        long grace = Math.max(2L * pollSeconds * 1000, 150_000L);
        Map<String, Sighting> hits = new HashMap<>();
        Summary sum = new Summary();
        for (Map<String, Object> ac : aircraft) {
            Sighting any = Evaluator.describe(ac, lat, lon, cfg.radiusKm, models);
            if (any == null) continue;
            sum.inRange++;
            Sighting s = Evaluator.evaluate(ac, lat, lon, cfg, models);
            if (s != null) {
                hits.put(s.hex, s);
                if (s.rarity != null && (sum.rarest == null || s.rarity > sum.rarest.rarity)) sum.rarest = s;
            }
        }

        for (Sighting s : hits.values()) {
            State st = state.get(s.hex);
            String title = Format.title(s);
            if (st == null || !st.active) {
                boolean recent = st != null && now - st.lastAlert < cfg.cooldownMs;
                boolean loud = !recent && !quiet;
                sink.alert(s, title, Format.body(s, ""), loud);
                log.log((loud ? "ALERT " : "Back in range ") + title);
                State ns = new State();
                ns.active = true;
                ns.lastAlert = (loud || st == null) ? now : st.lastAlert;
                ns.lastSeen = now;
                ns.dist = s.distKm;
                ns.title = title;
                state.put(s.hex, ns);
            } else {
                if (cfg.liveUpdate && !st.dismissed)
                    sink.update(s, title, Format.body(s, Format.trend(st.dist, s.distKm)));
                st.lastSeen = now;
                st.dist = s.distKm;
            }
        }

        Iterator<Map.Entry<String, State>> it = state.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, State> e = it.next();
            State st = e.getValue();
            if (st.active && !hits.containsKey(e.getKey()) && now - st.lastSeen >= grace) {
                st.active = false;
                st.dismissed = false;
                log.log("Out of range " + st.title);
                if (cfg.clearWhenGone) sink.clear(e.getKey());
            }
            if (!st.active && now - st.lastSeen > 12 * 3600_000L) it.remove();
        }
        sum.active = activeCount();
        return sum;
    }

    /** Clear everything still showing, used on shutdown. */
    public synchronized void clearAll() {
        for (Map.Entry<String, State> e : state.entrySet()) if (e.getValue().active) sink.clear(e.getKey());
        state.clear();
    }
}
