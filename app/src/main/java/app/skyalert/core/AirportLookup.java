package app.skyalert.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

/**
 * Turns an airport code (ICAO like EGLL, or IATA like LHR) into coordinates using the
 * public OurAirports list. Only the public file is downloaded: the code you type and
 * your own position are never sent anywhere.
 */
public final class AirportLookup {
    public static final String URL = "https://davidmegginson.github.io/ourairports-data/airports.csv";
    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final long MAX_MILLIS = 120_000L;

    private AirportLookup() {}

    public static final class Result {
        /** Short label, normally the 4-letter ICAO code. */
        public final String code;
        /** Full airport name. */
        public final String name;
        public final double lat;
        public final double lon;

        Result(String code, String name, double lat, double lon) {
            this.code = code;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** Upper-cases and checks a typed code. Throws IllegalArgumentException with a readable message. */
    public static String normalize(String raw) {
        String c = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!c.matches("[A-Z0-9]{3,4}"))
            throw new IllegalArgumentException("Enter a 4-letter ICAO code such as EGLL, or a 3-letter IATA code such as LHR");
        return c;
    }

    /** Downloads the list (stopping as soon as the airport is found). Returns null if there is no such airport. */
    public static Result lookup(String rawCode, BooleanSupplier cancelled) throws IOException {
        return lookupFrom(URL, rawCode, cancelled);
    }

    /** Same as lookup, from any address (used by the tests). */
    static Result lookupFrom(String address, String rawCode, BooleanSupplier cancelled) throws IOException {
        String code = normalize(rawCode);
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", Http.USER_AGENT);
        c.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int rc = c.getResponseCode();
            if (rc < 200 || rc >= 300) throw new IOException("HTTP " + rc + " from " + Http.host(address));
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
            in = new Limited(in, cancelled);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
                return find(r, code);
            }
        } finally {
            c.disconnect();
        }
    }

    /** Stops runaway downloads: too many bytes, too long, or cancelled by the user. */
    private static final class Limited extends InputStream {
        private final InputStream in;
        private final BooleanSupplier cancelled;
        private final long deadline = System.currentTimeMillis() + MAX_MILLIS;
        private long count;

        Limited(InputStream in, BooleanSupplier cancelled) {
            this.in = in;
            this.cancelled = cancelled;
        }

        private void check() throws IOException {
            if (cancelled != null && cancelled.getAsBoolean()) throw new IOException("cancelled");
            if (System.currentTimeMillis() > deadline) throw new IOException("the download took too long");
            if (count > MAX_BYTES) throw new IOException("the airport list is unexpectedly large");
        }

        @Override
        public int read() throws IOException {
            check();
            int b = in.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            check();
            int n = in.read(buf, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** Scans the CSV for the code. Columns are found by header name, so column order doesn't matter. */
    public static Result find(BufferedReader r, String code) throws IOException {
        String headerLine = readRecord(r);
        if (headerLine == null) throw new IOException("The airport list was empty");
        if (headerLine.startsWith("\uFEFF")) headerLine = headerLine.substring(1);
        List<String> header = splitCsv(headerLine);
        int iIdent = index(header, "ident"), iType = index(header, "type"), iName = index(header, "name");
        int iLat = index(header, "latitude_deg"), iLon = index(header, "longitude_deg");
        int iIcao = index(header, "icao_code"), iGps = index(header, "gps_code"), iIata = index(header, "iata_code");
        if (iIdent < 0 || iName < 0 || iLat < 0 || iLon < 0)
            throw new IOException("The airport list has an unexpected format");

        boolean byIata = code.length() == 3;
        Result best = null;
        int bestRank = Integer.MAX_VALUE;
        String rec;
        while ((rec = readRecord(r)) != null) {
            List<String> f = splitCsv(rec);
            String type = get(f, iType);
            if ("closed".equals(type)) continue;
            String ident = get(f, iIdent).toUpperCase(Locale.ROOT);
            boolean exact = ident.equals(code);
            boolean alt = byIata
                    ? get(f, iIata).equalsIgnoreCase(code)
                    : get(f, iIcao).equalsIgnoreCase(code) || get(f, iGps).equalsIgnoreCase(code);
            if (!exact && !alt) continue;
            Result cand = build(f, code, ident, iName, iLat, iLon, iIcao);
            if (cand == null) continue;
            if (exact) return cand;
            int rank = rank(type);
            if (rank < bestRank) {
                best = cand;
                bestRank = rank;
            }
            if (byIata && rank <= 1) return best; // a large or medium airport: good enough, stop downloading
        }
        return best;
    }

    private static Result build(List<String> f, String code, String ident, int iName, int iLat, int iLon, int iIcao) {
        double lat, lon;
        try {
            lat = Double.parseDouble(get(f, iLat));
            lon = Double.parseDouble(get(f, iLon));
        } catch (NumberFormatException e) {
            return null;
        }
        if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180)) return null;
        String label = get(f, iIcao).toUpperCase(Locale.ROOT);
        if (!label.matches("[A-Z0-9]{3,4}")) label = ident.matches("[A-Z0-9]{3,4}") ? ident : code;
        String name = get(f, iName);
        return new Result(label, name.isEmpty() ? label : name, lat, lon);
    }

    private static int rank(String type) {
        switch (type) {
            case "large_airport": return 0;
            case "medium_airport": return 1;
            case "small_airport": return 2;
            case "seaplane_base": return 3;
            case "heliport": return 4;
            default: return 5;
        }
    }

    private static int index(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) if (header.get(i).trim().equalsIgnoreCase(name)) return i;
        return -1;
    }

    private static String get(List<String> f, int i) {
        return i >= 0 && i < f.size() ? f.get(i).trim() : "";
    }

    /** Reads one CSV record, joining lines when a quoted field contains a line break. */
    static String readRecord(BufferedReader r) throws IOException {
        String line = r.readLine();
        if (line == null) return null;
        StringBuilder rec = new StringBuilder(line);
        int quotes = count(line);
        while ((quotes & 1) == 1 && rec.length() < 100_000) {
            String next = r.readLine();
            if (next == null) break;
            rec.append('\n').append(next);
            quotes += count(next);
        }
        return rec.toString();
    }

    private static int count(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '"') n++;
        return n;
    }

    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
