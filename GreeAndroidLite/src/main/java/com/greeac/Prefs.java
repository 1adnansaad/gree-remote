package com.greeac;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Per AC, by MAC: the user's "name.", "beep."; where it was last reached: "ip.", "key.", "gcm.", "unit." (its own
 * name); and the "pinned" one's MAC.
 */
class Prefs {
    final SharedPreferences p;

    Prefs(Context c) { p = c.getSharedPreferences("acs", Context.MODE_PRIVATE); }

    /** What the user calls d, else what it calls itself. */
    String name(Gree.Device d) { return p.getString("name." + d.mac, d.name); }

    boolean pinned(Gree.Device d) { return d.mac.equals(p.getString("pinned", null)); }

    boolean beeps(Gree.Device d) { return p.getBoolean("beep." + d.mac, true); }

    /** Empty: its own name again. */
    void setName(Gree.Device d, String n) { p.edit().putString("name." + d.mac, n.isEmpty() ? null : n).apply(); }

    /** One pin: pinning d unpins the other. */
    void setPinned(Gree.Device d, boolean on) { p.edit().putString("pinned", on ? d.mac : null).apply(); }

    void setBeeps(Gree.Device d, boolean on) { p.edit().putBoolean("beep." + d.mac, on).apply(); }

    /** Where d is and its key, so pinnedAc() can skip scan and bind. */
    void remember(Gree.Device d) {
        p.edit().putString("ip." + d.mac, d.ip).putString("key." + d.mac, d.key).putBoolean("gcm." + d.mac, d.gcm)
                .putString("unit." + d.mac, d.name).apply();
    }

    /** The pinned AC where it was last reached, ready to talk to; null if none. */
    Gree.Device pinnedAc() {
        String mac = p.getString("pinned", null), ip = mac == null ? null : p.getString("ip." + mac, null);
        if (ip == null) return null;
        Gree.Device d = new Gree.Device(ip, mac, p.getString("unit." + mac, mac), p.getBoolean("gcm." + mac, false));
        d.key = p.getString("key." + mac, null);
        return d;
    }
}
