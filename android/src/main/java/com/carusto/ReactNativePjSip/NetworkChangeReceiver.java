package com.carusto.ReactNativePjSip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

public class NetworkChangeReceiver extends BroadcastReceiver {

    private PjSipService sipService;

    public NetworkChangeReceiver(PjSipService sipService) {
        this.sipService = sipService;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.w(TAG, "NetworkChangeReceiver " + ConnectivityManager.CONNECTIVITY_ACTION);
        if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();

            if (activeNetwork != null && activeNetwork.isConnected()) {
                sipService.handleIpChange();
            }
        }
    }
}
