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
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.skyalert.core.Http;
import app.skyalert.core.Logger;
import app.skyalert.core.ModelDb;
import app.skyalert.core.QuietHours;
import app.skyalert.core.Settings;
import app.skyalert.core.Tracker;
import app.skyalert.core.Traffic;

/** Foreground service that polls live traffic and raises alerts. */
public class WatcherService extends Service {
    public static final String ACTION_STOP = "app.skyalert.STOP";
    public static final String ACTION_DISMISS = "app.skyalert.DISMISS";
    public static final String EXTRA_HEX = "hex";
    static final String TAG = "skyalert";

    static volatile boolean running;

    private volatile boolean loop;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private Notifier notifier;
    private Tracker tracker;
    private ModelDb models;
    private Traffic traffic;
    private LocationWatcher location;
    private String locationMode = "";
    private int failures;
    private Logger logger;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, WatcherService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final Context app = getApplicationContext();
        logger = m -> {
            Log.i(TAG, m);
            Prefs.log(app, m);
        };
        notifier = new Notifier(this);
        Http http = new Http();
        models = new ModelDb(getFilesDir(), http, logger, 24 * 3600_000L);
        traffic = new Traffic(http);
        tracker = new Tracker(notifier, logger);
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
            if (hex != null) tracker.dismiss(hex);
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
            logger.log("Android blocked a background restart. Open skyalert to resume.");
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
                if (quiet && cfg.quietPause) {
                    status("Paused for quiet hours");
                    delay = cfg.pollSeconds * 1000L;
                } else {
                    if (models.size() == 0) models.load(false);
                    else models.maybeRefresh();
                    double[] here = here(p);
                    if (here == null) {
                        status(LocationWatcher.hasPermission(this)
                                ? "Waiting for a location fix"
                                : "No location permission. Open skyalert to fix");
                        delay = 30_000L;
                    } else {
                        List<Map<String, Object>> ac = traffic.fetch(here[0], here[1], cfg.radiusKm, cfg.queryDecimals);
                        Tracker.Summary sum = tracker.tick(ac, here[0], here[1], cfg, models,
                                System.currentTimeMillis(), quiet, cfg.pollSeconds);
                        status(sum.text(cfg.radiusKm, traffic.lastSource, quiet));
                        failures = 0;
                        delay = cfg.pollSeconds * 1000L;
                    }
                }
            } catch (Exception e) {
                failures++;
                delay = Math.min(300_000L, cfg.pollSeconds * 1000L * (1L << Math.min(failures, 4)));
                if (failures == 1 || failures % 10 == 0) logger.log("Poll failed: " + e.getMessage());
                status("Network trouble, retrying in " + (delay / 1000) + "s");
            }
            long sleep = Math.max(1000L, delay - (System.currentTimeMillis() - start));
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    @Override
    public void onDestroy() {
        loop = false;
        running = false;
        if (worker != null) worker.interrupt();
        location.stop();
        tracker.clearAll();
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
