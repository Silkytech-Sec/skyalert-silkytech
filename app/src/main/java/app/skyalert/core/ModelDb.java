package app.skyalert.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Skycards model list: download, cache, parse. */
public final class ModelDb {
    public static final String[] SOURCES = {
            "https://api.skycards.oldapes.com/models",
            "https://raw.githubusercontent.com/Skycards/Changes/main/models.json",
    };
    static final int MIN_EXPECTED = 200;

    private final File cacheFile;
    private final Http http;
    private final Logger log;
    private final long maxAgeMs;
    private volatile Map<String, Model> models = Collections.emptyMap();
    private volatile long loadedAt;
    public String[] sources = SOURCES;

    public ModelDb(File dir, Http http, Logger log, long maxAgeMs) {
        this.cacheFile = new File(dir, "models.json");
        this.http = http;
        this.log = log;
        this.maxAgeMs = maxAgeMs;
    }

    public Model get(String code) {
        return models.get(code);
    }

    public Map<String, Model> all() {
        return models;
    }

    public int size() {
        return models.size();
    }

    public static Map<String, Model> parse(String text) {
        Object root = Json.parse(text);
        List<Object> rows = Json.obj(root) != null ? Json.arr(Json.obj(root).get("rows")) : Json.arr(root);
        if (rows == null) throw new IllegalArgumentException("model data has no rows list");
        Map<String, Model> out = new HashMap<>();
        for (Object o : rows) {
            Map<String, Object> r = Json.obj(o);
            if (r == null) continue;
            String code = Json.str(r.get("id")).toUpperCase(Locale.ROOT);
            Object rareness = r.get("rareness");
            if (code.isEmpty() || !Json.isNum(rareness)) continue;
            String name = (niceManufacturer(Json.str(r.get("manufacturer"))) + " " + Json.str(r.get("name"))).trim();
            String cat = Json.str(r.get("cardCategory")).toLowerCase(Locale.ROOT);
            out.put(code, new Model(code, name.isEmpty() ? code : name,
                    Json.num(rareness, 0) / 100.0,
                    cat.isEmpty() ? "unknown" : cat,
                    (int) Json.num(r.get("xp"), 0),
                    Json.bool(r.get("excluded"))));
        }
        return out;
    }

    static String niceManufacturer(String m) {
        if (m.length() <= 4 || !m.equals(m.toUpperCase(Locale.ROOT))) return m;
        StringBuilder b = new StringBuilder();
        boolean start = true;
        for (char c : m.toCharArray()) {
            b.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
            start = !Character.isLetter(c);
        }
        return b.toString();
    }

    /** Load from cache if fresh, else download. Falls back to a stale cache. */
    public synchronized int load(boolean force) throws IOException {
        boolean have = cacheFile.exists();
        boolean fresh = have && System.currentTimeMillis() - cacheFile.lastModified() < maxAgeMs;
        Map<String, Model> parsed = null;
        if (fresh && !force) {
            try {
                parsed = parse(read());
            } catch (RuntimeException | IOException e) {
                log.log("Cached model list unreadable, downloading again");
            }
        }
        if (parsed == null) {
            for (String url : sources) {
                try {
                    String text = http.get(url, 60000);
                    Map<String, Model> m = parse(text);
                    if (m.size() < MIN_EXPECTED) throw new IOException("suspiciously small model list");
                    write(text);
                    parsed = m;
                    log.log("Downloaded Skycards model list from " + Http.host(url) + " (" + m.size() + " types)");
                    break;
                } catch (IOException | RuntimeException e) {
                    log.log("Model list download failed from " + Http.host(url) + " (" + e.getMessage() + ")");
                }
            }
        }
        if (parsed == null) {
            if (!have) throw new IOException("Could not download the Skycards model list. Check your connection.");
            log.log("Using stale cached model list");
            parsed = parse(read());
        }
        models = parsed;
        loadedAt = System.currentTimeMillis();
        return parsed.size();
    }

    public void maybeRefresh() {
        if (System.currentTimeMillis() - loadedAt > maxAgeMs) {
            try {
                load(true);
            } catch (IOException e) {
                log.log("Model refresh failed, keeping current list");
            }
        }
    }

    private String read() throws IOException {
        return new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8);
    }

    private void write(String text) throws IOException {
        File tmp = new File(cacheFile.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(cacheFile)) {
            cacheFile.delete();
            if (!tmp.renameTo(cacheFile)) throw new IOException("could not write model cache");
        }
    }
}
