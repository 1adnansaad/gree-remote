package com.greeac;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.IBinder;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The pinned AC's controls, in a notification while this phone is on Wi-Fi and the AC answers there; a quiet "Looking
 * for" one otherwise. A foreground service: nothing else hears Wi-Fi come and go in the background.
 */
public class Watch extends Service {
    static final int ID = 1;
    static final int CHECK_S = 60; // the bar catches up with changes made elsewhere (IR remote) this often
    static final String BAR = "ac", LOOKING = "watch"; // channels
    static final String ON = "on", OFF = "off", UP = "up", DOWN = "down"; // its buttons
    static final String[] MODES = {"Auto", "Cool", "Dry", "Fan", "Heat"};

    final ScheduledExecutorService net = Executors.newSingleThreadScheduledExecutor(); // checks and buttons, one at a time
    ConnectivityManager cm;
    Prefs prefs;
    Network wifi; // the one bound to; callback thread only
    ScheduledFuture<?> checks;

    final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) {
            wifi = n;
            cm.bindProcessToNetwork(n); // the AC's UDP goes over Wi-Fi, even before it's the default or with no internet
            if (checks != null) checks.cancel(false);
            checks = net.scheduleWithFixedDelay(Watch.this::check, 0, CHECK_S, TimeUnit.SECONDS);
        }

        @Override public void onLost(Network n) {
            if (!n.equals(wifi)) return;
            wifi = null;
            checks.cancel(false);
            cm.bindProcessToNetwork(null);
            net.execute(() -> post(looking(lookingFor())));
        }
    };

    static void start(Context c) { c.startForegroundService(new Intent(c, Watch.class)); }

    static void stop(Context c) { c.stopService(new Intent(c, Watch.class)); }

    @Override public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        cm = getSystemService(ConnectivityManager.class);
        try {
            startForeground(ID, looking(lookingFor()), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (Exception e) {
            stopSelf(); // Android won't have it started from the background, as after being killed
            return;
        }
        if (prefs.pinnedAc() == null) { stopSelf(); return; } // unpinned meanwhile; startForeground still had to come first
        cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String a = i == null ? null : i.getAction();
        if (a != null) net.execute(() -> act(a));
        return START_STICKY;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        try { cm.unregisterNetworkCallback(callback); } catch (IllegalArgumentException ignored) {} // never registered
        cm.bindProcessToNetwork(null);
        net.shutdownNow();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    /** On net: the controls if the pinned AC answers where it was last reached. No scan: that's the app's. */
    void check() {
        Gree.Device d = prefs.pinnedAc();
        if (d == null) { stopSelf(); return; }
        try {
            show(this, d, Gree.status(d));
        } catch (Exception e) {
            post(looking(lookingFor()));
        }
    }

    /** On net: one of the bar's buttons. Power sets what the button said, so a stale bar can't flip it the wrong way. */
    void act(String a) {
        Gree.Device d = prefs.pinnedAc();
        if (d == null) return;
        try {
            if (a.equals(ON) || a.equals(OFF)) {
                Gree.set(d, new String[]{"Pow"}, new int[]{a.equals(ON) ? 1 : 0}, prefs.beeps(d));
            } else {
                Integer t = Gree.status(d).get("SetTem");
                if (t != null) Gree.set(d, new String[]{"TemUn", "SetTem"}, new int[]{0,
                        Math.max(MainActivity.MIN_T, Math.min(MainActivity.MAX_T, t + (a.equals(UP) ? 1 : -1)))}, prefs.beeps(d));
            }
            show(this, d, Gree.status(d));
        } catch (Exception e) {
            post(looking(prefs.name(d) + " isn't answering"));
        }
    }

    /** Posts the pinned AC's controls, as st has it. */
    static void show(Context c, Gree.Device d, Map<String, Integer> st) {
        boolean on = st.getOrDefault("Pow", 0) == 1;
        Integer t = st.get("SetTem"), m = st.get("Mod"), r = st.get("TemSen");
        String s = (on ? "On" : "Off") + (on && m != null && m >= 0 && m < MODES.length ? " · " + MODES[m] : "")
                + (t == null ? "" : " · " + t + "°C") + (r == null || r == 0 ? "" : " · room " + Gree.roomTemp(r) + "°C");
        c.getSystemService(NotificationManager.class).notify(ID, builder(c, BAR)
                .setContentTitle(new Prefs(c).name(d))
                .setContentText(s)
                .addAction(action(c, on ? OFF : ON, on ? "Turn off" : "Turn on"))
                .addAction(action(c, DOWN, "−1°C"))
                .addAction(action(c, UP, "+1°C"))
                .build());
    }

    void post(Notification n) { getSystemService(NotificationManager.class).notify(ID, n); }

    /** The quiet one, while the pinned AC doesn't answer. */
    Notification looking(String title) { return builder(this, LOOKING).setContentTitle(title).build(); }

    String lookingFor() {
        Gree.Device d = prefs.pinnedAc();
        return "Looking for " + (d == null ? "your AC" : prefs.name(d)) + " on Wi-Fi";
    }

    static Notification.Builder builder(Context c, String channel) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(BAR, "AC controls", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(LOOKING, "Looking for your AC", NotificationManager.IMPORTANCE_MIN));
        return new Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_ac)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(c, 0, c.getPackageManager().getLaunchIntentForPackage(c.getPackageName()),
                        PendingIntent.FLAG_IMMUTABLE)); // the launcher's intent: brings back the open app, not a second one
    }

    static Notification.Action action(Context c, String a, String label) {
        PendingIntent pi = PendingIntent.getService(c, 0, new Intent(c, Watch.class).setAction(a), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder((Icon) null, label, pi).build();
    }

    /** Starts it again after a reboot or an update of this app. */
    public static class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            if (new Prefs(c).pinnedAc() != null) start(c);
        }
    }
}
