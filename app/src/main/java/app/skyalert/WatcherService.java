package app.skyalert;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.skyalert.core.Http;
import app.skyalert.core.Logger;
import app.skyalert.core.ModelDb;
import app.skyalert.core.QuietHours;
import app.skyalert.core.Settings;
import app.skyalert.core.Tracker;
import app.skyalert.core.Traffic;
import app.skyalert.core.WatchPlan;
import app.skyalert.core.Watchlist;

/**
 * Foreground service that polls live traffic and raises alerts. It takes turns
 * checking your own position and each watchlist place, one place per check,
 * and keeps a separate alert tracker for every place.
 */
public class WatcherService extends Service {
    public static final String ACTION_STOP = "app.skyalert.STOP";
    public static final String ACTION_DISMISS = "app.skyalert.DISMISS";
    public static final String EXTRA_HEX = "hex";
    public static final String EXTRA_LOC = "loc";
    static final String TAG = "skyalert";

    static volatile boolean running;
    private static volatile WatcherService instance;

    private volatile boolean loop;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private Notifier notifier;
    private final Map<String, Tracker> trackers = new ConcurrentHashMap<>();
    private ModelDb models;
    private Traffic traffic;
    private LocationWatcher location;
    private String locationMode = "";
    private int failures;
    private int cursor;
    private Logger logger;

    // Worker thread only: the latest status line for each place.
    private final Map<String, String> lines = new LinkedHashMap<>();

