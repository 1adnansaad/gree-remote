package com.greeac;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GreeTest {
    static final String MAC = "f4911e000001", UNIT_KEY = "0123456789abcdef";
    static final String BIND = "{\"mac\":\"" + MAC + "\",\"t\":\"bind\",\"uid\":0}";
    // BIND encrypted by Python's cryptography package, the way gree.py does it
    static final String ECB = "wQf9S7800vtjI4/LFXYqkYDub543PyG7VQUvN65CVRmj9mrs/0p7GUTdWxmNaBUt";
    static final String GCM = "JtoT1XUt89K1ssWGayxFB59xuNIAzgWyIl52X7OFwurdNHgGiUNkepU=", TAG = "OcO4uUMKCLonpXuukhQMtg==";

    @Test public void encryptsAndDecryptsLikeGreePy() throws Exception {
        assertEquals(ECB, Gree.str(Gree.request(MAC, BIND, null, false, 1), "pack"));
        String gcm = Gree.request(MAC, BIND, null, true, 1);
        assertEquals(GCM, Gree.str(gcm, "pack"));
        assertEquals(TAG, Gree.str(gcm, "tag"));
        assertEquals(BIND, Gree.open("{\"t\":\"pack\",\"pack\":\"" + ECB + "\"}", null));
        assertEquals(BIND, Gree.open("{\"t\":\"pack\",\"tag\":\"" + TAG + "\",\"pack\":\"" + GCM + "\"}", null));
    }

    /** A unit that answers the scan in ECB but, like some V3 firmware, only binds and talks over GCM. */
    @Test public void scansBindsReadsAndSetsAGcmOnlyUnit() throws Exception {
        Map<String, Integer> state = new HashMap<>();
        state.put("Pow", 1);
        state.put("SetTem", 24);
        try (DatagramSocket unit = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Gree.port = unit.getLocalPort();
            new Thread(() -> serve(unit, state)).start();

            List<InetAddress> targets = new ArrayList<>();
            targets.add(InetAddress.getLoopbackAddress());
            List<Gree.Device> found = Gree.scan(targets, 300);
            assertEquals(1, found.size());
            Gree.Device d = found.get(0);
            assertEquals(MAC, d.mac); // its cid was empty
            assertEquals("Bedroom", d.name);
            assertFalse(d.gcm);

            Gree.bind(d);
            assertTrue(d.gcm);
            assertEquals(UNIT_KEY, d.key);
            assertEquals(Integer.valueOf(24), Gree.status(d).get("SetTem"));
            Gree.set(d, new String[]{"TemUn", "SetTem"}, new int[]{0, 27});
            Map<String, Integer> st = Gree.status(d);
            assertEquals(Integer.valueOf(27), st.get("SetTem"));
            assertEquals(Integer.valueOf(1), st.get("Pow"));
        }
    }

    static void serve(DatagramSocket s, Map<String, Integer> state) {
        byte[] buf = new byte[4096];
        while (true) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String req = new String(buf, 0, p.getLength(), UTF_8), reply;
                if (req.equals("{\"t\":\"scan\"}")) {
                    reply = Gree.request("", "{\"t\":\"dev\",\"cid\":\"\",\"mac\":\"" + MAC + "\",\"name\":\"Bedroom\"}", null, false, 1)
                            .replace("\"cid\":\"app\"", "\"cid\":\"\""); // firmware that leaves both cids empty
                } else if (Gree.str(req, "tag") == null) {
                    continue; // ignores ECB
                } else if (!opens(req, UNIT_KEY)) { // generic key: a bind
                    reply = Gree.request("app", "{\"t\":\"bindok\",\"mac\":\"" + MAC + "\",\"key\":\"" + UNIT_KEY + "\",\"r\":200}", null, true, 1);
                } else {
                    String pack = Gree.open(req, UNIT_KEY);
                    List<String> cols = Gree.arr(pack, "cols"), opt = Gree.arr(pack, "opt"), val = Gree.arr(pack, "p");
                    for (int i = 0; i < opt.size(); i++) state.put(opt.get(i), Integer.parseInt(val.get(i)));
                    List<Integer> dat = new ArrayList<>();
                    for (String c : cols) dat.add(state.getOrDefault(c, 0));
                    reply = Gree.request("app", opt.isEmpty()
                            ? "{\"t\":\"dat\",\"r\":200,\"cols\":[" + Gree.quote(cols.toArray(new String[0])) + "],\"dat\":" + dat + "}"
                            : "{\"t\":\"res\",\"r\":200,\"opt\":[" + Gree.quote(opt.toArray(new String[0])) + "],\"p\":" + val + "}", UNIT_KEY, true, 0);
                }
                byte[] b = reply.getBytes(UTF_8);
                s.send(new DatagramPacket(b, b.length, p.getSocketAddress()));
            } catch (Exception e) {
                return; // socket closed: test over
            }
        }
    }

    static boolean opens(String req, String key) {
        try { Gree.open(req, key); return true; } catch (Exception e) { return false; } // GCM: wrong key fails the tag
    }

    @Test public void sweepsTheWholeSubnetButNotAHugeOne() throws Exception {
        List<InetAddress> h = Gree.hosts(InetAddress.getByName("192.168.1.37"), 24);
        assertEquals(254, h.size());
        assertEquals("192.168.1.1", h.get(0).getHostAddress());
        assertEquals("192.168.1.254", h.get(253).getHostAddress());
        assertEquals(1022, Gree.hosts(InetAddress.getByName("10.0.5.9"), 22).size());
        assertTrue(Gree.hosts(InetAddress.getByName("10.0.5.9"), 16).isEmpty());
    }
}
