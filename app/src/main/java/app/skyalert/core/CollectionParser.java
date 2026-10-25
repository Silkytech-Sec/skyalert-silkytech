package app.skyalert.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the models you own from either a plain list of ICAO type codes or the
 * skycards_user.json export from github.com/mfkp/skycards-export.
 */
public final class CollectionParser {
    private static final String[] KEYS = {"modelId", "model", "aircraftModelId", "aircraftModel",
            "typeCode", "icaoType", "aircraftType", "type"};

    public static final class Result {
        public final Set<String> codes;
        public final String note;

        Result(Set<String> codes, String note) {
            this.codes = codes;
            this.note = note;
        }
    }

    private CollectionParser() {}

    public static Result parse(String text, Set<String> known) {
        Object root;
        try {
            root = Json.parse(text.trim());
        } catch (IllegalArgumentException e) {
            root = null;
        }
        if (!(root instanceof Map) && !(root instanceof List)) {
            Set<String> codes = Settings.codes(text);
            Set<String> good = new HashSet<>(codes);
            good.retainAll(known);
            int bad = codes.size() - good.size();
            return new Result(good, good.size() + " models" + (bad > 0 ? ", " + bad + " unknown codes ignored" : ""));
        }
        Map<String, Object> top = Json.obj(root);
        Object cards = top != null && top.containsKey("cards") ? top.get("cards") : root;
        Set<String> found = new HashSet<>();

        Map<String, Object> cmap = Json.obj(cards);
        if (cmap != null) {
            Set<String> keyed = new HashSet<>();
            for (String k : cmap.keySet()) if (known.contains(k.toUpperCase(Locale.ROOT))) keyed.add(k.toUpperCase(Locale.ROOT));
            if (!keyed.isEmpty() && keyed.size() >= cmap.size() * 0.5) found = keyed;
            cards = new java.util.ArrayList<>(cmap.values());
        }
        List<Object> list = Json.arr(cards);
        if (found.isEmpty() && list != null) {
            for (Object c : list) {
                Map<String, Object> d = Json.obj(c);
                if (d != null) {
                    String hit = fromDict(d, known);
                    if (hit != null) found.add(hit);
                } else if (c instanceof String && known.contains(((String) c).toUpperCase(Locale.ROOT))) {
                    found.add(((String) c).toUpperCase(Locale.ROOT));
                }
            }
        }
        if (found.isEmpty()) scan(cards, known, found, 0);

        String note = found.size() + " models";
        Object expected = top != null ? top.get("numAircraftModels") : null;
        if (Json.isNum(expected)) {
            long exp = Math.round(Json.num(expected, 0));
            if (exp > 0 && Math.abs(exp - found.size()) > Math.max(3, exp * 0.05))
                note += " (export says " + exp + ", check the list)";
        }
        return new Result(found, note);
    }

    private static String fromDict(Map<String, Object> d, Set<String> known) {
        for (String k : KEYS) {
            Object v = d.get(k);
            if (v instanceof String && known.contains(((String) v).toUpperCase(Locale.ROOT)))
                return ((String) v).toUpperCase(Locale.ROOT);
            Map<String, Object> inner = Json.obj(v);
            if (inner != null && inner.get("id") instanceof String) {
                String id = ((String) inner.get("id")).toUpperCase(Locale.ROOT);
                if (known.contains(id)) return id;
            }
        }
        return null;
    }

    private static void scan(Object o, Set<String> known, Set<String> found, int depth) {
        if (depth > 6 || o == null) return;
        if (o instanceof Map) {
            for (Object v : ((Map<?, ?>) o).values()) scan(v, known, found, depth + 1);
        } else if (o instanceof Collection) {
            for (Object v : (Collection<?>) o) scan(v, known, found, depth + 1);
        } else if (o instanceof String) {
            String u = ((String) o).toUpperCase(Locale.ROOT);
            if (known.contains(u)) found.add(u);
        }
    }
}
