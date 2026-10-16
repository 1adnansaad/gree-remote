package com.greeac;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

public class MainActivity extends Activity {
    static final int POLL_MS = 5000; // units don't report changes made with the IR remote, so ask
    static final int SCAN_QUIET_MS = 2000; // scan ends once no unit has answered for this long
    static final int MIN_T = 16, MAX_T = 30; // °C
    static final int ON = 0xFF1E88E5, OFF = 0xFF424242; // button tint: what's on, the rest

    final ExecutorService net = Executors.newSingleThreadExecutor(); // one request at a time: no network on the UI thread
    final Handler ui = new Handler(Looper.getMainLooper());
    final List<Runnable> marks = new ArrayList<>(); // each highlights its button from st and prefs
    Map<String, Integer> st = new HashMap<>(); // the unit's last known settings; UI thread only
    List<Gree.Device> acs = new ArrayList<>(); // the last scan's
    Prefs prefs;
    boolean scanned; // UI thread only
    volatile Gree.Device dev;
    Button scanBtn;
    TextView msg, title, room, temp;
    LinearLayout list, panel;

    final Runnable poll = new Runnable() {
        @Override public void run() {
            net.execute(MainActivity.this::refresh);
            ui.postDelayed(this, POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        scanBtn = button("Scan network", v -> scan());
        msg = text(16);
        list = vertical();
        title = text(22);
        room = text(16);
        temp = text(40);
        temp.setGravity(Gravity.CENTER);

        panel = vertical();
        panel.setVisibility(View.GONE); // until a unit answers
        panel.setPadding(0, dp(16), 0, 0);
        panel.addView(title);
        panel.addView(room);
        panel.addView(row(ctl("Power", "Pow", -1)));
        panel.addView(row(button("−", v -> setTemp(-1)), temp, button("+", v -> setTemp(1))));
        panel.addView(label("Mode"));
        panel.addView(row(ctl("Auto", "Mod", 0), ctl("Cool", "Mod", 1), ctl("Dry", "Mod", 2), ctl("Fan", "Mod", 3),
                ctl("Heat", "Mod", 4)));
        panel.addView(label("Fan"));
        panel.addView(row(ctl("Auto", "WdSpd", 0), ctl("Low", "WdSpd", 1), ctl("Med", "WdSpd", 3), ctl("High", "WdSpd", 5)));
        panel.addView(label("Extras"));
        panel.addView(row(ctl("Swing", "SwUpDn", -1), ctl("Turbo", "Tur", -1), ctl("Quiet", "Quiet", -1),
                ctl("Sleep", "SwhSlp", -1)));
        panel.addView(row(ctl("Light", "Lig", -1), ctl("X-Fan", "Blo", -1), ctl("Health", "Health", -1),
                ctl("Eco", "SvSt", -1)));
        panel.addView(label("This AC"));
        panel.addView(row(button("Rename", v -> rename()), lit(button("Pin", v -> pin()), () -> prefs.pinned(dev)),
                lit(button("Beep", v -> beep()), () -> prefs.beeps(dev))));

        LinearLayout root = vertical();
        root.addView(scanBtn);
        root.addView(msg);
        root.addView(list);
        root.addView(panel);
        ScrollView sv = new ScrollView(this);
        int pad = dp(12);
        sv.setOnApplyWindowInsetsListener((v, in) -> {
            v.setPadding(in.getSystemWindowInsetLeft() + pad, in.getSystemWindowInsetTop() + pad,
                    in.getSystemWindowInsetRight() + pad, in.getSystemWindowInsetBottom() + pad);
            return in;
        });
        sv.addView(root);
        setContentView(sv);
        Gree.Device p = prefs.pinnedAc();
        if (p == null) {
            scan();
        } else {
            Watch.start(this); // in case it was stopped
            select(p);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ui.post(poll); // and now: it may have been changed while we were away
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        net.shutdownNow();
    }

    void scan() {
        scanned = true;
        scanBtn.setEnabled(false);
        msg.setText("Scanning…");
        net.execute(() -> {
            List<Gree.Device> found;
            String err = null;
            try { found = Gree.scan(Gree.targets(), SCAN_QUIET_MS); } catch (Exception e) { found = new ArrayList<>(); err = "Scan failed: " + e; }
            List<Gree.Device> f = found;
            String m = err != null ? err : f.isEmpty() ? "No AC answered. Is this phone on the same Wi-Fi as the AC?"
                    : "Found " + f.size() + (f.size() == 1 ? " AC" : " ACs, tap one");
            runOnUiThread(() -> {
                scanBtn.setEnabled(true);
                msg.setText(m);
                acs = f;
                listAcs();
                Gree.Device pick = f.size() == 1 ? f.get(0) : null;
                for (Gree.Device d : f) if (prefs.pinned(d)) pick = d;
                if (pick != null) select(pick);
            });
        });
    }

    /** A button per AC the scan found, the pinned one marked. */
    void listAcs() {
        list.removeAllViews();
        for (Gree.Device d : acs) list.addView(button((prefs.pinned(d) ? "📌 " : "") + prefs.name(d) + "   " + d.ip, v -> select(d)));
    }

    void select(Gree.Device d) {
        dev = d;
        st = new HashMap<>();
        panel.setVisibility(View.GONE);
        msg.setText("Connecting to " + prefs.name(d) + "…");
        net.execute(() -> {
            try {
                if (d.key == null) {
                    Gree.bind(d);
                    prefs.remember(d);
                }
            } catch (Exception e) {
                say("Couldn't pair with " + prefs.name(d) + " (" + d.ip + "): " + e.getMessage());
                return;
            }
            refresh();
        });
    }

    /** On net: reads the unit's settings into st. */
    void refresh() {
        Gree.Device d = dev;
        if (d == null || d.key == null) return;
        try {
            Map<String, Integer> s = Gree.status(d);
            if (prefs.pinned(d)) Watch.show(this, d, s); // the bar follows what's done here
            runOnUiThread(() -> {
                if (d != dev) return;
                st = s;
                msg.setText("");
                panel.setVisibility(View.VISIBLE);
                render();
            });
        } catch (Exception e) {
            say("No reply from " + prefs.name(d) + " (" + d.ip + ")");
            ui.post(() -> { if (!scanned) scan(); }); // the pinned AC isn't where it was: find it, maybe at a new IP
        }
    }

    void send(String[] opt, int[] p) {
        Gree.Device d = dev;
        if (d == null || d.key == null) return;
        for (int i = 0; i < opt.length; i++) st.put(opt[i], p[i]); // show it now; the refresh after confirms
        render();
        net.execute(() -> {
            try {
                Gree.set(d, opt, p, prefs.beeps(d));
            } catch (Exception e) {
                say("No reply from " + prefs.name(d) + ": " + e.getMessage());
            }
            refresh();
        });
    }

    void setTemp(int step) {
        Integer t = st.get("SetTem");
        if (t != null) send(new String[]{"TemUn", "SetTem"}, new int[]{0, Math.max(MIN_T, Math.min(MAX_T, t + step))});
    }

    void render() {
        for (Runnable m : marks) m.run();
        title.setText(prefs.name(dev) + "   " + dev.ip);
        Integer t = st.get("SetTem"), r = st.get("TemSen");
        temp.setText(t == null ? "--" : t + "°C");
        room.setText(r == null || r == 0 ? "" : "Room " + Gree.roomTemp(r) + "°C");
    }

    void say(String m) { runOnUiThread(() -> msg.setText(m)); }

    void rename() {
        Gree.Device d = dev;
        EditText e = new EditText(this);
        e.setSingleLine();
        e.setText(prefs.name(d));
        e.setSelectAllOnFocus(true);
        LinearLayout box = vertical();
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e);
        AlertDialog a = new AlertDialog.Builder(this).setTitle("Name this AC").setView(box)
                .setPositiveButton("Save", (x, w) -> {
                    prefs.setName(d, e.getText().toString().trim());
                    render();
                    listAcs();
                    if (prefs.pinned(d)) Watch.show(this, d, st);
                })
                .setNegativeButton("Cancel", null).create();
        a.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        e.requestFocus();
        a.show();
    }

    void pin() {
        boolean on = !prefs.pinned(dev);
        prefs.setPinned(dev, on);
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0); // for its controls
            Watch.start(this);
        } else {
            Watch.stop(this);
        }
        Toast.makeText(this, on ? "Opens at startup, controls in notifications" : "Unpinned", Toast.LENGTH_SHORT).show();
        render();
        listAcs();
    }

    void beep() {
        prefs.setBeeps(dev, !prefs.beeps(dev)); // the unit hears it with the next command
        render();
    }

    /** A button that sets col to val, or toggles it 0/1 when val < 0; lit while the unit has it so. */
    Button ctl(String label, String col, int val) {
        return lit(button(label, v -> send(new String[]{col}, new int[]{val >= 0 ? val : st.getOrDefault(col, 0) == 1 ? 0 : 1})),
                () -> {
                    Integer v = st.get(col);
                    return v != null && v == (val < 0 ? 1 : val);
                });
    }

    /** b, tinted ON while on says so; render() checks again. */
    Button lit(Button b, BooleanSupplier on) {
        marks.add(() -> b.setBackgroundTintList(ColorStateList.valueOf(on.getAsBoolean() ? ON : OFF)));
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundTintList(ColorStateList.valueOf(OFF));
        return b;
    }

    Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(l);
        return b;
    }

    TextView text(float sp) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        return t;
    }

    TextView label(String s) {
        TextView t = text(14);
        t.setText(s);
        t.setPadding(0, dp(12), 0, 0);
        return t;
    }

    LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row(View... vs) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        for (View v : vs) l.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return l;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
