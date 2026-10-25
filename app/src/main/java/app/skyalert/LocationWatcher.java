package app.skyalert;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.HandlerThread;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a recent location fix with low battery cost. Uses the passive
 * provider (free fixes other apps already requested) plus a slow network or
 * fused request, and GPS only when precise mode is on.
 */
final class LocationWatcher implements LocationListener {
    static final long INTERVAL_MS = 5 * 60_000L;
    static final String FUSED = "fused";

    private final Context ctx;
    private final LocationManager lm;
    private HandlerThread thread;
    private volatile Location best;
    private boolean started;

    LocationWatcher(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    synchronized void start(boolean precise) {
        if (started || !hasPermission(ctx) || lm == null) return;
        thread = new HandlerThread("skyalert-location");
        thread.start();
        try {
            for (String p : lm.getAllProviders()) consider(lm.getLastKnownLocation(p));
            List<String> enabled = lm.getProviders(true);
            List<String> want = new ArrayList<>();
            if (precise) want.add(LocationManager.GPS_PROVIDER);
            if (enabled.contains(FUSED)) want.add(FUSED);
            else if (enabled.contains(LocationManager.NETWORK_PROVIDER)) want.add(LocationManager.NETWORK_PROVIDER);
            if (want.isEmpty() && enabled.contains(LocationManager.GPS_PROVIDER)) want.add(LocationManager.GPS_PROVIDER);
            for (String p : want) {
                if (enabled.contains(p)) lm.requestLocationUpdates(p, INTERVAL_MS, 200f, this, thread.getLooper());
            }
            if (enabled.contains(LocationManager.PASSIVE_PROVIDER))
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 60_000L, 100f, this, thread.getLooper());
            started = true;
        } catch (SecurityException | IllegalArgumentException e) {
            stop();
        }
    }

    synchronized void stop() {
        try {
            if (lm != null) lm.removeUpdates(this);
        } catch (RuntimeException ignored) {
        }
        if (thread != null) thread.quitSafely();
        thread = null;
        started = false;
    }

    boolean isStarted() {
        return started;
    }

    /** Most recent usable fix as {lat, lon, ageMinutes}, or null. */
    double[] current() {
        Location l = best;
        if (l == null) return null;
        double age = (System.currentTimeMillis() - l.getTime()) / 60000.0;
        return new double[]{l.getLatitude(), l.getLongitude(), age};
    }

    private void consider(Location l) {
        if (l == null) return;
        Location b = best;
        if (b == null) { best = l; return; }
        long dt = l.getTime() - b.getTime();
        boolean muchNewer = dt > 2 * 60_000L;
        boolean similarTime = Math.abs(dt) <= 2 * 60_000L;
        boolean moreAccurate = l.hasAccuracy() && (!b.hasAccuracy() || l.getAccuracy() < b.getAccuracy());
        if (muchNewer || (similarTime && moreAccurate) || (dt > 0 && !b.hasAccuracy())) best = l;
    }

    @Override
    public void onLocationChanged(Location location) {
        consider(location);
    }

    // Android 10 declares these as abstract, so they must be implemented.
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }
}
