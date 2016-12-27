/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.pbap;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothPbap;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothPbap;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.sqlite.SQLiteException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicLong;

import com.android.bluetooth.ObexServerSockets;
import com.android.bluetooth.BluetoothObexTransport;
import com.android.bluetooth.IObexConnectionHandler;
import com.android.bluetooth.sdp.SdpManager;
import com.android.bluetooth.Utils;
import com.android.bluetooth.util.DevicePolicyUtils;

import com.android.bluetooth.R;
import com.android.bluetooth.btservice.AdapterService;

import java.io.IOException;

import javax.obex.ServerSession;

public class BluetoothPbapService extends Service implements IObexConnectionHandler{
    private static final String TAG = "BluetoothPbapService";
    public static final String LOG_TAG = "BluetoothPbap";

    /**
     * To enable PBAP DEBUG/VERBOSE logging - run below cmd in adb shell, and
     * restart com.android.bluetooth process. only enable DEBUG log:
     * "setprop log.tag.BluetoothPbapService DEBUG"; enable both VERBOSE and
     * DEBUG log: "setprop log.tag.BluetoothPbapService VERBOSE"
     */

    public static final boolean DEBUG = true;

    public static boolean VERBOSE = Log.isLoggable(LOG_TAG, Log.VERBOSE);

    /**
     * Intent indicating incoming obex authentication request which is from
     * PCE(Carkit)
     */
    public static final String AUTH_CHALL_ACTION = "com.android.bluetooth.pbap.authchall";

    /**
     * Intent indicating obex session key input complete by user which is sent
     * from BluetoothPbapActivity
     */
    public static final String AUTH_RESPONSE_ACTION = "com.android.bluetooth.pbap.authresponse";

    /**
     * Intent indicating user canceled obex authentication session key input
     * which is sent from BluetoothPbapActivity
     */
    public static final String AUTH_CANCELLED_ACTION = "com.android.bluetooth.pbap.authcancelled";

    /**
     * Intent indicating timeout for user confirmation, which is sent to
     * BluetoothPbapActivity
     */
    public static final String USER_CONFIRM_TIMEOUT_ACTION =
            "com.android.bluetooth.pbap.userconfirmtimeout";

    /**
     * Intent Extra name indicating session key which is sent from
     * BluetoothPbapActivity
     */
    public static final String EXTRA_SESSION_KEY = "com.android.bluetooth.pbap.sessionkey";

    public static final String THIS_PACKAGE_NAME = "com.android.bluetooth";

    public static final int MSG_SERVERSESSION_CLOSE = 5000;

    public static final int MSG_SESSION_ESTABLISHED = 5001;

    public static final int MSG_SESSION_DISCONNECTED = 5002;

    public static final int MSG_OBEX_AUTH_CHALL = 5003;

    public static final int MSG_ACQUIRE_WAKE_LOCK = 5004;

    public static final int MSG_RELEASE_WAKE_LOCK = 5005;

    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;

    private static final int START_LISTENER = 1;

    private static final int USER_TIMEOUT = 2;

    private static final int AUTH_TIMEOUT = 3;

    private static final int SHUTDOWN = 4;

    private static final int SDP_PBAP_SERVER_VERSION = 0x0102;

    private static final int SDP_PBAP_SUPPORTED_REPOSITORIES = 0x0003;

    private static final int SDP_PBAP_SUPPORTED_FEATURES = 0x021F;

    private static final int USER_CONFIRM_TIMEOUT_VALUE = 30000;

    private static final int RELEASE_WAKE_LOCK_DELAY = 10000;

    // Ensure not conflict with Opp notification ID
    private static final int NOTIFICATION_ID_ACCESS = -1000001;

    private static final int NOTIFICATION_ID_AUTH = -1000002;

    private static final int SDP_PBAP_AOSP_SERVER_VERSION = 0x0101;

    private static final int SDP_PBAP_AOSP_SUPPORTED_REPOSITORIES = 0x0001;

    private static final int SDP_PBAP_AOSP_SUPPORTED_FEATURES = 0x0003;

    private PowerManager.WakeLock mWakeLock = null;

