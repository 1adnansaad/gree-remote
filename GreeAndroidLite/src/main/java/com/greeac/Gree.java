package com.greeac;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gree AC LAN protocol, as in ../PythonCLI/gree.py and ../README.md: JSON over UDP port 7000, its payload
 * ("pack") AES-encrypted with ECB or, on newer firmware, GCM.
 */
public class Gree {
    static int port = 7000; // tests move it off the real one
    static final int TIMEOUT_MS = 1500; // per try; a lost packet gets one retry
    static final String KEY = "a3K8Bx%2r8Y7#xDh", GCM_KEY = "{yxAHAY_Lm6pbC/<"; // generic keys: scan and bind
    static final byte[] GCM_IV = {0x54, 0x40, 0x78, 0x44, 0x49, 0x67, 0x5a, 0x51, 0x6c, 0x5e, 0x63, 0x13};
    static final byte[] GCM_AAD = "qualcomm-test".getBytes(UTF_8);
    static final String[] COLS = {"Pow", "Mod", "SetTem", "WdSpd", "SwUpDn", "Tur", "Quiet", "SwhSlp", "Lig", "Blo",
            "Health", "SvSt", "TemSen"};

    public static class Device {
        public final String ip, mac, name;
        public volatile boolean gcm; // the cipher it speaks, settled by bind
        public volatile String key;  // its own AES key, from bind

        Device(String ip, String mac, String name, boolean gcm) {
            this.ip = ip;
            this.mac = mac;
            this.name = name;
            this.gcm = gcm;
        }
    }

