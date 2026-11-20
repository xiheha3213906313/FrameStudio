package studio.yingxu.frame;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class MacDiscovery {
    static final String TYPE = "_mflux-studio._tcp";

    static final class Service {
        final String name, serverId;
        final URI endpoint;

        Service(String name, String serverId, URI endpoint) {
            this.name = name;
            this.serverId = serverId;
            this.endpoint = endpoint;
        }

        static Service from(NsdServiceInfo info) {
            if (!"1".equals(attribute(info, "v")) || info.getPort() <= 0) return null;
            String serverId;
            try { serverId = MacTransport.uuid(attribute(info, "serverId")); }
            catch (IOException missingId) { serverId = ""; }
            URI endpoint = address(attribute(info, "ipv4"), info.getPort());
            if (endpoint == null) {
                List<InetAddress> addresses = Build.VERSION.SDK_INT >= 34 ?
                    info.getHostAddresses() : Collections.singletonList(info.getHost());
                for (InetAddress host : addresses) {
                    if (host == null) continue;
                    endpoint = address(host.getHostAddress(), info.getPort());
                    if (endpoint != null) break;
                }
            }
            if (endpoint == null) return null;
            String name = info.getServiceName();
            return new Service(name == null || name.isEmpty() ? "MFlux Studio" : name, serverId, endpoint);
        }

        private static URI address(String ipv4, int port) {
            try { return MacTransport.endpoint("https://" + ipv4 + ":" + port); }
            catch (IOException invalid) { return null; }
        }

        private static String attribute(NsdServiceInfo info, String name) {
            for (Map.Entry<String, byte[]> item : info.getAttributes().entrySet()) {
                if (name.equalsIgnoreCase(item.getKey()) && item.getValue() != null)
                    return new String(item.getValue(), StandardCharsets.US_ASCII);
            }
            return "";
        }
    }

    private final NsdManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final Map<String, NsdServiceInfo> found = new LinkedHashMap<>();
    private final Map<String, Service> resolved = new LinkedHashMap<>();
    private final Map<String, NsdServiceInfo> pending = new LinkedHashMap<>();
    private final Map<String, NsdManager.ServiceInfoCallback> watches = new HashMap<>();
    private Scan scan;
    private boolean resolving;
    private volatile List<Service> services = Collections.emptyList();

    MacDiscovery(Context context, Runnable changed) {
        manager = context.getSystemService(NsdManager.class);
        this.changed = changed;
    }

    List<Service> snapshot() { return services; }

    JSONArray json() {
        JSONArray list = new JSONArray();
        for (Service service : snapshot()) {
            JSONObject item = new JSONObject();
            Studio.with(item, "name", service.name);
            Studio.with(item, "endpoint", service.endpoint.toString());
            list.put(item);
        }
        return list;
    }

    void start() {
        if (manager == null || scan != null) return;
        scan = new Scan();
        try { manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, scan); }
        catch (IllegalArgumentException | IllegalStateException | SecurityException error) { scan = null; }
    }

    void stop() {
        Scan current = scan;
        scan = null;
        if (current == null) return;
        try { manager.stopServiceDiscovery(current); }
        catch (IllegalArgumentException | IllegalStateException | SecurityException ignored) {}
        clear();
    }

    void restart() { stop(); start(); }

    private void clear() {
        if (Build.VERSION.SDK_INT >= 34) {
            for (NsdManager.ServiceInfoCallback callback : watches.values()) unregister(callback);
            watches.clear();
        }
        found.clear();
        pending.clear();
        if (!resolved.isEmpty()) {
            resolved.clear();
            publish();
        }
    }

    private static String key(NsdServiceInfo info) {
        return info.getServiceName() + (Build.VERSION.SDK_INT >= 33 && info.getNetwork() != null ? "@" + info.getNetwork() : "");
    }

    private void serviceFound(Scan owner, NsdServiceInfo info) {
        if (scan != owner) return;
        String type = info.getServiceType();
        if (!TYPE.equalsIgnoreCase(type) && !(TYPE + ".").equalsIgnoreCase(type)) return;
        String key = key(info);
        if (found.putIfAbsent(key, info) != null) return;
        if (Build.VERSION.SDK_INT >= 34) watch(owner, key, info);
        else queueResolution(owner, key, info);
    }

    @android.annotation.TargetApi(34)
    private void watch(Scan owner, String key, NsdServiceInfo info) {
        NsdManager.ServiceInfoCallback callback = new NsdManager.ServiceInfoCallback() {
            @Override public void onServiceInfoCallbackRegistrationFailed(int errorCode) {
                if (scan != owner || watches.get(key) != this) return;
                watches.remove(key);
                queueResolution(owner, key, info);
            }
            @Override public void onServiceUpdated(NsdServiceInfo updated) {
                if (scan == owner && watches.get(key) == this) update(key, Service.from(updated));
            }
            @Override public void onServiceLost() {
                if (scan == owner && watches.get(key) == this) update(key, null);
            }
            @Override public void onServiceInfoCallbackUnregistered() {}
        };
        watches.put(key, callback);
        try { manager.registerServiceInfoCallback(info, main::post, callback); }
        catch (IllegalArgumentException | IllegalStateException | SecurityException error) {
            watches.remove(key);
            queueResolution(owner, key, info);
        }
    }

    @android.annotation.TargetApi(34)
    private void unregister(NsdManager.ServiceInfoCallback callback) {
        try { manager.unregisterServiceInfoCallback(callback); }
        catch (IllegalArgumentException | IllegalStateException | SecurityException ignored) {}
    }

    private void serviceLost(Scan owner, NsdServiceInfo info) {
        if (scan != owner) return;
        String key = key(info);
        found.remove(key);
        pending.remove(key);
        if (Build.VERSION.SDK_INT >= 34) {
            NsdManager.ServiceInfoCallback callback = watches.remove(key);
            if (callback != null) unregister(callback);
        }
        update(key, null);
    }

    private void queueResolution(Scan owner, String key, NsdServiceInfo info) {
        if (scan != owner || found.get(key) != info) return;
        pending.put(key, info);
        resolveNext();
    }

    private void resolveNext() {
        if (resolving || scan == null || pending.isEmpty()) return;
        Map.Entry<String, NsdServiceInfo> next = pending.entrySet().iterator().next();
        String key = next.getKey();
        NsdServiceInfo info = next.getValue();
        pending.remove(key);
        Scan owner = scan;
        resolving = true;
        try {
            manager.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    main.post(() -> finish(null));
                }
                @Override public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    main.post(() -> finish(Service.from(serviceInfo)));
                }
                private void finish(Service service) {
                    resolving = false;
                    if (scan == owner && found.get(key) == info) update(key, service);
                    resolveNext();
                }
            });
        } catch (IllegalArgumentException | IllegalStateException | SecurityException error) {
            resolving = false;
            main.post(this::resolveNext);
        }
    }

    private void update(String key, Service service) {
        Service previous = resolved.get(key);
        if (service == null) {
            if (resolved.remove(key) != null) publish();
        } else if (previous == null || !previous.endpoint.equals(service.endpoint) ||
            !previous.serverId.equals(service.serverId) || !previous.name.equals(service.name)) {
            resolved.put(key, service);
            publish();
        }
    }

    private void publish() {
        services = Collections.unmodifiableList(new ArrayList<>(resolved.values()));
        changed.run();
    }

    private final class Scan implements NsdManager.DiscoveryListener {
        @Override public void onDiscoveryStarted(String type) {}
        @Override public void onStartDiscoveryFailed(String type, int errorCode) { main.post(this::ended); }
        @Override public void onDiscoveryStopped(String type) { main.post(this::ended); }
        @Override public void onStopDiscoveryFailed(String type, int errorCode) { main.post(this::ended); }
        @Override public void onServiceFound(NsdServiceInfo info) { main.post(() -> serviceFound(this, info)); }
        @Override public void onServiceLost(NsdServiceInfo info) { main.post(() -> serviceLost(this, info)); }

        private void ended() {
            if (scan != this) return;
            scan = null;
            clear();
        }
    }
}
