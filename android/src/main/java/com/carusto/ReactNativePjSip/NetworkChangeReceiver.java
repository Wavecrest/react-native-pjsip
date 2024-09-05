package com.carusto.ReactNativePjSip;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;

public class NetworkChangeReceiver extends ConnectivityManager.NetworkCallback {
    private static final String TAG = "NetworkChangeReceiver";
    private String lastIp;

    private PjSipService sipService;
    private Context context;

    public NetworkChangeReceiver(PjSipService sipService, Context context) {
        this.sipService = sipService;
        this.context = context;
        this.lastIp = getIPAddress(context);
    }

    @Override
    public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
        String ipAddress = getIPAddress(context);
        if (ipAddress != null && !ipAddress.equals(lastIp)) {
            Log.w(TAG, "IP changed");
            lastIp = ipAddress;
            sipService.handleIpChange();
        }
    }

    public static String getIPAddress(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork != null) {
            LinkProperties linkProperties = cm.getLinkProperties(activeNetwork);

            if (linkProperties != null) {
                List<LinkAddress> linkAddresses = linkProperties.getLinkAddresses();

                for (LinkAddress linkAddress : linkAddresses) {
                    InetAddress address = linkAddress.getAddress();
                    if (address instanceof Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        }
        return null;
    }
}
