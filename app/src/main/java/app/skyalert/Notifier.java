package app.skyalert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import app.skyalert.core.Sighting;
import app.skyalert.core.Tracker;
import app.skyalert.core.WatchPlan;

/** Android notifications for alerts and the foreground status. */
public final class Notifier implements Tracker.Sink {
    public static final String SKYCARDS_PACKAGE = "com.flightradar24.skycards";
    static final String CH_ALERTS = "alerts";
    static final String CH_QUIET = "alerts_quiet";
    static final String CH_STATUS = "status";
    static final String GROUP = "skyalert.aircraft";
    public static final int STATUS_ID = 1;
    static final int PAUSED_ID = 2;

    private final Context ctx;
    private final NotificationManager nm;
    /** Sink for the user's own position, which is what this class always did. */
    private final Tracker.Sink home;

    public Notifier(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannels(this.ctx);
        this.home = sinkFor(WatchPlan.HOME, null);
    }

    public static void createChannels(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel alerts = new NotificationChannel(CH_ALERTS, "Rare aircraft", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Heads-up alert when a rare aircraft enters your radar");
        alerts.enableVibration(true);
        alerts.setVibrationPattern(new long[]{0, 200, 120, 200});
        alerts.enableLights(true);
        alerts.setLightColor(0xFF00BFFF);
        NotificationChannel quiet = new NotificationChannel(CH_QUIET, "Live updates and quiet alerts", NotificationManager.IMPORTANCE_LOW);
        quiet.setDescription("Silent distance updates, re-entries, and quiet hours alerts");
        NotificationChannel status = new NotificationChannel(CH_STATUS, "Running status", NotificationManager.IMPORTANCE_MIN);
        status.setDescription("Shows that Skyalert is watching");
        status.setShowBadge(false);
        nm.createNotificationChannel(alerts);
        nm.createNotificationChannel(quiet);
        nm.createNotificationChannel(status);
    }

    static int idFor(String hex) {
        try {
            return 1000 + (Integer.parseInt(hex, 16) & 0x0FFFFFFF);
        } catch (NumberFormatException e) {
            return 1000 + (hex.hashCode() & 0x0FFFFFFF);
        }
    }

    /** One notification per aircraft per place, so the same plane near two airports is two alerts. */
    static int idFor(String key, String hex) {
        int base = idFor(hex);
        if (WatchPlan.HOME.equals(key)) return base;
        return 1000 + (((base - 1000) + key.hashCode() * 31) & 0x0FFFFFFF);
    }

    static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    /** Opens Skycards, or its store page if it is not installed. */
    public static Intent skycardsIntent(Context ctx) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(SKYCARDS_PACKAGE);
        if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + SKYCARDS_PACKAGE));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /**
     * A sink for one watched place. The label (null for the user's own position) is
     * shown on each notification so you can tell which airport an alert is for.
     */
    public Tracker.Sink sinkFor(final String key, final String label) {
        return new Tracker.Sink() {
            @Override
            public void alert(Sighting s, String title, String body, boolean loud) {
                nm.notify(idFor(key, s.hex), build(key, label, s, title, body, loud));
            }

            @Override
            public void update(Sighting s, String title, String body) {
                nm.notify(idFor(key, s.hex), build(key, label, s, title, body, false));
            }

            @Override
            public void clear(String hex) {
                nm.cancel(idFor(key, hex));
            }
        };
    }

    private Notification build(String key, String label, Sighting s, String title, String body, boolean loud) {
        int id = idFor(key, s.hex);
        PendingIntent open = PendingIntent.getActivity(ctx, id, skycardsIntent(ctx), flags());
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://adsb.lol/?icao=" + Uri.encode(s.hex)));
        mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent map = PendingIntent.getActivity(ctx, id + 1, mapIntent, flags());
        Intent dismiss = new Intent(ctx, WatcherService.class).setAction(WatcherService.ACTION_DISMISS)
                .putExtra(WatcherService.EXTRA_HEX, s.hex)
                .putExtra(WatcherService.EXTRA_LOC, key);
        PendingIntent onDelete = PendingIntent.getService(ctx, id + 2, dismiss, flags());

        Notification.Builder b = new Notification.Builder(ctx, loud ? CH_ALERTS : CH_QUIET)
                .setSmallIcon(R.drawable.ic_stat_plane)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setDeleteIntent(onDelete)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setShowWhen(true)
                .setGroup(GROUP)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setColor(0xFF1E88E5)
                .addAction(new Notification.Action.Builder(null, "Open Skycards", open).build())
                .addAction(new Notification.Action.Builder(null, "Map", map).build());
        if (label != null) b.setSubText(label);
        return b.build();
    }

    @Override
    public void alert(Sighting s, String title, String body, boolean loud) {
        home.alert(s, title, body, loud);
    }

    @Override
    public void update(Sighting s, String title, String body) {
        home.update(s, title, body);
    }

    @Override
    public void clear(String hex) {
        home.clear(hex);
    }

    public Notification status(String text) {
        Intent app = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openApp = PendingIntent.getActivity(ctx, 10, app, flags());
        Intent stop = new Intent(ctx, WatcherService.class).setAction(WatcherService.ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(ctx, 11, stop, flags());
        return new Notification.Builder(ctx, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat_plane)
                .setContentTitle("Skyalert is watching")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                .build();
    }

    public void updateStatus(String text) {
        nm.notify(STATUS_ID, status(text));
    }

    /** Shown after reboot when we are not allowed to restart location tracking on our own. */
    public void pausedAfterBoot() {
        Intent app = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_AUTOSTART, true);
        PendingIntent pi = PendingIntent.getActivity(ctx, 12, app, flags());
        nm.notify(PAUSED_ID, new Notification.Builder(ctx, CH_QUIET)
                .setSmallIcon(R.drawable.ic_stat_plane)
                .setContentTitle("Skyalert is paused")
                .setContentText("Tap to resume watching for rare aircraft")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    public void cancelPaused() {
        nm.cancel(PAUSED_ID);
    }
}
