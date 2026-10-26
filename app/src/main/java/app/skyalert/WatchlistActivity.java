package app.skyalert;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import app.skyalert.core.Watchlist;

/** Add and remove the airports (or any places) that are watched in addition to your own position. */
public class WatchlistActivity extends Activity {
    private SharedPreferences prefs;
    private List<Watchlist.Entry> items;
    private LinearLayout rows;
    private Button add;

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

    private EditText field(LinearLayout parent, String label, String hint, int inputType) {
        text(parent, label, 14, false);
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setSingleLine(true);
        parent.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Airport watchlist");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        prefs = Prefs.get(this);
        items = new ArrayList<>(Prefs.watchlist(this));

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(48));
        scroll.addView(root);

        TextView intro = text(root, "Watch airports, or any other place, as well as your own location. "
                + "Places take turns being checked, each about as often as your \"Check every\" setting, "
                + "but never faster than one request every 15 seconds in total.", 14, false);
        intro.setAlpha(0.8f);

        CheckBox home = new CheckBox(this);
        home.setText("Also watch my own location");
        home.setChecked(prefs.getBoolean(Prefs.WATCH_HOME, true));
        home.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(Prefs.WATCH_HOME, on).apply();
            WatcherService.nudge();
        });
        root.addView(home);

        text(root, "Places", 18, true).setPadding(0, dp(20), 0, dp(4));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows);

        add = new Button(this);
        add.setAllCaps(false);
        add.setOnClickListener(v -> showAddDialog());
        root.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tip = text(root, "Tip: in a maps app, long-press an airport to see its coordinates. "
                + "Each place uses its own radius, and the radius on the main screen is used for your own location.", 13, false);
        tip.setAlpha(0.7f);

        setContentView(scroll);
        render();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void render() {
        rows.removeAllViews();
        if (items.isEmpty()) {
            TextView none = text(rows, "No places added yet.", 14, false);
            none.setAlpha(0.7f);
        }
        for (final Watchlist.Entry e : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            text(info, e.name, 16, true);
            TextView detail = text(info, e.describe(), 13, false);
            detail.setAlpha(0.7f);
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            Button remove = new Button(this);
            remove.setText("Remove");
            remove.setAllCaps(false);
            remove.setOnClickListener(v -> remove(e));
            row.addView(remove, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rows.addView(row);
        }
        boolean room = items.size() < Watchlist.MAX;
        add.setEnabled(room);
        add.setText(room ? "Add a place" : "The list is full (" + Watchlist.MAX + " places)");
    }

    private void save() {
        Prefs.saveWatchlist(this, items);
        WatcherService.nudge();
    }

    private void remove(Watchlist.Entry e) {
        items.remove(e);
        save();
        render();
        Toast.makeText(this, "Removed " + e.name, Toast.LENGTH_SHORT).show();
    }

    private void showAddDialog() {
        int num = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        int signed = num | InputType.TYPE_NUMBER_FLAG_SIGNED;

        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        sv.addView(box);
        final EditText name = field(box, "Name", "for example Heathrow", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final EditText lat = field(box, "Latitude", "51.4700", signed);
        final EditText lon = field(box, "Longitude", "-0.4543", signed);
        final EditText radius = field(box, "Radius in km (blank for " + (int) Watchlist.DEFAULT_RADIUS_KM + ")", String.valueOf((int) Watchlist.DEFAULT_RADIUS_KM), num);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add a place")
                .setView(sv)
                .setPositiveButton("Add", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        // Set the click handler after show() so a bad entry keeps the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                Watchlist.Entry e = Watchlist.Entry.fromInput(
                        name.getText().toString(), lat.getText().toString(),
                        lon.getText().toString(), radius.getText().toString());
                for (Watchlist.Entry x : items) {
                    if (x.key().equals(e.key())) throw new IllegalArgumentException("That location is already in the list");
                }
                if (items.size() >= Watchlist.MAX) throw new IllegalArgumentException("The list is full");
                items.add(e);
                save();
                render();
                dialog.dismiss();
            } catch (IllegalArgumentException ex) {
                Toast.makeText(this, ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
}
