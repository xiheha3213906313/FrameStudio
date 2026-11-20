package studio.yingxu.frame;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;

final class MacConnection {
    private static final String ALIAS = "frame-mflux-device";
    private final SharedPreferences prefs;
    private final ConnectivityManager connectivity;
    private MacPairing pairing;

    MacConnection(Context context) {
        connectivity = context.getSystemService(ConnectivityManager.class);
        prefs = context.getSharedPreferences("mflux", Context.MODE_PRIVATE);
    }

    private static final class CredentialFailure extends IOException {
        CredentialFailure(Exception cause) { super("本机无法读取 Mac 凭证，请重新配对。", cause); }
    }

    static boolean credentialsInvalid(Exception error) {
        return error instanceof CredentialFailure ||
            error instanceof MacTransport.Failure && ((MacTransport.Failure) error).status == 401;
    }

    static final class Session {
        final URI endpoint;
        final String pin, serverId, deviceId;
        private final String token;
        private final ConnectivityManager connectivity;
        Session(URI endpoint, String pin, String serverId, String deviceId, String token) {
            this(endpoint, pin, serverId, deviceId, token, null);
        }
        Session(URI endpoint, String pin, String serverId, String deviceId, String token, ConnectivityManager connectivity) {
            this.connectivity = connectivity;
            this.endpoint = endpoint;
            this.pin = pin;
            this.serverId = serverId;
            this.deviceId = deviceId;
            this.token = token;
        }

        MacTransport client() throws Exception { return new MacTransport(endpoint, pin, token, lanNetwork(connectivity, endpoint)); }

        Session at(URI address) { return new Session(address, pin, serverId, deviceId, token, connectivity); }
        boolean owns(JSONObject job) {
            return serverId.equals(job.optString("serverId")) && deviceId.equals(job.optString("deviceId"));
        }
    }

    JSONObject status() throws Exception {
        JSONObject row = record();
        JSONObject result = new JSONObject();
        if (row.has("endpoint")) result.put("endpoint", row.getString("endpoint")).put("serverId", row.getString("serverId"));
        return result.put("catalog", row.optJSONObject("catalog"));
    }

    synchronized JSONObject inspectPairing(String address) throws Exception {
        pairing = null;
        URI endpoint = MacTransport.endpoint(address);
        JSONObject saved = record();
        if (endpoint.toString().equals(saved.optString("endpoint"))) {
            pairing = new MacPairing(endpoint, saved.getString("pin"), MacTransport.uuid(saved.getString("serverId")));
        } else {
            String fingerprint = MacTransport.inspectFingerprint(endpoint, lanNetwork(connectivity, endpoint));
            pairing = new MacPairing(endpoint, fingerprint, null);
        }
        return pairing.prompt();
    }

    synchronized JSONObject confirmPairing(String id) throws Exception { return pairing(id).confirm(); }

    synchronized void cancelPairing(String id) {
        if (pairing != null && pairing.id.equals(id)) pairing = null;
    }

    private MacPairing pairing(String id) throws IOException {
        if (pairing == null || !pairing.id.equals(id)) throw new IOException("本次配对已结束，请重新选择 Mac。");
        return pairing;
    }

    synchronized JSONObject pair(String id, String code) throws Exception {
        MacPairing attempt = pairing(id);
        String deviceName = (Build.MANUFACTURER + " " + Build.MODEL).replaceAll("\\p{Cntrl}", "").trim();
        if (deviceName.isEmpty()) deviceName = "Android";
        if (deviceName.length() > 60) deviceName = deviceName.substring(0, 60);
        try {
            Session paired = attempt.send(lanNetwork(connectivity, attempt.endpoint), code, deviceName);
            save(paired, null);
            return status();
        } finally {
            if (attempt.finished()) pairing = null;
        }
    }

    JSONObject refresh(String newAddress, List<MacDiscovery.Service> discovered) throws Exception {
        String savedRecord = prefs.getString("connection", "{}");
        JSONObject row = Studio.json(savedRecord);
        Session saved = session(row);
        if (saved == null) throw new IOException("请先与 MFlux Studio 配对。");
        Verified verified = newAddress == null ? reconnect(saved, discovered) : verify(saved.at(MacTransport.endpoint(newAddress)));
        return saveRefresh(savedRecord, row, verified.endpoint, verified.catalog);
    }

    static final class Verified {
        final URI endpoint;
        final JSONObject catalog;
        Verified(URI endpoint, JSONObject catalog) { this.endpoint = endpoint; this.catalog = catalog; }
    }

