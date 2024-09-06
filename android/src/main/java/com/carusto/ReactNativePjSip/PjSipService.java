package com.carusto.ReactNativePjSip;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.Log;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;
import android.net.ConnectivityManager;
import android.net.NetworkRequest;

import com.carusto.ReactNativePjSip.dto.AccountConfigurationDTO;
import com.carusto.ReactNativePjSip.dto.CallSettingsDTO;
import com.carusto.ReactNativePjSip.dto.ServiceConfigurationDTO;
import com.carusto.ReactNativePjSip.dto.SipMessageDTO;
import com.carusto.ReactNativePjSip.utils.ArgumentUtils;

import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;

import org.json.JSONObject;
import org.pjsip.pjsua2.*;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class PjSipService extends Service {

    private static final String TAG = "PjSipService";
    private static final String CHANNEL_ID = "PjSipServiceChannel";

    private static boolean isForeground = false;
    private boolean isLoaded = false;

    private boolean mInitialized;
    private HandlerThread mWorkerThread;
    private Handler mHandler;
    private Endpoint mEndpoint;
    private int mUdpTransportId;
    private int mTcpTransportId;
    private int mTlsTransportId;
    private ServiceConfigurationDTO mServiceConfiguration = new ServiceConfigurationDTO();
    private PjSipLogWriter mLogWriter;
    private PjSipBroadcastEmiter mEmitter;
    private List<PjSipAccount> mAccounts = new ArrayList<>();
    private List<PjSipCall> mCalls = new ArrayList<>();
    private List<Object> mTrash = new LinkedList<>();
    private AudioManager mAudioManager;
    private boolean mUseSpeaker = false;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mIncallWakeLock;
    private WifiManager mWifiManager;
    private WifiManager.WifiLock mWifiLock;
    private String mRegisteredThread;

    private BroadcastReceiver mPhoneStateChangedReceiver = new PhoneStateChangedReceiver();

    private ConnectivityManager connectivityManager;
    private NetworkChangeReceiver networkChangeReceiver;

    public PjSipBroadcastEmiter getEmitter() {
        return mEmitter;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        boolean isPermissionGranted = notificationManager.areNotificationsEnabled();

        if (!mInitialized) {
            if (intent != null && intent.hasExtra("service")) {
                mServiceConfiguration = ServiceConfigurationDTO.fromMap((Map) intent.getSerializableExtra("service"));
            }

            mWorkerThread = new HandlerThread(getClass().getSimpleName(), Process.THREAD_PRIORITY_FOREGROUND);
            mWorkerThread.setPriority(Thread.MAX_PRIORITY);
            mWorkerThread.start();
            mHandler = new Handler(mWorkerThread.getLooper());
            mEmitter = new PjSipBroadcastEmiter(this);
            mAudioManager = (AudioManager) getApplicationContext().getSystemService(AUDIO_SERVICE);
            mPowerManager = (PowerManager) getApplicationContext().getSystemService(POWER_SERVICE);
            mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            mWifiLock = mWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, this.getPackageName() + "-wifi-call-lock");
            mWifiLock.setReferenceCounted(false);

            IntentFilter phoneStateFilter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            registerReceiver(mPhoneStateChangedReceiver, phoneStateFilter);

            mInitialized = true;

            if (!isForeground && isPermissionGranted) {
                createNotificationChannel();
                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                                    .setStyle(new NotificationCompat.BigTextStyle().bigText(mServiceConfiguration.notificationMessage))
                                    .setContentTitle(mServiceConfiguration.notificationTitle)
                                    .setSmallIcon(getApplicationContext().getApplicationInfo().icon)
                                    .setPriority(NotificationCompat.PRIORITY_LOW)
                                    .setOngoing(true)
                                    .build();

                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                isForeground = true;
            }

            try {
                job(() -> {
                    load();
                    isLoaded = true;
                });
            } catch (Exception e) {
                Log.e(TAG, "Exception during job(this::load)", e);
            }
        }

        if (intent != null) {
            job(() -> {
                synchronized (this) {
                    while (!isLoaded) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    handle(intent);
                }
            });
        }

        return START_NOT_STICKY;
    }

    public synchronized void handleIpChange() {
        if (!isLoaded || !mInitialized) {
            return;
        }

        job(() -> {
            try {

                mEmitter.fireIpChanged();
                IpChangeParam ipChangeParam = new IpChangeParam();
                ipChangeParam.setRestartListener(true);
                mEndpoint.handleIpChange(ipChangeParam);
                mEmitter.fireIpTransitioned();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int importance = NotificationManager.IMPORTANCE_DEFAULT;

            NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Background Service Channel",
                importance
            );

            serviceChannel.setSound(null, null);
            serviceChannel.enableVibration(false);
            serviceChannel.setShowBadge(false);
            serviceChannel.setDescription("This channel is used to enable calls when app is in background");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private synchronized void load() {
        try {
            Log.d(TAG, "System.loadLibrary('pjsua2');");
            System.loadLibrary("pjsua2");
        } catch (UnsatisfiedLinkError error) {
            Log.e(TAG, "Error while loading PJSIP pjsua2 native library", error);
            throw new RuntimeException(error);
        }

        try {
            Log.d(TAG, "if (mEndpoint == null) {");
            if (mEndpoint == null) {
                mEndpoint = new Endpoint();
                Log.d(TAG, "mEndpoint = new Endpoint();");
                mEndpoint.libCreate();
                Log.d(TAG, "mEndpoint.libCreate();");

                if (!Thread.currentThread().getName().equals(mRegisteredThread)) {
                    mEndpoint.libRegisterThread(Thread.currentThread().getName());
                    mRegisteredThread = Thread.currentThread().getName();
                }

                // Configure endpoint
                EpConfig epConfig = new EpConfig();
                epConfig.getLogConfig().setLevel(10);
                epConfig.getLogConfig().setConsoleLevel(10);
                mLogWriter = new PjSipLogWriter();
                epConfig.getLogConfig().setWriter(mLogWriter);

                if (mServiceConfiguration.isUserAgentNotEmpty()) {
                    epConfig.getUaConfig().setUserAgent(mServiceConfiguration.getUserAgent());
                } else {
                    epConfig.getUaConfig().setUserAgent("React Native PjSip (" + mEndpoint.libVersion().getFull() + ")");
                }

                if (mServiceConfiguration.isStunServersNotEmpty()) {
                    epConfig.getUaConfig().setStunServer(mServiceConfiguration.getStunServers());
                }

                epConfig.getMedConfig().setHasIoqueue(true);
                epConfig.getMedConfig().setClockRate(8000);
                epConfig.getMedConfig().setQuality(4);
                epConfig.getMedConfig().setEcOptions(1);
                epConfig.getMedConfig().setEcTailLen(200);
                epConfig.getMedConfig().setThreadCnt(2);

                mEndpoint.libInit(epConfig);
                Log.d(TAG, "mEndpoint.libInit(epConfig);");
                mTrash.add(epConfig);

                {
                    TransportConfig transportConfig = new TransportConfig();
                    transportConfig.setQosType(pj_qos_type.PJ_QOS_TYPE_VOICE);
                    mTlsTransportId = mEndpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, transportConfig);
                    mTrash.add(transportConfig);
                }
                Log.d(TAG, "mTrash.add(transportConfig);");
                mEndpoint.libStart();
                Log.d(TAG, "mEndpoint.libStart();");
                connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                networkChangeReceiver = new NetworkChangeReceiver(this, getApplicationContext());
                NetworkRequest networkRequest = new NetworkRequest.Builder().build();
                connectivityManager.registerNetworkCallback(networkRequest, networkChangeReceiver);
                notifyAll();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error while starting PJSIP", e);
        }
    }

    public synchronized void releaseSIPResources() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            if (mWorkerThread != null) {
                mWorkerThread.quitSafely();
                mWorkerThread = null;
            }
        }

        try {
            if (connectivityManager != null && networkChangeReceiver != null) {
                connectivityManager.unregisterNetworkCallback(networkChangeReceiver);
                networkChangeReceiver = null;
                connectivityManager = null;
            }
            for (PjSipCall call : mCalls) {
                evict(call);
            }
            for (PjSipAccount account : mAccounts) {
                evict(account);
            }
            if (mTlsTransportId != 0) {
                mEndpoint.transportClose(mTlsTransportId);
                mTlsTransportId = 0;
            }
            for (Object obj : mTrash) {
                if (obj instanceof PersistentObject) {
                    ((PersistentObject) obj).delete();
                }
            }
            mTrash.clear();

            if (mIncallWakeLock != null && mIncallWakeLock.isHeld()) {
                mIncallWakeLock.release();
                mIncallWakeLock = null;
            }

            if (mWifiLock != null && mWifiLock.isHeld()) {
                mWifiLock.release();
                mWifiLock = null;
            }

            if (mEndpoint != null) {
                mEndpoint.libDestroy();
                mEndpoint.delete();
                mEndpoint = null;
//                 mRegisteredThread = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to destroy PjSip library", e);
        }

        if (mInitialized) {
            try {
                unregisterReceiver(mPhoneStateChangedReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Receiver was not registered or already unregistered");
            }
        }

        mInitialized = false;
        isLoaded = false;
    }


    @Override
    public void onDestroy() {
        Log.d(TAG, "PJSIPService onDestroy()");
        releaseSIPResources();
        super.onDestroy();
    }

    private void job(Runnable job) {
        mHandler.post(job);
    }

    protected synchronized AudDevManager getAudDevManager() {
        return mEndpoint.audDevManager();
    }

    public void evict(final PjSipAccount account) {
        mAccounts.remove(account);
        account.delete();
    }

    public void evict(final PjSipCall call) {
        mCalls.remove(call);
        call.delete();
    }

    private void handle(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        Log.d(TAG, "Handle \"" + intent.getAction() + "\" action (" + ArgumentUtils.dumpIntentExtraParameters(intent) + ")");

        switch (intent.getAction()) {
            // General actions
            case PjActions.ACTION_START:
                handleStart(intent);
                break;

            // Account actions
            case PjActions.ACTION_CREATE_ACCOUNT:
                handleAccountCreate(intent);
                break;
            case PjActions.ACTION_REGISTER_ACCOUNT:
                handleAccountRegister(intent);
                break;
            case PjActions.ACTION_DELETE_ACCOUNT:
                handleAccountDelete(intent);
                break;

            // Call actions
            case PjActions.ACTION_MAKE_CALL:
                handleCallMake(intent);
                break;
            case PjActions.ACTION_HANGUP_CALL:
                handleCallHangup(intent);
                break;
            case PjActions.ACTION_DECLINE_CALL:
                handleCallDecline(intent);
                break;
            case PjActions.ACTION_ANSWER_CALL:
                handleCallAnswer(intent);
                break;
            case PjActions.ACTION_HOLD_CALL:
                handleCallSetOnHold(intent);
                break;
            case PjActions.ACTION_UNHOLD_CALL:
                handleCallReleaseFromHold(intent);
                break;
            case PjActions.ACTION_MUTE_CALL:
                handleCallMute(intent);
                break;
            case PjActions.ACTION_UNMUTE_CALL:
                handleCallUnMute(intent);
                break;
            case PjActions.ACTION_USE_SPEAKER_CALL:
                handleCallUseSpeaker(intent);
                break;
            case PjActions.ACTION_USE_EARPIECE_CALL:
                handleCallUseEarpiece(intent);
                break;
            case PjActions.ACTION_XFER_CALL:
                handleCallXFer(intent);
                break;
            case PjActions.ACTION_XFER_REPLACES_CALL:
                handleCallXFerReplaces(intent);
                break;
            case PjActions.ACTION_REDIRECT_CALL:
                handleCallRedirect(intent);
                break;
            case PjActions.ACTION_DTMF_CALL:
                handleCallDtmf(intent);
                break;
            case PjActions.ACTION_CHANGE_CODEC_SETTINGS:
                handleChangeCodecSettings(intent);
                break;
            case PjActions.ACTION_STOP:
                handleStop(intent);
                break;

            // Configuration actions
            case PjActions.ACTION_SET_SERVICE_CONFIGURATION:
                handleSetServiceConfiguration(intent);
                break;
        }
    }

    private void handleStart(Intent intent) {
        try {
            // Modify existing configuration if it changes during application reload.
            if (intent.hasExtra("service")) {
                ServiceConfigurationDTO newServiceConfiguration = ServiceConfigurationDTO.fromMap((Map) intent.getSerializableExtra("service"));
                if (!newServiceConfiguration.equals(mServiceConfiguration)) {
                    updateServiceConfiguration(newServiceConfiguration);
                }
            }

            CodecInfoVector2 codVect = mEndpoint.codecEnum2();
            JSONObject codecs = new JSONObject();

            for (int i = 0; i < codVect.size(); i++) {
                CodecInfo codInfo = codVect.get(i);
                String codId = codInfo.getCodecId();
                short priority = codInfo.getPriority();
                codecs.put(codId, priority);
                codInfo.delete();
            }

            JSONObject settings = mServiceConfiguration.toJson();
            settings.put("codecs", codecs);

            mEmitter.fireStarted(intent, mAccounts, mCalls, settings);
        } catch (Exception error) {
            Log.e(TAG, "Error while building codecs list", error);
            throw new RuntimeException(error);
        }
    }

    private void handleStop(Intent intent) {
        try {
            stopForeground(true);
            isForeground = false;
            releaseSIPResources();
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleSetServiceConfiguration(Intent intent) {
        try {
            updateServiceConfiguration(ServiceConfigurationDTO.fromIntent(intent));
            mEmitter.fireIntentHandled(intent, mServiceConfiguration.toJson());
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void updateServiceConfiguration(ServiceConfigurationDTO configuration) {
        mServiceConfiguration = configuration;
    }

    private void handleAccountCreate(Intent intent) {
        try {
            AccountConfigurationDTO accountConfiguration = AccountConfigurationDTO.fromIntent(intent);
            PjSipAccount account = doAccountCreate(accountConfiguration);
            mEmitter.fireAccountCreated(intent, account);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleAccountRegister(Intent intent) {
        try {
            int accountId = intent.getIntExtra("account_id", -1);
            boolean renew = intent.getBooleanExtra("renew", false);
            PjSipAccount account = findAccount(accountId);
            account.register(renew);
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private PjSipAccount doAccountCreate(AccountConfigurationDTO configuration) throws Exception {
        AccountConfig cfg = new AccountConfig();

        // General settings
        AuthCredInfo cred = new AuthCredInfo(
            "Digest",
            configuration.getNomalizedRegServer(),
            configuration.getUsername(),
            0,
            configuration.getPassword()
        );

        String idUri = configuration.getIdUri();
        String regUri = configuration.getRegUri();

        cfg.setIdUri(idUri);
        cfg.getRegConfig().setRegistrarUri(regUri);
        cfg.getRegConfig().setRegisterOnAdd(configuration.isRegOnAdd());
        cfg.getSipConfig().getAuthCreds().add(cred);

        // Registration settings

        if (configuration.getContactParams() != null) {
            cfg.getSipConfig().setContactParams(configuration.getContactParams());
        }
        if (configuration.getContactUriParams() != null) {
            cfg.getSipConfig().setContactUriParams(configuration.getContactUriParams());
        }
        if (configuration.getRegContactParams() != null) {
            Log.w(TAG, "Property regContactParams are not supported on android, use contactParams instead");
        }

        if (configuration.getRegHeaders() != null && configuration.getRegHeaders().size() > 0) {
            SipHeaderVector headers = new SipHeaderVector();

            for (Map.Entry<String, String> entry : configuration.getRegHeaders().entrySet()) {
                SipHeader hdr = new SipHeader();
                hdr.setHName(entry.getKey());
                hdr.setHValue(entry.getValue());
                headers.add(hdr);
            }

            cfg.getRegConfig().setHeaders(headers);
        }

        int transportId = mTlsTransportId;

        if (configuration.isTransportNotEmpty()) {
            TransportConfig transportConfig = new TransportConfig();

            switch (configuration.getTransport()) {
                case "TLS":
                    transportId = mTlsTransportId;
                    transportConfig.setQosType(pj_qos_type.PJ_QOS_TYPE_VOICE);
                    transportId = mEndpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, transportConfig);
                    mTrash.add(transportConfig);
                    break;
                default:
                    Log.w(TAG, "Illegal \""+ configuration.getTransport() +"\" transport (possible values are UDP, TCP or TLS) use TCP instead");
                    break;
            }
        }

        cfg.getSipConfig().setTransportId(transportId);

        if (configuration.isProxyNotEmpty()) {
            StringVector v = new StringVector();
            v.add(configuration.getProxy());
            cfg.getSipConfig().setProxies(v);
        }

        cfg.getMediaConfig().getTransportConfig().setQosType(pj_qos_type.PJ_QOS_TYPE_VOICE);

        PjSipAccount account = new PjSipAccount(this, transportId, configuration);
        account.create(cfg);

        mTrash.add(cfg);
        mTrash.add(cred);

        mAccounts.add(account);

        return account;
    }

    private void handleAccountDelete(Intent intent) {
        try {
            int accountId = intent.getIntExtra("account_id", -1);
            PjSipAccount account = findAccount(accountId);
            evict(account);
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallMake(Intent intent) {
        try {
            int accountId = intent.getIntExtra("account_id", -1);
            Log.d(TAG, "PjSipAccount account = findAccount(accountId);");
            PjSipAccount account = findAccount(accountId);
            String destination = intent.getStringExtra("destination");
            String settingsJson = intent.getStringExtra("settings");
            String messageJson = intent.getStringExtra("message");

            Log.d(TAG, "CallOpParam callOpParam = new CallOpParam(true);");
            CallOpParam callOpParam = new CallOpParam(true);

            if (settingsJson != null) {
                Log.d(TAG, "CallSettingsDTO settingsDTO = CallSettingsDTO.fromJson(settingsJson);");
                CallSettingsDTO settingsDTO = CallSettingsDTO.fromJson(settingsJson);
                Log.d(TAG, "CallSetting callSettings = new CallSetting();");
                CallSetting callSettings = new CallSetting();

                if (settingsDTO.getAudioCount() != null) {
                    callSettings.setAudioCount(settingsDTO.getAudioCount());
                }
                if (settingsDTO.getFlag() != null) {
                    callSettings.setFlag(settingsDTO.getFlag());
                }
                if (settingsDTO.getRequestKeyframeMethod() != null) {
                    callSettings.setReqKeyframeMethod(settingsDTO.getRequestKeyframeMethod());
                }

                callOpParam.setOpt(callSettings);
                mTrash.add(callSettings);
            }

            if (messageJson != null) {
                Log.d(TAG, "SipMessageDTO messageDTO = SipMessageDTO.fromJson(messageJson);");
                SipMessageDTO messageDTO = SipMessageDTO.fromJson(messageJson);
                Log.d(TAG, "SipTxOption callTxOption = new SipTxOption();");
                SipTxOption callTxOption = new SipTxOption();

                if (messageDTO.getTargetUri() != null) {
                    callTxOption.setTargetUri(messageDTO.getTargetUri());
                }
                if (messageDTO.getContentType() != null) {
                    callTxOption.setContentType(messageDTO.getContentType());
                }
                if (messageDTO.getHeaders() != null) {
                    callTxOption.setHeaders(PjSipUtils.mapToSipHeaderVector(messageDTO.getHeaders()));
                }
                if (messageDTO.getBody() != null) {
                    callTxOption.setMsgBody(messageDTO.getBody());
                }

                callOpParam.setTxOption(callTxOption);
                mTrash.add(callTxOption);
            }

            Log.d(TAG, "PjSipCall call = new PjSipCall(account);");
            PjSipCall call = new PjSipCall(account);
            call.makeCall(destination, callOpParam);
            callOpParam.delete();

            doPauseParallelCalls(call);

            mCalls.add(call);
            Log.d(TAG, "mEmitter.fireIntentHandled(intent, call.toJson());");
            mEmitter.fireIntentHandled(intent, call.toJson());
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallHangup(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            PjSipCall call = findCall(callId);
            call.hangup(new CallOpParam(true));
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallDecline(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            PjSipCall call = findCall(callId);
            CallOpParam prm = new CallOpParam(true);
            prm.setStatusCode(pjsip_status_code.PJSIP_SC_DECLINE);
            call.hangup(prm);
            prm.delete();
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallAnswer(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            PjSipCall call = findCall(callId);
            CallOpParam prm = new CallOpParam();
            prm.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
            call.answer(prm);
            doPauseParallelCalls(call);
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallSetOnHold(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            PjSipCall call = findCall(callId);
            call.hold();
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallReleaseFromHold(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            PjSipCall call = findCall(callId);
            call.unhold();
            doPauseParallelCalls(call);
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallMute(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            PjSipCall call = findCall(callId);
            call.mute();
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallUnMute(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            PjSipCall call = findCall(callId);
            call.unmute();
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallUseSpeaker(Intent intent) {
        try {
            mAudioManager.setSpeakerphoneOn(true);
            mUseSpeaker = true;
            for (PjSipCall call : mCalls) {
                emmitCallUpdated(call);
            }
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallUseEarpiece(Intent intent) {
        try {
            mAudioManager.setSpeakerphoneOn(false);
            mUseSpeaker = false;
            for (PjSipCall call : mCalls) {
                emmitCallUpdated(call);
            }
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallXFer(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            String destination = intent.getStringExtra("destination");
            PjSipCall call = findCall(callId);
            call.xfer(destination, new CallOpParam(true));
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallXFerReplaces(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            int destinationCallId = intent.getIntExtra("dest_call_id", -1);
            PjSipCall call = findCall(callId);
            PjSipCall destinationCall = findCall(destinationCallId);
            call.xferReplaces(destinationCall, new CallOpParam(true));
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallRedirect(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            String destination = intent.getStringExtra("destination");
            PjSipCall call = findCall(callId);
            call.redirect(destination);
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallDtmf(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            String digits = intent.getStringExtra("digits");
            PjSipCall call = findCall(callId);
            call.dialDtmf(digits);
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleChangeCodecSettings(Intent intent) {
        try {
            Bundle codecSettings = intent.getExtras();
            if (codecSettings != null) {
                CodecInfoVector2 codVect = mEndpoint.codecEnum2();
                List<String> availableCodecs = new ArrayList<>();

                for (int i = 0; i < codVect.size(); i++) {
                    CodecInfo codec = codVect.get(i);
                    availableCodecs.add(codec.getCodecId());
                    codec.delete();
                }

                for (String key : codecSettings.keySet()) {
                    if (!key.equals("callback_id")) {
                        short priority = (short) codecSettings.getInt(key);

                        if (availableCodecs.contains(key)) {
                            mEndpoint.codecSetPriority(key, priority);
                        } else {
                            Log.w(TAG, "Codec not found: " + key);
                        }
                    }
                }
            }
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error while changing codec settings", e);
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private PjSipAccount findAccount(int id) throws Exception {
        for (PjSipAccount account : mAccounts) {
            if (account.getId() == id) {
                return account;
            }
        }
        throw new Exception("Account with specified \"" + id + "\" id not found");
    }

    private PjSipCall findCall(int id) throws Exception {
        for (PjSipCall call : mCalls) {
            if (call.getId() == id) {
                return call;
            }
        }
        throw new Exception("Call with specified \"" + id + "\" id not found");
    }

    void emmitRegistrationChanged(PjSipAccount account, OnRegStateParam prm) {
        getEmitter().fireRegistrationChangeEvent(account);
    }

    void emmitMessageReceived(PjSipAccount account, PjSipMessage message) {
        getEmitter().fireMessageReceivedEvent(message);
    }

    void emmitCallReceived(PjSipAccount account, PjSipCall call) {
        mCalls.add(call);
        mEmitter.fireCallReceivedEvent(call);
    }

    void emmitCallStateChanged(PjSipCall call, OnCallStateParam prm) {
        try {
            if (call.getInfo().getState() == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                emmitCallTerminated(call, prm);
            } else {
                emmitCallChanged(call, prm);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to handle call state event", e);
        }
    }

    void emmitCallChanged(PjSipCall call, OnCallStateParam prm) {
        try {
            final int callId = call.getId();
            final int callState = call.getInfo().getState();

            job(() -> {
                if (mIncallWakeLock == null) {
                    mIncallWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "incall");
                }
                if (!mIncallWakeLock.isHeld()) {
                    mIncallWakeLock.acquire();
                }

                if (callState != pjsip_inv_state.PJSIP_INV_STATE_INCOMING && !mUseSpeaker && mAudioManager.isSpeakerphoneOn()) {
                    mAudioManager.setSpeakerphoneOn(false);
                }

                mWifiLock.acquire();

                if (callState == pjsip_inv_state.PJSIP_INV_STATE_EARLY || callState == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
                    mAudioManager.setMode(AudioManager.MODE_IN_CALL);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Failed to retrieve call state", e);
        }

        mEmitter.fireCallChanged(call);
    }

    void emmitCallTerminated(PjSipCall call, OnCallStateParam prm) {
        job(() -> {
            if (mCalls.size() == 1) {
                if (mIncallWakeLock != null && mIncallWakeLock.isHeld()) {
                    mIncallWakeLock.release();
                }
            }

            if (mCalls.size() == 1) {
                mWifiLock.release();
            }

            if (mCalls.size() == 1) {
                mAudioManager.setSpeakerphoneOn(false);
                mUseSpeaker = false;
                mAudioManager.setMode(AudioManager.MODE_NORMAL);
            }
        });

        mEmitter.fireCallTerminated(call);
        evict(call);
    }

    void emmitCallUpdated(PjSipCall call) {
        mEmitter.fireCallChanged(call);
    }

    /**
     * Pauses active calls once user answer to incoming calls.
     */
    private void doPauseParallelCalls(PjSipCall activeCall) {
        for (PjSipCall call : mCalls) {
            if (activeCall.getId() == call.getId()) {
                continue;
            }

            try {
                call.hold();
            } catch (Exception e) {
                Log.w(TAG, "Failed to put call on hold", e);
            }
        }
    }

    /**
     * Pauses all calls, used when received GSM call.
     */
    private void doPauseAllCalls() {
        for (PjSipCall call : mCalls) {
            try {
                call.hold();
            } catch (Exception e) {
                Log.w(TAG, "Failed to put call on hold", e);
            }
        }
    }

    protected class PhoneStateChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String extraState = intent.getStringExtra(TelephonyManager.EXTRA_STATE);

            if (TelephonyManager.EXTRA_STATE_RINGING.equals(extraState) || TelephonyManager.EXTRA_STATE_OFFHOOK.equals(extraState)) {
                Log.d(TAG, "GSM call received, pause all SIP calls.");

                job(PjSipService.this::doPauseAllCalls);
            }
        }
    }
}