    private final Object sleepLock = new Object();
    private boolean wakeRequested;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, WatcherService.class));
    }

    /** Ask a running service to re-read its settings and check again right away. */
    public static void nudge() {
        WatcherService s = instance;
        if (s != null) s.requestPoll();
    }

    private void requestPoll() {
        synchronized (sleepLock) {
            wakeRequested = true;
            sleepLock.notifyAll();
        }
    }

    private void sleepFor(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        synchronized (sleepLock) {
            while (!wakeRequested) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                sleepLock.wait(left);
            }
            wakeRequested = false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        final Context app = getApplicationContext();
        logger = m -> {
            Log.i(TAG, m);
            Prefs.log(app, m);
        };
        notifier = new Notifier(this);
        Http http = new Http();
        models = new ModelDb(getFilesDir(), http, logger, 24 * 3600_000L);
        traffic = new Traffic(http);
        location = new LocationWatcher(this);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "skyalert:poll");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            Prefs.get(this).edit().putBoolean(Prefs.ENABLED, false).apply();
            logger.log("Stopped from notification");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_DISMISS.equals(action)) {
            String hex = intent.getStringExtra(EXTRA_HEX);
            String loc = intent.getStringExtra(EXTRA_LOC);
            Tracker t = trackers.get(loc == null ? WatchPlan.HOME : loc);
            if (hex != null && t != null) t.dismiss(hex);
            if (!loop) stopSelf();
            return START_STICKY;
        }
        if (!goForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!loop) startWorker();
        return START_STICKY;
    }

    private boolean fixedMode(SharedPreferences p) {
        return p.getBoolean(Prefs.FIXED_LOCATION, false) && Prefs.fixed(p) != null;
    }

    /** Returns false if Android refused to let us run in the foreground. */
    private boolean goForeground() {
        try {
            goForegroundUnsafe();
            return true;
        } catch (RuntimeException e) {
            logger.log("Android blocked a background restart. Open Skyalert to resume.");
            notifier.pausedAfterBoot();
            return false;
        }
    }

    private void goForegroundUnsafe() {
        Notification n = notifier.status("Starting up");
        SharedPreferences p = Prefs.get(this);
        boolean useLocation = !fixedMode(p) && LocationWatcher.hasPermission(this);
        if (Build.VERSION.SDK_INT >= 34) {
            int type = useLocation ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            try {
                startForeground(Notifier.STATUS_ID, n, type);
            } catch (RuntimeException e) {
                // Location type is refused when started from the background without
                // "Allow all the time". Run anyway and use the fixed location if set.
                logger.log("Location access limited in background, using fallback");
                startForeground(Notifier.STATUS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            }
        } else if (useLocation) {
            startForeground(Notifier.STATUS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(Notifier.STATUS_ID, n);
        }
        running = true;
        notifier.cancelPaused();
    }

    private void startWorker() {
        loop = true;
        wakeLock.acquire();
        worker = new Thread(this::runLoop, "skyalert-poll");
        worker.start();
        logger.log("Started watching");
    }

    private void ensureLocation(SharedPreferences p) {
        String mode = fixedMode(p) ? "fixed" : (p.getBoolean(Prefs.PRECISE, false) ? "precise" : "battery");
        if (mode.equals(locationMode) && (mode.equals("fixed") || location.isStarted())) return;
        location.stop();
        if (!mode.equals("fixed")) location.start(mode.equals("precise"));
        locationMode = mode;
    }

    /** {lat, lon} from fixed setting or the phone, else null. */
    private double[] here(SharedPreferences p) {
        if (fixedMode(p)) return Prefs.fixed(p);
        double[] cur = location.current();
        if (cur != null) return new double[]{cur[0], cur[1]};
        return Prefs.fixed(p);
    }

    private void status(String text) {
        String stamp = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String full = text + " | " + stamp;
        Prefs.get(this).edit().putString(Prefs.STATUS, full).putLong(Prefs.STATUS_TIME, System.currentTimeMillis()).apply();
        if (loop) notifier.updateStatus(full);
    }

    /** Drops trackers (and their notifications) for places that are no longer wanted. */
    private void pruneTrackers(Settings cfg) {
        Set<String> wanted = new HashSet<>();
        if (cfg.watchHome) wanted.add(WatchPlan.HOME);
        for (Watchlist.Entry e : cfg.watchlist) wanted.add(e.key());
        Iterator<Map.Entry<String, Tracker>> it = trackers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Tracker> en = it.next();
            if (!wanted.contains(en.getKey())) {
                en.getValue().clearAll();
                it.remove();
            }
        }
        lines.keySet().retainAll(wanted);
    }

    private Tracker trackerFor(final WatchPlan.Target t) {
        Tracker existing = trackers.get(t.key);
        if (existing != null) return existing;
        Logger lg = t.isHome() ? logger : m -> logger.log(t.label + ": " + m);
        Tracker created = new Tracker(notifier.sinkFor(t.key, t.isHome() ? null : t.label), lg);
        trackers.put(t.key, created);
        return created;
    }

    private String idleMessage(Settings cfg) {
        if (!cfg.watchHome && cfg.watchlist.isEmpty()) return "Nothing to watch. Add a place or turn on My location.";
        return LocationWatcher.hasPermission(this)
                ? "Waiting for a location fix"
                : "No location permission. Open Skyalert to fix";
    }

    private void reportStatus(List<WatchPlan.Target> targets, WatchPlan.Target t, Tracker.Summary sum, boolean quiet) {
        if (targets.size() == 1) {
            lines.clear();
            status(sum.text(t.radiusKm, traffic.lastSource, quiet));
            return;
        }
        lines.put(t.key, t.label + ": " + sum.inRange + " in range, " + sum.active + (sum.active == 1 ? " alert" : " alerts"));
        StringBuilder b = new StringBuilder();
        for (WatchPlan.Target x : targets) {
            String l = lines.get(x.key);
            if (l == null) continue;
            if (b.length() > 0) b.append('\n');
            b.append(l);
        }
        if (quiet) b.append(" | quiet hours");
        status(b.toString());
    }

    private void runLoop() {
        while (loop) {
            long start = System.currentTimeMillis();
            long delay;
            SharedPreferences p = Prefs.get(this);
            Settings cfg = Prefs.toSettings(p);
            try {
                Calendar cal = Calendar.getInstance();
                QuietHours q = QuietHours.parseOrNull(cfg.quietHours);
                boolean quiet = q != null && q.contains(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE));
                ensureLocation(p);
                pruneTrackers(cfg);
                List<WatchPlan.Target> targets = WatchPlan.targets(cfg.watchHome, here(p), cfg.radiusKm, cfg.watchlist);
                if (quiet && cfg.quietPause) {
                    status("Paused for quiet hours");
                    delay = cfg.pollSeconds * 1000L;
                } else if (targets.isEmpty()) {
                    status(idleMessage(cfg));
                    delay = 30_000L;
                } else {
                    if (models.size() == 0) models.load(false);
                    else models.maybeRefresh();
                    int n = targets.size();
                    int idx = cursor % n;
                    cursor = idx + 1;
                    WatchPlan.Target t = targets.get(idx);
                    cfg.radiusKm = t.radiusKm;
                    List<Map<String, Object>> ac = traffic.fetch(t.lat, t.lon, t.radiusKm, cfg.queryDecimals);
                    Tracker.Summary sum = trackerFor(t).tick(ac, t.lat, t.lon, cfg, models,
                            System.currentTimeMillis(), quiet, WatchPlan.perLocationSeconds(cfg.pollSeconds, n));
                    reportStatus(targets, t, sum, quiet);
                    failures = 0;
                    delay = WatchPlan.stepMs(cfg.pollSeconds, n);
                }
            } catch (Exception e) {
                failures++;
                delay = Math.min(300_000L, cfg.pollSeconds * 1000L * (1L << Math.min(failures, 4)));
                if (failures == 1 || failures % 10 == 0) logger.log("Poll failed: " + e.getMessage());
                status("Network trouble, retrying in " + (delay / 1000) + "s");
            }
            long sleep = Math.max(1000L, delay - (System.currentTimeMillis() - start));
            try {
                sleepFor(sleep);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    @Override
    public void onDestroy() {
        loop = false;
        running = false;
        instance = null;
        if (worker != null) worker.interrupt();
        location.stop();
        for (Tracker t : trackers.values()) t.clearAll();
        trackers.clear();
        if (wakeLock.isHeld()) wakeLock.release();
        Prefs.get(this).edit().putString(Prefs.STATUS, "Stopped").apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
