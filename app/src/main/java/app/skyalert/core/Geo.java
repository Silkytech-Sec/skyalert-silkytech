package app.skyalert.core;

public final class Geo {
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private Geo() {}

    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0088;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double x = Math.sin(dl) * Math.cos(p2);
        double y = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(x, y)) + 360.0) % 360.0;
    }

    public static String compass(double deg) {
        return COMPASS[(int) Math.floor(((deg % 360 + 360) % 360 + 22.5) / 45.0) % 8];
    }
}
