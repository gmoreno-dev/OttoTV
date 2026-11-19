package app.ottotv.connect.home;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

/**
 * The network as the top bar shows it: none, a cable, or Wi-Fi with one to
 * three arcs. "Offline" means no network at all: a network that hasn't
 * passed Android's internet check still counts, since that check can fail
 * where its servers are blocked while everything else works.
 */
final class Net {
    interface Listener {
        void onNet(int kind, int bars);
    }

    /** Tests: StatusPill.OFFLINE, WIFI or CABLE instead of the real network. */
    static volatile int fake = -1;

    private final ConnectivityManager cm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private int kind = StatusPill.WIFI;
    private int bars = 3;
    private boolean started;

    Net(Context c, Listener listener) {
        cm = c.getSystemService(ConnectivityManager.class);
        callback = new Callback(this, listener);
        read(cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork()));
    }

    int kind() {
        return kind;
    }

    int bars() {
        return bars;
    }

    boolean online() {
        return kind != StatusPill.OFFLINE;
    }

    void start() {
        if (cm != null && !started) {
            started = true;
            try {
                cm.registerDefaultNetworkCallback(callback, main);
            } catch (RuntimeException e) {
                started = false;
            }
        }
    }

    void stop() {
        if (cm != null && started) {
            started = false;
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (RuntimeException e) {
                // already gone
            }
        }
    }

    boolean read(NetworkCapabilities caps) {
        int k, b = 3;
        if (fake >= 0) {
            k = fake;
        } else if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            k = StatusPill.OFFLINE;
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            k = StatusPill.CABLE;
        } else {
            k = StatusPill.WIFI;
            int rssi = caps.getSignalStrength();
            if (rssi != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
                b = rssi >= -60 ? 3 : rssi >= -72 ? 2 : 1;
            }
        }
        boolean changed = k != kind || b != bars;
        kind = k;
        bars = b;
        return changed;
    }

    static final class Callback extends ConnectivityManager.NetworkCallback {
        private final Net net;
        private final Listener listener;

        Callback(Net net, Listener listener) {
            this.net = net;
            this.listener = listener;
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            if (net.read(caps)) {
                listener.onNet(net.kind, net.bars);
            }
        }

        @Override
        public void onLost(Network network) {
            if (net.read(null)) {
                listener.onNet(net.kind, net.bars);
            }
        }
    }
}
