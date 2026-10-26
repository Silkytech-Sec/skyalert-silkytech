package app.skyalert;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

import app.skyalert.core.CollectionParser;
import app.skyalert.core.Evaluator;
import app.skyalert.core.Http;
import app.skyalert.core.ModelDb;
import app.skyalert.core.QuietHours;
import app.skyalert.core.Sighting;
import app.skyalert.core.Traffic;

public class MainActivity extends Activity {
    public static final String EXTRA_AUTOSTART = "autostart";
    static final int REQ_START = 1, REQ_BACKGROUND = 2, REQ_LOCATE = 3, REQ_IMPORT = 4;
    static final String ASKED_BATTERY = "asked_battery";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView status, activity, preview, collectionNote, permNote;
    private Button startStop;
    private EditText minRarity, radius, poll, categories, uncaughtMin, collection, watch, ignore, quiet, lat, lon;
    private CheckBox onlyUncaught, ground, military, quietPause, precise;
    private RadioButton follow, fixed;
    private ModelDb sharedModels;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            ui.postDelayed(this, 3000);
        }
    };

    // ------------------------------------------------------------------ UI

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(LinearLayout parent, String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(2));
        parent.addView(t);
        return t;
    }

    private void section(LinearLayout parent, String s) {
        TextView t = text(parent, s, 18, true);
        t.setPadding(0, dp(20), 0, dp(4));
    }

    private void hint(LinearLayout parent, String s) {
        TextView t = text(parent, s, 13, false);
        t.setAlpha(0.7f);
    }

    private EditText field(LinearLayout parent, String label, String hintText, int inputType) {
        text(parent, label, 14, false);
        EditText e = new EditText(this);
        e.setHint(hintText);
        e.setInputType(inputType);
        e.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        parent.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private CheckBox check(LinearLayout parent, String label) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        parent.addView(c);
        return c;
    }

    private Button button(LinearLayout parent, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private void build() {
        int num = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        int signed = num | InputType.TYPE_NUMBER_FLAG_SIGNED;
        int caps = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(48));
        scroll.addView(root);

        text(root, "Skyalert", 28, true);
        hint(root, "Alerts you when a rare Skycards aircraft enters your radar.");
        status = text(root, "", 15, false);
        status.setPadding(0, dp(12), 0, dp(8));
        startStop = button(root, "Start watching", v -> {
            if (WatcherService.isRunning()) stopWatching();
            else startFlow();
        });
        permNote = text(root, "", 13, false);
        permNote.setAlpha(0.85f);

        section(root, "Alerts");
        minRarity = field(root, "Minimum rarity", "8.0 (about every Ultra card)", num);
        hint(root, "Common under 2, uncommon 2 to 4, scarce 4 to 6, rare 6 to 8, ultra 8 and up.");
        categories = field(root, "Also alert on these categories", "for example: rare, ultra", InputType.TYPE_CLASS_TEXT);
        radius = field(root, "Radar radius (km)", "100", num);
        poll = field(root, "Check every (seconds)", "60", InputType.TYPE_CLASS_NUMBER);
        ground = check(root, "Include rare aircraft parked on the ground");
        military = check(root, "Include military aircraft");

        section(root, "My collection");
        hint(root, "List the ICAO type codes you already own to get NEW card alerts. Leave blank to skip.");
        collection = field(root, "Models I own", "A20N, B738, C172", caps | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        collection.setMinLines(2);
        collectionNote = text(root, "", 13, false);
        button(root, "Import skycards_user.json", v -> importCollection());
        uncaughtMin = field(root, "Minimum rarity for NEW card alerts", "0 (every new card)", num);
        onlyUncaught = check(root, "Only alert on models I don't own yet");

        section(root, "Watch and ignore lists");
        watch = field(root, "Always alert on these type codes", "B52, C5M, A3ST", caps);
        ignore = field(root, "Never alert on these type codes", "", caps);

        section(root, "Quiet hours");
        quiet = field(root, "Quiet hours", "21-7 or 22:30-06:45", InputType.TYPE_CLASS_TEXT);
        quietPause = check(root, "Pause completely during quiet hours (otherwise alerts arrive silently)");

        section(root, "Location");
        RadioGroup group = new RadioGroup(this);
        follow = new RadioButton(this);
        follow.setText("Follow my phone");
        follow.setId(View.generateViewId());
        fixed = new RadioButton(this);
        fixed.setText("Use a fixed location");
        fixed.setId(View.generateViewId());
        group.addView(follow);
        group.addView(fixed);
        root.addView(group);
        precise = check(root, "Use GPS for precise location (more battery)");
        lat = field(root, "Latitude", "43.46", signed);
        lon = field(root, "Longitude", "-80.52", signed);
        button(root, "Fill in my current location", v -> fillCurrentLocation());
        hint(root, "With Follow my phone, the fixed location is used only as a backup.");
        
        section(root, "Airport watchlist");
        hint(root, "Also watch airports, or any other place, besides your own location.");
        button(root, "Manage watchlist", v -> startActivity(new Intent(this, WatchlistActivity.class)));
        
        section(root, "Reliability");
        button(root, "Let Skyalert run in the background (battery)", v -> requestBattery());
        button(root, "Allow auto-start after reboot (location all the time)", v -> requestBackground());

        section(root, "Preview");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row);
        Button save = new Button(this);
        save.setText("Save settings");
        save.setAllCaps(false);
        save.setOnClickListener(v -> { if (save()) toast("Saved. Changes apply on the next check."); });
        Button prev = new Button(this);
        prev.setText("Show what's in range");
        prev.setAllCaps(false);
        prev.setOnClickListener(v -> runPreview());
        row.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(prev, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        preview = text(root, "", 12, false);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextIsSelectable(true);

        section(root, "Recent activity");
        activity = text(root, "", 12, false);
        activity.setTypeface(Typeface.MONOSPACE);

        text(root, "", 12, false);
        hint(root, "Live traffic from adsb.lol (ODbL) and adsb.fi. Rarity data from Skycards. "
                + "Not affiliated with Skycards or Flightradar24. Never uses your Skycards account.");

        setContentView(scroll);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Prefs.get(this);
        Notifier.createChannels(this);
        build();
        load();
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTOSTART, false)) startFlow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresher);
        saveQuietly();
        super.onPause();
    }

    private void load() {
        minRarity.setText(prefs.getString(Prefs.MIN_RARITY, "8.0"));
        categories.setText(prefs.getString(Prefs.CATEGORIES, ""));
        radius.setText(prefs.getString(Prefs.RADIUS_KM, "100"));
        poll.setText(prefs.getString(Prefs.POLL_SECONDS, "60"));
        ground.setChecked(prefs.getBoolean(Prefs.INCLUDE_GROUND, true));
        military.setChecked(prefs.getBoolean(Prefs.INCLUDE_MILITARY, true));
        collection.setText(prefs.getString(Prefs.COLLECTION, ""));
        uncaughtMin.setText(prefs.getString(Prefs.UNCAUGHT_MIN, "0"));
        onlyUncaught.setChecked(prefs.getBoolean(Prefs.ONLY_UNCAUGHT, false));
        watch.setText(prefs.getString(Prefs.WATCH, ""));
        ignore.setText(prefs.getString(Prefs.IGNORE, ""));
        quiet.setText(prefs.getString(Prefs.QUIET_HOURS, ""));
        quietPause.setChecked(prefs.getBoolean(Prefs.QUIET_PAUSE, false));
        boolean isFixed = prefs.getBoolean(Prefs.FIXED_LOCATION, false);
        follow.setChecked(!isFixed);
        fixed.setChecked(isFixed);
        precise.setChecked(prefs.getBoolean(Prefs.PRECISE, false));
        lat.setText(prefs.getString(Prefs.LAT, ""));
        lon.setText(prefs.getString(Prefs.LON, ""));
    }

    private static String t(EditText e) {
        return e.getText().toString().trim();
    }

    private void saveQuietly() {
        prefs.edit()
                .putString(Prefs.MIN_RARITY, t(minRarity))
                .putString(Prefs.CATEGORIES, t(categories))
                .putString(Prefs.RADIUS_KM, t(radius))
                .putString(Prefs.POLL_SECONDS, t(poll))
                .putBoolean(Prefs.INCLUDE_GROUND, ground.isChecked())
                .putBoolean(Prefs.INCLUDE_MILITARY, military.isChecked())
                .putString(Prefs.COLLECTION, t(collection))
                .putString(Prefs.UNCAUGHT_MIN, t(uncaughtMin))
                .putBoolean(Prefs.ONLY_UNCAUGHT, onlyUncaught.isChecked())
                .putString(Prefs.WATCH, t(watch))
                .putString(Prefs.IGNORE, t(ignore))
                .putString(Prefs.QUIET_HOURS, t(quiet))
                .putBoolean(Prefs.QUIET_PAUSE, quietPause.isChecked())
                .putBoolean(Prefs.FIXED_LOCATION, fixed.isChecked())
                .putBoolean(Prefs.PRECISE, precise.isChecked())
                .putString(Prefs.LAT, t(lat))
                .putString(Prefs.LON, t(lon))
                .apply();
    }

    /** Saves and validates. Returns false and explains the problem if something is off. */
    private boolean save() {
        saveQuietly();
        try {
            QuietHours.parse(t(quiet));
        } catch (IllegalArgumentException e) {
            toast(e.getMessage());
            return false;
        }
        if (fixed.isChecked() && Prefs.fixed(prefs) == null) {
            toast("Enter a valid latitude and longitude, or choose Follow my phone");
            return false;
        }
        if (onlyUncaught.isChecked() && t(collection).isEmpty()) {
            toast("\"Only models I don't own\" needs your collection list filled in");
            return false;
        }
        return true;
    }

    private void refreshStatus() {
        boolean on = WatcherService.isRunning();
        startStop.setText(on ? "Stop watching" : "Start watching");
        String s = prefs.getString(Prefs.STATUS, "");
        status.setText(on ? "Watching. " + s : "Not running." + (s.isEmpty() || s.equals("Stopped") ? "" : " Last status " + s));
        activity.setText(prefs.getString(Prefs.LOG, ""));
        List<String> notes = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notes.add("Notifications are off, so alerts can't appear.");
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName()))
            notes.add("Battery optimization is on, which can delay alerts. Tap the battery button below.");
        if (!fixed.isChecked() && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
            notes.add("After a reboot you'll get a tap-to-resume reminder. Allow location all the time to restart automatically.");
        permNote.setText(String.join("\n", notes));
    }

    // ------------------------------------------------------------------ start / stop

    private void startFlow() {
        if (!save()) return;
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!fixed.isChecked() && !LocationWatcher.hasPermission(this)) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_START);
            return;
        }
        reallyStart();
    }

    private void reallyStart() {
        prefs.edit().putBoolean(Prefs.ENABLED, true).apply();
        try {
            WatcherService.start(this);
        } catch (RuntimeException e) {
            toast("Android refused to start the service: " + e.getMessage());
            return;
        }
        toast("Watching for rare aircraft");
        ui.postDelayed(this::refreshStatus, 500);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName()) && !prefs.getBoolean(ASKED_BATTERY, false)) {
            prefs.edit().putBoolean(ASKED_BATTERY, true).apply();
            requestBattery();
        }
    }

    private void stopWatching() {
        prefs.edit().putBoolean(Prefs.ENABLED, false).apply();
        stopService(new Intent(this, WatcherService.class));
        ui.postDelayed(this::refreshStatus, 500);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_START) {
            if (!fixed.isChecked() && !LocationWatcher.hasPermission(this)) {
                toast("Location is needed to follow you. You can use a fixed location instead.");
                return;
            }
            reallyStart();
        } else if (code == REQ_LOCATE && LocationWatcher.hasPermission(this)) {
            fillCurrentLocation();
        } else if (code == REQ_BACKGROUND) {
            refreshStatus();
        }
    }

    private void requestBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void requestBackground() {
        if (!LocationWatcher.hasPermission(this)) {
            toast("Grant regular location first by tapping Start watching");
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            toast("Already allowed all the time");
            return;
        }
        toast("Choose \"Allow all the time\" on the next screen");
        requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BACKGROUND);
    }

    // ------------------------------------------------------------------ location helpers

    private void locateOnce(boolean forcePhone, Consumer<double[]> cb) {
        if (fixed.isChecked() && !forcePhone) {
            cb.accept(Prefs.fixed(prefs));
            return;
        }
        final double[] backup = forcePhone ? null : Prefs.fixed(prefs);
        if (!LocationWatcher.hasPermission(this)) {
            cb.accept(backup);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        try {
            for (String p : lm.getAllProviders()) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 10 * 60_000L) {
            cb.accept(new double[]{best.getLatitude(), best.getLongitude()});
            return;
        }
        final Location fallback = best;
        List<String> enabled = lm.getProviders(true);
        String provider = enabled.contains("fused") ? "fused"
                : enabled.contains(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                : enabled.contains(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
        if (provider == null || Build.VERSION.SDK_INT < 30) {
            cb.accept(fallback != null ? new double[]{fallback.getLatitude(), fallback.getLongitude()} : backup);
            return;
        }
        toast("Getting your location");
        try {
            lm.getCurrentLocation(provider, null, getMainExecutor(), loc -> {
                Location use = loc != null ? loc : fallback;
                cb.accept(use != null ? new double[]{use.getLatitude(), use.getLongitude()} : backup);
            });
        } catch (SecurityException | IllegalArgumentException e) {
            cb.accept(backup);
        }
    }

    private void fillCurrentLocation() {
        if (!LocationWatcher.hasPermission(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATE);
            return;
        }
        locateOnce(true, here -> {
            if (here == null) {
                toast("No location fix yet. Try again outdoors or turn on Location.");
                return;
            }
            lat.setText(String.format(Locale.ROOT, "%.4f", here[0]));
            lon.setText(String.format(Locale.ROOT, "%.4f", here[1]));
            saveQuietly();
        });
    }

    // ------------------------------------------------------------------ preview

    private synchronized ModelDb models() throws java.io.IOException {
        if (sharedModels == null) sharedModels = new ModelDb(getFilesDir(), new Http(), m -> { }, 24 * 3600_000L);
        if (sharedModels.size() == 0) sharedModels.load(false);
        return sharedModels;
    }

    private void runPreview() {
        if (!save()) return;
        preview.setText("Checking...");
        locateOnce(false, here -> {
            if (here == null) {
                preview.setText("No location. Grant location access, or set a fixed location.");
                return;
            }
            final app.skyalert.core.Settings cfg = Prefs.toSettings(prefs);
            new Thread(() -> {
                String out;
                try {
                    ModelDb db = models();
                    Traffic tr = new Traffic(new Http());
                    List<Map<String, Object>> ac = tr.fetch(here[0], here[1], cfg.radiusKm, cfg.queryDecimals);
                    out = formatPreview(ac, here, cfg, db, tr.lastSource);
                } catch (Exception e) {
                    out = "Couldn't check right now: " + e.getMessage();
                }
                final String result = out;
                ui.post(() -> preview.setText(result));
            }, "skyalert-preview").start();
        });
    }

    static String formatPreview(List<Map<String, Object>> ac, double[] here, app.skyalert.core.Settings cfg,
                                ModelDb db, String source) {
        List<Sighting> all = new ArrayList<>();
        java.util.Set<String> alerting = new java.util.HashSet<>();
        for (Map<String, Object> a : ac) {
            Sighting d = Evaluator.describe(a, here[0], here[1], cfg.radiusKm, db);
            if (d == null) continue;
            all.add(d);
            if (Evaluator.evaluate(a, here[0], here[1], cfg, db) != null) alerting.add(d.hex);
        }
        Collections.sort(all, (x, y) -> Double.compare(y.rarity == null ? -1 : y.rarity, x.rarity == null ? -1 : x.rarity));
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "%d aircraft within %d km (%s)%n* = would alert%n%n",
                all.size(), Math.round(cfg.radiusKm), source));
        int shown = 0;
        for (Sighting s : all) {
            if (shown++ >= 40) {
                b.append("... ").append(all.size() - 40).append(" more\n");
                break;
            }
            String r = s.rarity == null ? "  -  " : String.format(Locale.ROOT, "%5.2f", s.rarity);
            String name = s.name.length() > 22 ? s.name.substring(0, 22) : s.name;
            b.append(String.format(Locale.ROOT, "%s %-5s %s %-22s %3dkm %s%n",
                    alerting.contains(s.hex) ? "*" : " ", s.code.isEmpty() ? "?" : s.code, r, name,
                    Math.round(s.distKm), s.compass));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ collection import

    private void importCollection() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (ActivityNotFoundException e) {
            toast("No file picker available");
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_IMPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        collectionNote.setText("Reading...");
        new Thread(() -> {
            String note;
            String codes = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
                String textContent = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                CollectionParser.Result r = CollectionParser.parse(textContent, models().all().keySet());
                codes = String.join(", ", new TreeSet<>(r.codes));
                note = "Imported " + r.note;
            } catch (Exception e) {
                note = "Import failed: " + e.getMessage();
            }
            final String fNote = note, fCodes = codes;
            ui.post(() -> {
                collectionNote.setText(fNote);
                if (fCodes != null) {
                    collection.setText(fCodes);
                    saveQuietly();
                }
            });
        }, "skyalert-import").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