    private BluetoothAdapter mAdapter;

    private BluetoothPbapAuthenticator mAuth = null;

    private BluetoothPbapObexServer mPbapServer;

    private ServerSession mServerSession = null;

    private ObexServerSockets mServerSockets = null;

    private AlarmManager mAlarmManager = null;

    private int mSdpHandle = -1;

    private boolean mRemoveTimeoutMsg = false;

    private BluetoothSocket mConnSocket = null;

    private BluetoothDevice mRemoteDevice = null;

    private static String sLocalPhoneNum = null;

    private static String sLocalPhoneName = null;

    private static String sRemoteDeviceName = null;

    private int mPermission = BluetoothDevice.ACCESS_UNKNOWN;

    private boolean mSdpSearchInitiated = false;

    private boolean mHasStarted = false;

    private volatile boolean mInterrupted;

    private int mState;

    private int mStartId = -1;

    //private IBluetooth mBluetoothService;

    private boolean mIsWaitingAuthorization = false;

    private static  AtomicLong mDbIndetifier = new AtomicLong();

    private PbapServiceMessageHandler mSessionStatusHandler;

    // package and class name to which we send intent to check phone book access permission
    private static final String ACCESS_AUTHORITY_PACKAGE = "com.android.settings";
    private static final String ACCESS_AUTHORITY_CLASS =
        "com.android.settings.bluetooth.BluetoothPermissionRequest";

    private ContentObserver contactChangeObserver;
    public static long primaryVersionCounter = 0;
    public static long secondaryVersionCounter = 0;