    /** Sends a scan to every target and collects the units that answer, until none has for quietMs. */
    public static List<Device> scan(List<InetAddress> targets, int quietMs) throws IOException {
        Map<String, Device> found = new LinkedHashMap<>(); // by MAC: broadcast and sweep both reach a unit
        byte[] q = "{\"t\":\"scan\"}".getBytes(UTF_8), buf = new byte[4096];
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(quietMs);
            for (InetAddress a : targets) {
                try { s.send(new DatagramPacket(q, q.length, a, port)); } catch (IOException ignored) {} // try the rest
            }
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException e) { break; }
                try {
                    String resp = new String(buf, 0, p.getLength(), UTF_8), pack = open(resp, null);
                    String mac = first(str(pack, "cid"), str(resp, "cid"), str(pack, "mac")); // some leave cid empty
                    if (mac != null) found.putIfAbsent(mac, new Device(p.getAddress().getHostAddress(), mac,
                            first(str(pack, "name"), mac), str(resp, "tag") != null));
                } catch (Exception ignored) {} // not a Gree unit
            }
        }
        return new ArrayList<>(found.values());
    }

    /** Gets the unit's own key. ECB first: the firmware version doesn't tell, and GCM units ignore an ECB bind. */
    public static void bind(Device d) throws Exception {
        String pack = "{\"mac\":\"" + d.mac + "\",\"t\":\"bind\",\"uid\":0}", resp;
        try {
            resp = send(d.ip, request(d.mac, pack, null, d.gcm, 1));
        } catch (SocketTimeoutException e) {
            if (d.gcm) throw e;
            resp = send(d.ip, request(d.mac, pack, null, true, 1));
        }
        String r = open(resp, null), key = str(r, "key");
        if (key == null) throw new IOException("bind refused: " + r);
        d.gcm = str(resp, "tag") != null;
        d.key = key;
    }

    /** The unit's current settings: COLS name to value. */
    public static Map<String, Integer> status(Device d) throws Exception {
        String r = call(d, "{\"cols\":[" + quote(COLS) + "],\"mac\":\"" + d.mac + "\",\"t\":\"status\"}");
        List<String> cols = arr(r, "cols"), dat = arr(r, "dat");
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < Math.min(cols.size(), dat.size()); i++) {
            try { m.put(cols.get(i), Integer.parseInt(dat.get(i))); } catch (NumberFormatException ignored) {}
        }
        return m;
    }

    /** Sets each opt[i] to p[i]; the unit beeps for it only if beep. */
    public static void set(Device d, String[] opt, int[] p, boolean beep) throws Exception {
        String[] o = Arrays.copyOf(opt, opt.length + 2);
        int[] v = Arrays.copyOf(p, p.length + 2);
        // the beep is per command: older firmware mutes on Buzzer_ON_OFF 1, newer beeps on BuzzerCtrl 1 (as greeclimate)
        o[opt.length] = "Buzzer_ON_OFF";
        v[p.length] = beep ? 0 : 1;
        o[opt.length + 1] = "BuzzerCtrl";
        v[p.length + 1] = beep ? 1 : 0;
        String r = call(d, "{\"opt\":[" + quote(o) + "],\"p\":" + Arrays.toString(v) + ",\"t\":\"cmd\"}");
        String code = str(r, "r");
        if (code != null && !code.equals("200")) throw new IOException("unit refused: " + r);
    }

    /** TemSen is the room temperature +40 °C, except on firmware that reports it as is. */
    public static int roomTemp(int raw) { return raw > 40 ? raw - 40 : raw; } // ponytail: guesses which; wrong below 0 °C

    /**
     * This phone's IPv4 broadcast addresses, then every host on those subnets: some routers and phones drop
     * broadcasts, and a unicast sweep (gree.py --sweep) still reaches the units.
     */
    public static List<InetAddress> targets() throws SocketException, UnknownHostException {
        List<InetAddress> out = new ArrayList<>(), sweep = new ArrayList<>();
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (!(ia.getAddress() instanceof Inet4Address) || ia.getBroadcast() == null) continue;
                out.add(ia.getBroadcast());
                sweep.addAll(hosts(ia.getAddress(), ia.getNetworkPrefixLength()));
            }
        }
        out.add(InetAddress.getByName("255.255.255.255"));
        out.addAll(sweep);
        return out;
    }

    /** The hosts on addr's subnet; none past /22 (1024 addresses), gree.py's sweep limit. */
    static List<InetAddress> hosts(InetAddress addr, int prefix) throws UnknownHostException {
        List<InetAddress> out = new ArrayList<>();
        if (prefix < 22 || prefix > 30) return out;
        int mask = -1 << (32 - prefix), net = ByteBuffer.wrap(addr.getAddress()).getInt() & mask;
        for (int h = 1; h < ~mask; h++) out.add(InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(net | h).array()));
        return out;
    }

    static String call(Device d, String pack) throws Exception {
        return open(send(d.ip, request(d.mac, pack, d.key, d.gcm, 0)), d.key);
    }

    /** One request, one reply. */
    static String send(String ip, String req) throws IOException {
        byte[] b = req.getBytes(UTF_8), buf = new byte[4096];
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(TIMEOUT_MS);
            for (int tries = 1; ; tries++) {
                s.send(new DatagramPacket(b, b.length, InetAddress.getByName(ip), port));
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                    return new String(buf, 0, p.getLength(), UTF_8);
                } catch (SocketTimeoutException e) {
                    if (tries == 2) throw e;
                }
            }
        }
    }

    /** A request carrying pack to unit tcid, encrypted with key (null: the generic one). */
    static String request(String tcid, String pack, String key, boolean gcm, int i) throws Exception {
        byte[] out = cipher(Cipher.ENCRYPT_MODE, key, gcm).doFinal(pack.getBytes(UTF_8));
        String tag = "";
        if (gcm) { // Java appends the 16-byte tag; the units want it in its own field
            tag = ",\"tag\":\"" + b64(Arrays.copyOfRange(out, out.length - 16, out.length)) + "\"";
            out = Arrays.copyOf(out, out.length - 16);
        }
        return "{\"cid\":\"app\",\"i\":" + i + ",\"t\":\"pack\",\"uid\":0,\"tcid\":\"" + tcid + "\"" + tag
                + ",\"pack\":\"" + b64(out) + "\"}";
    }

    /** The decrypted pack of a reply, GCM if it has a tag; key null: the generic one. */
    static String open(String resp, String key) throws Exception {
        String tag = str(resp, "tag");
        byte[] in = Base64.getDecoder().decode(str(resp, "pack"));
        if (tag != null) {
            byte[] t = Base64.getDecoder().decode(tag);
            in = Arrays.copyOf(in, in.length + t.length);
            System.arraycopy(t, 0, in, in.length - t.length, t.length);
        }
        String s = new String(cipher(Cipher.DECRYPT_MODE, key, tag != null).doFinal(in), UTF_8);
        return s.substring(0, s.lastIndexOf('}') + 1); // drops the padding, whatever the unit put there
    }

    static Cipher cipher(int mode, String key, boolean gcm) throws Exception {
        SecretKeySpec k = new SecretKeySpec((key != null ? key : gcm ? GCM_KEY : KEY).getBytes(UTF_8), "AES");
        if (!gcm) {
            Cipher c = Cipher.getInstance(mode == Cipher.ENCRYPT_MODE ? "AES/ECB/PKCS5Padding" : "AES/ECB/NoPadding");
            c.init(mode, k);
            return c;
        }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, k, new GCMParameterSpec(128, GCM_IV));
        c.updateAAD(GCM_AAD);
        return c;
    }

    // ponytail: regex, not a JSON parser; enough for the flat, machine-made packs the units send (no escaped quotes)
    static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(?:\"([^\"]*)\"|([-\\w.]+))").matcher(json);
        return !m.find() ? null : m.group(1) != null ? m.group(1) : m.group(2);
    }

    static List<String> arr(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^\\]]*)]").matcher(json);
        List<String> out = new ArrayList<>();
        if (m.find() && !m.group(1).trim().isEmpty()) for (String v : m.group(1).split(",")) out.add(v.trim().replace("\"", ""));
        return out;
    }

    static String first(String... v) {
        for (String s : v) if (s != null && !s.isEmpty()) return s;
        return null;
    }

    static String quote(String[] a) { return "\"" + String.join("\",\"", a) + "\""; }

    static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
}
