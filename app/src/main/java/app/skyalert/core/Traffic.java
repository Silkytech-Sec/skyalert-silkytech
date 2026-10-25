package app.skyalert.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Live ADS-B traffic from adsb.lol, falling back to adsb.fi. */
public final class Traffic {
    public static final String[][] SOURCES = {
            {"adsb.lol", "https://api.adsb.lol/v2/lat/%s/lon/%s/dist/%d"},
            {"adsb.fi", "https://opendata.adsb.fi/api/v2/lat/%s/lon/%s/dist/%d"},
    };

    private final Http http;
    public String[][] sources = SOURCES;
    public volatile String lastSource = "";

    public Traffic(Http http) {
        this.http = http;
    }

    public static int queryNm(double radiusKm, int decimals) {
        double padKm = 111.0 * (0.5 * Math.pow(10, -decimals)) * 1.5;
        return (int) Math.min(250, Math.ceil((radiusKm + padKm) / 1.852));
    }

    public static String round(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + Math.max(0, decimals) + "f", v);
    }

    public List<Map<String, Object>> fetch(double lat, double lon, double radiusKm, int decimals) throws IOException {
        int nm = queryNm(radiusKm, decimals);
        String qlat = round(lat, decimals), qlon = round(lon, decimals);
        String lastErr = "";
        for (String[] src : sources) {
            String url = String.format(Locale.ROOT, src[1], qlat, qlon, nm);
            try {
                Map<String, Object> root = Json.obj(Json.parse(http.get(url, 25000)));
                if (root == null) throw new IOException("unexpected response");
                List<Object> ac = Json.arr(root.get("ac"));
                if (ac == null) ac = Json.arr(root.get("aircraft"));
                if (ac == null && !root.containsKey("ac") && !root.containsKey("aircraft"))
                    throw new IOException("response has no aircraft list");
                List<Map<String, Object>> out = new ArrayList<>();
                if (ac != null) for (Object o : ac) if (Json.obj(o) != null) out.add(Json.obj(o));
                lastSource = src[0];
                return out;
            } catch (IOException | RuntimeException e) {
                lastErr = src[0] + ": " + e.getMessage();
            }
        }
        throw new IOException("all traffic sources failed (" + lastErr + ")");
    }
}
