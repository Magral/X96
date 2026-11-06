// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.IOException;

/** Bind every ADB socket to Wi-Fi. No cellular fallback, DNS or VPN remote endpoint. */
final class WifiSockets {
    static AdbClient.SocketFactory select(Context context) throws IOException {
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm!=null)for(Network network:cm.getAllNetworks()) {
            NetworkCapabilities c=cm.getNetworkCapabilities(network);
            if(c!=null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                return ()->network.getSocketFactory().createSocket();
        }
        throw new IOException("Подключи смартфон к домашней сети Wi-Fi. Мобильная сеть не используется.");
    }
    private WifiSockets() {}
}