    static Verified reconnect(Session saved, List<MacDiscovery.Service> discovered) throws Exception {
        Exception failure;
        try { return verify(saved); }
        catch (Exception error) {
            if (credentialsInvalid(error)) throw error;
            failure = error;
        }
        Set<URI> tried = new HashSet<>();
        tried.add(saved.endpoint);
        for (MacDiscovery.Service candidate : discovered) {
            // Bonjour IDs only narrow the search. TLS and the authenticated catalog establish identity.
            if (!saved.serverId.equals(candidate.serverId) || !tried.add(candidate.endpoint)) continue;
            try { return verify(saved.at(candidate.endpoint)); }
            catch (Exception error) { if (credentialsInvalid(error)) throw error; }
        }
        throw new IOException("等待连接 Mac。" + MacTransport.explain(failure), failure);
    }

    static Verified verify(Session session) throws Exception {
        JSONObject catalog = session.client().json("/v1/models", null, 200);
        if (catalog.optInt("version") != 1 || !session.serverId.equals(MacTransport.uuid(catalog.getString("serverId"))))
            throw new IOException("此地址返回的 Mac 身份与已配对设备不一致。");
        catalog.getJSONArray("models");
        catalog.getJSONObject("limits");
        return new Verified(session.endpoint, catalog);
    }

    private synchronized JSONObject saveRefresh(String previous, JSONObject row, URI endpoint, JSONObject catalog) throws Exception {
        if (!previous.equals(prefs.getString("connection", "{}"))) throw new IOException("Mac 配对资料已更新，请重试。");
        row.put("endpoint", endpoint.toString()).put("catalog", catalog);
        String updated = row.toString();
        if (!previous.equals(updated) && !prefs.edit().putString("connection", updated).commit())
            throw new IOException("无法保存 Mac 模型目录。");
        return status();
    }

    synchronized void forget() throws Exception {
        pairing = null;
        if (!prefs.edit().remove("connection").commit()) throw new IOException("无法移除本机配对记录。");
        keyStore().deleteEntry(ALIAS);
    }

    Session session() throws Exception { return session(record()); }

    private Session session(JSONObject row) throws Exception {
        if (!row.has("endpoint")) return null;
        return new Session(MacTransport.endpoint(row.getString("endpoint")), row.getString("pin"), row.getString("serverId"),
            row.getString("deviceId"), decrypt(row.getString("credential")), connectivity);
    }

    private static Network lanNetwork(ConnectivityManager connectivity, URI endpoint) throws IOException {
        if (connectivity == null) return null;
        byte[] remote = InetAddress.getByName(endpoint.getHost()).getAddress();
        Network fallback = null, active = connectivity.getActiveNetwork();
        // Bind only the Mac socket to the matching LAN even if Android prefers cellular for Internet access.
        for (Network network : connectivity.getAllNetworks()) {
            LinkProperties link = connectivity.getLinkProperties(network);
            if (link == null || !sameSubnet(link, remote)) continue;
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)))
                return network;
            if (fallback == null || network.equals(active)) fallback = network;
        }
        if (fallback == null) throw new NoRouteToHostException();
        return fallback;
    }

    private static boolean sameSubnet(LinkProperties link, byte[] remote) {
        for (LinkAddress address : link.getLinkAddresses()) {
            byte[] local = address.getAddress().getAddress();
            int prefix = address.getPrefixLength();
            if (local.length != 4 || remote.length != 4 || prefix == 0) continue;
            int index = 0, bits = prefix;
            while (bits >= 8 && local[index] == remote[index]) { index++; bits -= 8; }
            if (bits < 8 && (bits == 0 || ((local[index] ^ remote[index]) & (255 << (8 - bits))) == 0)) return true;
        }
        return false;
    }

    JSONObject catalog() { return record().optJSONObject("catalog"); }
    private JSONObject record() { return Studio.json(prefs.getString("connection", "{}")); }

    private synchronized void save(Session session, JSONObject catalog) throws Exception {
        JSONObject row = new JSONObject().put("endpoint", session.endpoint.toString()).put("pin", session.pin)
            .put("serverId", session.serverId).put("deviceId", session.deviceId)
            .put("credential", encrypt(session.token)).put("catalog", catalog);
        if (!prefs.edit().putString("connection", row.toString()).commit()) throw new IOException("无法保存 Mac 配对资料。");
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }

    private static String encrypt(String token) throws Exception {
        KeyStore store = keyStore();
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            gen.generateKey();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, store.getKey(ALIAS, null));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String decrypt(String credential) throws IOException {
        try {
            String[] parts = credential.split("[.]");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyStore().getKey(ALIAS, null),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CredentialFailure(e);
        }
    }
}
