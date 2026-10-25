package app.skyalert;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/** Restarts watching after a reboot or an app update, if it was on before. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        SharedPreferences p = Prefs.get(c);
        if (!p.getBoolean(Prefs.ENABLED, false)) return;
        boolean fixed = p.getBoolean(Prefs.FIXED_LOCATION, false) && Prefs.fixed(p) != null;
        boolean background = c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (fixed || background) {
            try {
                WatcherService.start(c);
                return;
            } catch (RuntimeException e) {
                // fall through to the reminder notification
            }
        }
        new Notifier(c).pausedAfterBoot();
    }
}