    public BluetoothPbapService() {
        mState = BluetoothPbap.STATE_DISCONNECTED;
        contactChangeObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                Log.d(TAG,"**************onChange on contact uri ************");
                primaryVersionCounter = primaryVersionCounter + 1;
            }
        };


    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (DEBUG) Log.d(TAG, "Enter - onCreate for service PBAP");
        mInterrupted = false;
        mAdapter = BluetoothAdapter.getDefaultAdapter();

        if (!mHasStarted) {
            mHasStarted = true;
            if (VERBOSE) Log.v(TAG, "Starting PBAP service");
            BluetoothPbapConfig.init(this);
            int state = mAdapter.getState();
            if (state == BluetoothAdapter.STATE_ON) {
                HandlerThread thread = new HandlerThread("BluetoothPbapHandler");
                thread.start();
                Looper looper = thread.getLooper();
                mSessionStatusHandler = new PbapServiceMessageHandler(looper);
                if (mSessionStatusHandler != null)
                    mSessionStatusHandler.sendMessage(mSessionStatusHandler
                        .obtainMessage(START_LISTENER));
            }
        }
        // Register observer on contact to update version counter
        try {
            if (DEBUG) Log.d(TAG,"Registering observer");
            getContentResolver().registerContentObserver(
               DevicePolicyUtils.getEnterprisePhoneUri(this), false, contactChangeObserver);
        } catch (SQLiteException e) {
            Log.e(TAG, "SQLite exception: " + e);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Illegal state exception, content observer is already registered");
        }
        if (DEBUG) Log.d(TAG, "Exit - onCreate for service PBAP");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //int retCode = super.onStartCommand(intent, flags, startId);
        //if (retCode == START_STICKY) {
            if (DEBUG) Log.d(TAG, "Enter - onStartCommand for service PBAP");
            mStartId = startId;
            if (mAdapter == null) {
                Log.d(TAG, "Stopping BluetoothPbapService: "
                        + "device does not have BT or device is not ready");
                // Release all resources
                if (mSessionStatusHandler != null){
                    Log.d(TAG, " onStartCommand, Shutting down");
                    mSessionStatusHandler.sendMessage(mSessionStatusHandler
                        .obtainMessage(SHUTDOWN));
                }
            } else {
                // No need to handle the null intent case, because we have
                // all restart work done in onCreate()
                if (intent != null) {
                    parseIntent(intent);
                }
            }
        //}
        if (DEBUG) Log.d(TAG, "Exit - onStartCommand for service PBAP");
        return START_NOT_STICKY;
    }

    // process the intent from receiver
    private void parseIntent(final Intent intent) {
        String action = intent.getStringExtra("action");
        if (action == null) return;             // Nothing to do
        if (DEBUG) Log.d(TAG, "action: " + action);


        boolean removeTimeoutMsg = true;
        if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (DEBUG) Log.d(TAG, "state: " + state);
            if (state == BluetoothAdapter.STATE_TURNING_OFF) {
                // Send any pending timeout now, as this service will be destroyed.
                if (mSessionStatusHandler != null){
                    if (mSessionStatusHandler.hasMessages(USER_TIMEOUT)) {
                        Intent timeoutIntent =
                            new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_CANCEL);
                        timeoutIntent.setClassName(ACCESS_AUTHORITY_PACKAGE, ACCESS_AUTHORITY_CLASS);
                        timeoutIntent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                                         BluetoothDevice.REQUEST_TYPE_PHONEBOOK_ACCESS);
                        sendBroadcast(timeoutIntent, BLUETOOTH_ADMIN_PERM);
                    }

                    Log.d(TAG, "Adapter turning off, SHUTDOWN..");
                    mSessionStatusHandler.sendMessage(mSessionStatusHandler
                        .obtainMessage(SHUTDOWN));
                }
            } else {
                if (state == BluetoothAdapter.STATE_ON && mSessionStatusHandler == null) {
                    /* It is possible that PBAP service was not killed
                     * when BT was off in previous iteration, so
                     * listener would not be restart as service would
                     * not be created again. Re-start the listeners explicitly.
                     */
                    if (DEBUG) Log.d(TAG, "Received BT on intent, while PBAP Service is not " +
                        "killed, restarting listeners");
                    HandlerThread thread = new HandlerThread("BluetoothPbapHandler");
                    thread.start();
                    Looper looper = thread.getLooper();
                    mSessionStatusHandler = new PbapServiceMessageHandler(looper);
                    if (mSessionStatusHandler != null)
                        mSessionStatusHandler.sendMessage(mSessionStatusHandler
                            .obtainMessage(START_LISTENER));
                }
                removeTimeoutMsg = false;
            }
        } else if (action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                && mIsWaitingAuthorization) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            if (mRemoteDevice == null || device == null) {
                Log.e(TAG, "Unexpected error!");
                return;
            }

            if (DEBUG) Log.d(TAG,"ACL disconnected for "+ device);

            if (mRemoteDevice.equals(device)) {
                Intent cancelIntent = new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_CANCEL);
                cancelIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
                cancelIntent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                                      BluetoothDevice.REQUEST_TYPE_PHONEBOOK_ACCESS);
                sendBroadcast(cancelIntent);
                mIsWaitingAuthorization = false;
                stopObexServerSession();
            }
        } else if (action.equals(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY)) {
            int requestType = intent.getIntExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                                           BluetoothDevice.REQUEST_TYPE_PHONEBOOK_ACCESS);

            if ((!mIsWaitingAuthorization)
                    || (requestType != BluetoothDevice.REQUEST_TYPE_PHONEBOOK_ACCESS)) {
                // this reply is not for us
                return;
            }

            mIsWaitingAuthorization = false;

            if (intent.getIntExtra(BluetoothDevice.EXTRA_CONNECTION_ACCESS_RESULT,
                                   BluetoothDevice.CONNECTION_ACCESS_NO)
                    == BluetoothDevice.CONNECTION_ACCESS_YES) {
                if (intent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)) {
                    boolean result = mRemoteDevice.setPhonebookAccessPermission(
                            BluetoothDevice.ACCESS_ALLOWED);
                    if (VERBOSE) {
                        Log.v(TAG, "setPhonebookAccessPermission(ACCESS_ALLOWED) result=" + result);
                    }
                }
                try {
                    if (mConnSocket != null) {
                        startObexServerSession();
                    } else {
                        stopObexServerSession();
                    }
                } catch (IOException ex) {
                    Log.e(TAG, "Caught the error: " + ex.toString());
                }
            } else {
                if (intent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)) {
                    boolean result = mRemoteDevice.setPhonebookAccessPermission(
                            BluetoothDevice.ACCESS_REJECTED);
                    if (DEBUG) {
                        Log.d(TAG, "setPhonebookAccessPermission(ACCESS_REJECTED) result="
                                + result);
                    }
                }
                stopObexServerSession();
            }
        } else if (action.equals(AUTH_RESPONSE_ACTION)) {
            String sessionkey = intent.getStringExtra(EXTRA_SESSION_KEY);
            notifyAuthKeyInput(sessionkey);
        } else if (action.equals(AUTH_CANCELLED_ACTION)) {
            notifyAuthCancelled();
        } else {
            removeTimeoutMsg = false;
        }

        if ((removeTimeoutMsg) && (mSessionStatusHandler != null)) {
            mSessionStatusHandler.removeMessages(USER_TIMEOUT);
        }
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "Pbap Service onDestroy");

        try {
            if (DEBUG) Log.d(TAG,"Unregistering observer");
                getContentResolver().unregisterContentObserver(contactChangeObserver);
        } catch (SQLiteException e) {
            Log.e(TAG, "SQLite exception: " + e);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Illegal state exception, content observer is not registered");
        }

        super.onDestroy();
        if (getState() != BluetoothPbap.STATE_DISCONNECTED) {
            setState(BluetoothPbap.STATE_DISCONNECTED, BluetoothPbap.RESULT_CANCELED);
        }
        if (DEBUG)
            Log.d(TAG, "StatusHandler :" + mSessionStatusHandler + " mInterrupted:" + mInterrupted);
        // synchronize call to closeService by sending SHUTDOWN Message
        if (mSessionStatusHandler != null && (!mInterrupted)) {
            if (DEBUG) Log.d(TAG, " onDestroy, sending SHUTDOWN Message");
            mSessionStatusHandler.sendMessage(mSessionStatusHandler.obtainMessage(SHUTDOWN));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (DEBUG) Log.d(TAG, "Pbap Service onBind");
        return mBinder;
    }

    synchronized private void startSocketListeners() {
        if (DEBUG) Log.d(TAG, "startsocketListener");
        if(!VERBOSE)
            VERBOSE = Log.isLoggable(LOG_TAG, Log.VERBOSE);
        if (VERBOSE) Log.v(TAG, "Pbap Service startRfcommSocketListener");

        if (mServerSession != null) {
            if (DEBUG) Log.d(TAG, "mServerSession exists - shutting it down...");
            mServerSession.close();
            mServerSession = null;
        }

        closeConnectionSocket();

        if (mServerSockets != null) {
            mServerSockets.prepareForNewConnect();
        } else {
            mServerSockets = ObexServerSockets.create(this);
            if (mServerSockets == null) {
                // TODO: Handle - was not handled before
                Log.e(TAG, "Failed to start the listeners");
                return;
            }
            if (mAdapter != null && mSdpHandle >= 0 &&
                                    SdpManager.getDefaultManager() != null) {
                Log.d(TAG, "Removing SDP record for PBAP with SDP handle: " +
                    mSdpHandle);
                boolean status = SdpManager.getDefaultManager().removeSdpRecord(mSdpHandle);
                Log.d(TAG, "RemoveSDPrecord returns " + status);
                mSdpHandle = -1;
            }
            if (SdpManager.getDefaultManager() != null) {
                boolean isDisabledNonAosp = getResources().getBoolean
                        (R.bool.disable_non_aosp_bt_features);
                if (DEBUG) Log.d(TAG, "isDisabledNonAosp :" + isDisabledNonAosp);
                if (isDisabledNonAosp) {
                    mSdpHandle = SdpManager.getDefaultManager().createPbapPseRecord
                            ("OBEX Phonebook Access Server",mServerSockets.getRfcommChannel(),
                            -1, SDP_PBAP_AOSP_SERVER_VERSION, SDP_PBAP_AOSP_SUPPORTED_REPOSITORIES,
                            SDP_PBAP_AOSP_SUPPORTED_FEATURES);
                } else {
                    mSdpHandle = SdpManager.getDefaultManager().createPbapPseRecord
                            ("OBEX Phonebook Access Server",mServerSockets.getRfcommChannel(),
                            mServerSockets.getL2capPsm(), SDP_PBAP_SERVER_VERSION,
                            SDP_PBAP_SUPPORTED_REPOSITORIES, SDP_PBAP_SUPPORTED_FEATURES);
                    // Here we might have changed crucial data, hence reset DB
                    // identifier
                    updateDbIdentifier();
                }
            }

            if(DEBUG) Log.d(TAG, "Creating new SDP record for PBAP server with handle: " + mSdpHandle);
        }
    }

    private void updateDbIdentifier(){
        mDbIndetifier.set(Calendar.getInstance().getTime().getTime());
    }

    public long getDbIdentifier() {
        return mDbIndetifier.get();
    }

    private void setUserTimeoutAlarm(){
        if (DEBUG) Log.d(TAG,"SetUserTimeOutAlarm()");
        if (mAlarmManager == null) {
            mAlarmManager =(AlarmManager) this.getSystemService (Context.ALARM_SERVICE);
        }
        mRemoveTimeoutMsg = true;
        Intent timeoutIntent =
                new Intent(USER_CONFIRM_TIMEOUT_ACTION);
        PendingIntent pIntent = PendingIntent.getBroadcast(this, 0, timeoutIntent, 0);
        mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() +
                USER_CONFIRM_TIMEOUT_VALUE,pIntent);
    }

    @Override
    public boolean onConnect(BluetoothDevice remoteDevice, BluetoothSocket socket) {
        mRemoteDevice = remoteDevice;
        if (mRemoteDevice == null) {
            Log.i(TAG, "getRemoteDevice() = null");
            return false;
        }

        if (socket != null)
            mConnSocket = socket;
        else
        return false;

        sRemoteDeviceName = mRemoteDevice.getName();
        // In case getRemoteName failed and return null
        if (TextUtils.isEmpty(sRemoteDeviceName)) {
            sRemoteDeviceName = getString(R.string.defaultname);
        }
        int permission = mRemoteDevice.getPhonebookAccessPermission();
        if (DEBUG) Log.d(TAG, "getPhonebookAccessPermission() = " + permission);

        if (permission == BluetoothDevice.ACCESS_ALLOWED) {
            try {
                if (VERBOSE) {
                    Log.v(TAG, "incoming connection accepted from: " + sRemoteDeviceName
                        + " automatically as already allowed device");
                }
                startObexServerSession();
            } catch (IOException ex) {
                Log.e(TAG, "Caught exception starting obex server session"
                        + ex.toString());
            }
        } else if (permission == BluetoothDevice.ACCESS_REJECTED) {
            if (DEBUG) {
                Log.d(TAG, "incoming connection rejected from: " + sRemoteDeviceName
                        + " automatically as already rejected device");
            }
            return false;
        } else {  // permission == BluetoothDevice.ACCESS_UNKNOWN
            // Send an Intent to Settings app to ask user preference.
            Intent intent =
                    new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_REQUEST);
            intent.setClassName(ACCESS_AUTHORITY_PACKAGE, ACCESS_AUTHORITY_CLASS);
            intent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                    BluetoothDevice.REQUEST_TYPE_PHONEBOOK_ACCESS);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
            intent.putExtra(BluetoothDevice.EXTRA_PACKAGE_NAME, getPackageName());
            intent.putExtra(BluetoothDevice.EXTRA_CLASS_NAME,
                    BluetoothPbapReceiver.class.getName());

            mIsWaitingAuthorization = true;
            sendOrderedBroadcast(intent, BLUETOOTH_ADMIN_PERM);

            if (VERBOSE) Log.v(TAG, "waiting for authorization for connection from: "
                    + sRemoteDeviceName);

            // In case car kit time out and try to use HFP for
            // phonebook
            // access, while UI still there waiting for user to
            // confirm
            if (mSessionStatusHandler != null)
                mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
                    .obtainMessage(USER_TIMEOUT), USER_CONFIRM_TIMEOUT_VALUE);
            // We will continue the process when we receive
            // BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY from Settings app.
        }

        return true;

    };

    /**
     * Called when an unrecoverable error occurred in an accept thread.
     * Close down the server socket, and restart.
     * TODO: Change to message, to call start in correct context.
     */
    @Override
    public synchronized void onAcceptFailed() {
        //Force socket listener to restart
        mServerSockets = null;
        if (!mInterrupted && mAdapter != null && mAdapter.isEnabled()) {
            startSocketListeners();
        }
    }

    private final synchronized void closeServerSocket() {

       // exit SocketAcceptThread early
       if (mServerSockets != null) {
           mServerSockets.shutdown(false);
           mServerSockets = null;
       }
    }

    private final synchronized void closeConnectionSocket() {
        if (mConnSocket != null) {
            try {
                mConnSocket.close();
                mConnSocket = null;
            } catch (IOException e) {
                Log.e(TAG, "Close Connection Socket error: " + e.toString());
            }
        }
    }

    private final void closeService() {
        if (DEBUG) Log.d(TAG, "Pbap Service closeService in");

        // exit initSocket early
        mInterrupted = true;

        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        if (mServerSession != null) {
            mServerSession.close();
            mServerSession = null;
        }

        if (mAdapter != null && mSdpHandle >= 0 &&
                                SdpManager.getDefaultManager() != null) {
            Log.d(TAG, "Removing SDP record for PBAP with SDP handle: " +
                mSdpHandle);
            boolean status = SdpManager.getDefaultManager().removeSdpRecord(mSdpHandle);
            Log.d(TAG, "RemoveSDPrecord returns " + status);
            mSdpHandle = -1;
        }

        closeConnectionSocket();
        closeServerSocket();

        mHasStarted = false;
        if (mStartId != -1 && stopSelfResult(mStartId)) {
            if (VERBOSE) Log.v(TAG, "successfully stopped pbap service");
            mStartId = -1;
        }

        if(mSessionStatusHandler != null) {
            mSessionStatusHandler.removeCallbacksAndMessages(null);
            Looper looper = mSessionStatusHandler.getLooper();
            if (looper != null) {
                looper.quit();
            }
            mSessionStatusHandler = null;
        }

        if (DEBUG) Log.d(TAG, "Pbap Service closeService out");
    }

    private final void startObexServerSession() throws IOException {
        if (DEBUG) Log.d(TAG, "Pbap Service startObexServerSession");

        // acquire the wakeLock before start Obex transaction thread
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "StartingObexPbapTransaction");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
        }
        TelephonyManager tm = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            sLocalPhoneNum = tm.getLine1Number();
            sLocalPhoneName = tm.getLine1AlphaTag();
            if (TextUtils.isEmpty(sLocalPhoneName)) {
                sLocalPhoneName = this.getString(R.string.localPhoneName);
            }
        }

        mPbapServer = new BluetoothPbapObexServer(mSessionStatusHandler, this, this);
        synchronized (this) {
            mAuth = new BluetoothPbapAuthenticator(mSessionStatusHandler);
            mAuth.setChallenged(false);
            mAuth.setCancelled(false);
        }
        BluetoothObexTransport transport = new BluetoothObexTransport(mConnSocket);
        mServerSession = new ServerSession(transport, mPbapServer, mAuth);
        setState(BluetoothPbap.STATE_CONNECTED);

        mSessionStatusHandler.removeMessages(MSG_RELEASE_WAKE_LOCK);
        mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
            .obtainMessage(MSG_RELEASE_WAKE_LOCK), RELEASE_WAKE_LOCK_DELAY);

        if (VERBOSE) {
            Log.v(TAG, "startObexServerSession() success!");
        }
    }

    private void stopObexServerSession() {
        if (DEBUG) Log.d(TAG, "Pbap Service stopObexServerSession");

        mSessionStatusHandler.removeMessages(MSG_ACQUIRE_WAKE_LOCK);
        mSessionStatusHandler.removeMessages(MSG_RELEASE_WAKE_LOCK);
        // Release the wake lock if obex transaction is over
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        if (mServerSession != null) {
            mServerSession.close();
            mServerSession = null;
        }

        closeConnectionSocket();

        // Last obex transaction is finished, we start to listen for incoming
        // connection again
        if (mAdapter.isEnabled()) {
            startSocketListeners();
        }
        setState(BluetoothPbap.STATE_DISCONNECTED);
    }

    private void notifyAuthKeyInput(final String key) {
        synchronized (mAuth) {
            if (key != null) {
                mAuth.setSessionKey(key);
            }
            mAuth.setChallenged(true);
            mAuth.notify();
        }
    }

    private void notifyAuthCancelled() {
        synchronized (mAuth) {
            mAuth.setCancelled(true);
            mAuth.notify();
        }
    }

    private final class PbapServiceMessageHandler extends Handler {
        private PbapServiceMessageHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg) {
            if (VERBOSE) Log.v(TAG, "Handler(): got msg=" + msg.what);

            switch (msg.what) {
                case START_LISTENER:
                    if (mAdapter.isEnabled()) {
                        startSocketListeners();
                    } else {
                        closeService();// release all resources
                    }
                    break;
                case USER_TIMEOUT:
                    Intent intent = new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_CANCEL);
                    intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
                    intent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                                    BluetoothDevice.REQUEST_TYPE_PHONEBOOK_ACCESS);
                    sendBroadcast(intent);
                    mIsWaitingAuthorization = false;
                    stopObexServerSession();
                    break;
                case AUTH_TIMEOUT:
                    Intent i = new Intent(USER_CONFIRM_TIMEOUT_ACTION);
                    sendBroadcast(i);
                    removePbapNotification(NOTIFICATION_ID_AUTH);
                    notifyAuthCancelled();
                    break;
                case MSG_SERVERSESSION_CLOSE:
                    stopObexServerSession();
                    break;
                case MSG_SESSION_ESTABLISHED:
                    break;
                case MSG_SESSION_DISCONNECTED:
                    // case MSG_SERVERSESSION_CLOSE will handle ,so just skip
                    break;
                case MSG_OBEX_AUTH_CHALL:
                    createPbapNotification(AUTH_CHALL_ACTION);
                    mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
                            .obtainMessage(AUTH_TIMEOUT), USER_CONFIRM_TIMEOUT_VALUE);
                    break;
                case SHUTDOWN:
                    if (DEBUG) Log.d(TAG, "Closing PBAP service ");
                    closeService();
                    break;
                case MSG_ACQUIRE_WAKE_LOCK:
                    if (mWakeLock == null) {
                        PowerManager pm = (PowerManager)getSystemService(
                                          Context.POWER_SERVICE);
                        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                    "StartingObexPbapTransaction");
                        mWakeLock.setReferenceCounted(false);
                        mWakeLock.acquire();
                        Log.w(TAG, "Acquire Wake Lock");
                    }
                    mSessionStatusHandler.removeMessages(MSG_RELEASE_WAKE_LOCK);
                    mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
                      .obtainMessage(MSG_RELEASE_WAKE_LOCK), RELEASE_WAKE_LOCK_DELAY);
                    break;
                case MSG_RELEASE_WAKE_LOCK:
                    if (mWakeLock != null) {
                        mWakeLock.release();
                        mWakeLock = null;
                        Log.w(TAG, "Release Wake Lock");
                    }
                    break;
                default:
                    break;
            }
        }
    };

    private void setState(int state) {
        setState(state, BluetoothPbap.RESULT_SUCCESS);
    }

    private int getState() {
        return mState;
    }

    private synchronized void setState(int state, int result) {
        if (state != mState) {
            if (DEBUG) Log.d(TAG, "Pbap state " + mState + " -> " + state + ", result = "
                    + result);
            int prevState = mState;
            mState = state;
            Intent intent = new Intent(BluetoothPbap.PBAP_STATE_CHANGED_ACTION);
            intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
            intent.putExtra(BluetoothProfile.EXTRA_STATE, mState);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
            sendBroadcast(intent, BLUETOOTH_PERM);
            AdapterService s = AdapterService.getAdapterService();
            if (s != null) {
                s.onProfileConnectionStateChanged(mRemoteDevice, BluetoothProfile.PBAP,
                        mState, prevState);
            }
        }
    }

    private void createPbapNotification(String action) {

        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);

        // Create an intent triggered by clicking on the status icon.
        Intent clickIntent = new Intent();
        clickIntent.setClass(this, BluetoothPbapActivity.class);
        clickIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        clickIntent.setAction(action);

        // Create an intent triggered by clicking on the
        // "Clear All Notifications" button
        Intent deleteIntent = new Intent();
        deleteIntent.setClass(this, BluetoothPbapReceiver.class);

        Notification notification = null;
        String name = getRemoteDeviceName();

        if (action.equals(AUTH_CHALL_ACTION)) {
            deleteIntent.setAction(AUTH_CANCELLED_ACTION);
            notification = new Notification(android.R.drawable.stat_sys_data_bluetooth,
                getString(R.string.auth_notif_ticker), System.currentTimeMillis());
            notification.color = getResources().getColor(
                    com.android.internal.R.color.system_notification_accent_color);
            notification.setLatestEventInfo(this, getString(R.string.auth_notif_title),
                    getString(R.string.auth_notif_message, name), PendingIntent
                            .getActivity(this, 0, clickIntent, 0));

            notification.flags |= Notification.FLAG_AUTO_CANCEL;
            notification.flags |= Notification.FLAG_ONLY_ALERT_ONCE;
            notification.defaults = Notification.DEFAULT_SOUND;
            notification.deleteIntent = PendingIntent.getBroadcast(this, 0, deleteIntent, 0);
            nm.notify(NOTIFICATION_ID_AUTH, notification);
        }
    }

    private void removePbapNotification(int id) {
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(id);
    }

    public static String getLocalPhoneNum() {
        return sLocalPhoneNum;
    }

    public static String getLocalPhoneName() {
        return sLocalPhoneName;
    }

    public static String getRemoteDeviceName() {
        return sRemoteDeviceName;
    }

    /**
     * Handlers for incoming service calls
     */
    private final IBluetoothPbap.Stub mBinder = new IBluetoothPbap.Stub() {
        public int getState() {
            if (DEBUG) Log.d(TAG, "getState " + mState);

            if (!Utils.checkCaller()) {
                Log.w(TAG,"getState(): not allowed for non-active user");
                return BluetoothPbap.STATE_DISCONNECTED;
            }

            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mState;
        }

        public BluetoothDevice getClient() {
            if (DEBUG) Log.d(TAG, "getClient" + mRemoteDevice);

            if (!Utils.checkCaller()) {
                Log.w(TAG,"getClient(): not allowed for non-active user");
                return null;
            }

            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            if (mState == BluetoothPbap.STATE_DISCONNECTED) {
                return null;
            }
            return mRemoteDevice;
        }

        public boolean isConnected(BluetoothDevice device) {
            if (!Utils.checkCaller()) {
                Log.w(TAG,"isConnected(): not allowed for non-active user");
                return false;
            }

            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mState == BluetoothPbap.STATE_CONNECTED && mRemoteDevice.equals(device);
        }

        public boolean connect(BluetoothDevice device) {
            if (!Utils.checkCaller()) {
                Log.w(TAG,"connect(): not allowed for non-active user");
                return false;
            }

            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                    "Need BLUETOOTH_ADMIN permission");
            return false;
        }

        public void disconnect() {
            if (DEBUG) Log.d(TAG, "disconnect");

            if (!Utils.checkCaller()) {
                Log.w(TAG,"disconnect(): not allowed for non-active user");
                return;
            }

            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                    "Need BLUETOOTH_ADMIN permission");
            synchronized (BluetoothPbapService.this) {
                switch (mState) {
                    case BluetoothPbap.STATE_CONNECTED:
                        if (mServerSession != null) {
                            mServerSession.close();
                            mServerSession = null;
                        }

                        closeConnectionSocket();

                        setState(BluetoothPbap.STATE_DISCONNECTED, BluetoothPbap.RESULT_CANCELED);
                        break;
                    default:
                        break;
                }
            }
        }
    };
}
