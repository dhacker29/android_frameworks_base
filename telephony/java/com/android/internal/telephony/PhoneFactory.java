/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony;

import android.content.Context;
import android.net.LocalServerSocket;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.os.SystemProperties;

import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.cdma.CDMALTEPhone;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;
import com.android.internal.telephony.gsm.GSMPhone;
import com.android.internal.telephony.sip.SipPhone;
import com.android.internal.telephony.sip.SipPhoneFactory;
import com.android.internal.telephony.uicc.UiccController;

import java.lang.reflect.Constructor;

/**
 * {@hide}
 */
public class PhoneFactory {
    static final String LOG_TAG = "PHONE";
    static protected final int SOCKET_OPEN_RETRY_MILLIS = 2 * 1000;
    static protected final int SOCKET_OPEN_MAX_RETRY = 3;

    //***** Class Variables

    static protected Phone sProxyPhone = null;
    static protected CommandsInterface sCommandsInterface = null;

    static protected boolean sMadeDefaults = false;
    static protected PhoneNotifier sPhoneNotifier;
    static protected Looper sLooper;
    static protected Context sContext;

    protected static final int preferredCdmaSubscription =
                         CdmaSubscriptionSourceManager.PREFERRED_CDMA_SUBSCRIPTION;

    //***** Class Methods

    public static void makeDefaultPhones(Context context) {
        makeDefaultPhone(context);
    }

    /**
     * FIXME replace this with some other way of making these
     * instances
     */
    public static void makeDefaultPhone(Context context) {
        synchronized(Phone.class) {
            if (!sMadeDefaults) {
                sLooper = Looper.myLooper();
                sContext = context;

                if (sLooper == null) {
                    throw new RuntimeException(
                        "PhoneFactory.makeDefaultPhone must be called from Looper thread");
                }

                int retryCount = 0;
                for(;;) {
                    boolean hasException = false;
                    retryCount ++;

                    try {
                        // use UNIX domain socket to
                        // prevent subsequent initialization
                        new LocalServerSocket("com.android.internal.telephony");
                    } catch (java.io.IOException ex) {
                        hasException = true;
                    }

                    if ( !hasException ) {
                        break;
                    } else if (retryCount > SOCKET_OPEN_MAX_RETRY) {
                        throw new RuntimeException("PhoneFactory probably already running");
                    } else {
                        try {
                            Thread.sleep(SOCKET_OPEN_RETRY_MILLIS);
                        } catch (InterruptedException er) {
                        }
                    }
                }

                sPhoneNotifier = new DefaultPhoneNotifier();

                // Get preferred network mode
                int preferredNetworkMode = RILConstants.PREFERRED_NETWORK_MODE;
                if (BaseCommands.getLteOnCdmaModeStatic() == Phone.LTE_ON_CDMA_TRUE) {
                    preferredNetworkMode = Phone.NT_MODE_GLOBAL;
                }
                if (BaseCommands.getLteOnGsmModeStatic() != 0) {
                    preferredNetworkMode = Phone.NT_MODE_LTE_GSM_WCDMA;
                }
                int networkMode = Settings.Secure.getInt(context.getContentResolver(),
                        Settings.Secure.PREFERRED_NETWORK_MODE, preferredNetworkMode);
                Log.i(LOG_TAG, "Network Mode set to " + Integer.toString(networkMode));


                //Get cdmaSubscription mode from Settings.Secure
                int cdmaSubscription;
                cdmaSubscription = Settings.Secure.getInt(context.getContentResolver(),
                                Settings.Secure.CDMA_SUBSCRIPTION_MODE,
                                preferredCdmaSubscription);
                Log.i(LOG_TAG, "Cdma Subscription set to " + cdmaSubscription);

                //reads the system properties and makes commandsinterface
                String sRILClassname = SystemProperties.get("ro.telephony.ril_class", "RIL");
                Log.i(LOG_TAG, "RILClassname is " + sRILClassname);

                // Use reflection to construct the RIL class (defaults to RIL)
                try {
                    Class<?> classDefinition = Class.forName("com.android.internal.telephony." + sRILClassname);
                    Constructor<?> constructor = classDefinition.getConstructor(new Class[] {Context.class, int.class, int.class});
                    sCommandsInterface = (RIL) constructor.newInstance(new Object[] {context, networkMode, cdmaSubscription});
                } catch (Exception e) {
                    // 6 different types of exceptions are thrown here that it's
                    // easier to just catch Exception as our "error handling" is the same.
                    Log.wtf(LOG_TAG, "Unable to construct command interface", e);
                    throw new RuntimeException(e);
                }

                // Instantiate UiccController so that all other classes can just call getInstance()
                UiccController.make(context, sCommandsInterface);

                int phoneType = getPhoneType(networkMode);
                if (phoneType == Phone.PHONE_TYPE_GSM) {
                    Log.i(LOG_TAG, "Creating GSMPhone");
                    sProxyPhone = new PhoneProxy(new GSMPhone(context,
                            sCommandsInterface, sPhoneNotifier));
                } else if (phoneType == Phone.PHONE_TYPE_CDMA) {
                    switch (BaseCommands.getLteOnCdmaModeStatic()) {
                        case Phone.LTE_ON_CDMA_TRUE:
                            Log.i(LOG_TAG, "Creating CDMALTEPhone");
                            sProxyPhone = new PhoneProxy(new CDMALTEPhone(context,
                                sCommandsInterface, sPhoneNotifier));
                            break;
                        case Phone.LTE_ON_CDMA_FALSE:
                        default:
                            Log.i(LOG_TAG, "Creating CDMAPhone");
                            sProxyPhone = new PhoneProxy(new CDMAPhone(context,
                                    sCommandsInterface, sPhoneNotifier));
                            break;
                    }
                }

                sMadeDefaults = true;
            }
        }
    }

    /*
     * This function returns the type of the phone, depending
     * on the network mode.
     *
     * @param network mode
     * @return Phone Type
     */
    public static int getPhoneType(int networkMode) {
        switch(networkMode) {
        case RILConstants.NETWORK_MODE_CDMA:
        case RILConstants.NETWORK_MODE_CDMA_NO_EVDO:
        case RILConstants.NETWORK_MODE_EVDO_NO_CDMA:
            return Phone.PHONE_TYPE_CDMA;

        case RILConstants.NETWORK_MODE_WCDMA_PREF:
        case RILConstants.NETWORK_MODE_GSM_ONLY:
        case RILConstants.NETWORK_MODE_WCDMA_ONLY:
        case RILConstants.NETWORK_MODE_GSM_UMTS:
        case RILConstants.NETWORK_MODE_LTE_GSM_WCDMA:
        case RILConstants.NETWORK_MODE_LTE_WCDMA:
            return Phone.PHONE_TYPE_GSM;

        // Use CDMA Phone for the global mode including CDMA
        case RILConstants.NETWORK_MODE_GLOBAL:
        case RILConstants.NETWORK_MODE_LTE_CDMA_EVDO:
        case RILConstants.NETWORK_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
            return Phone.PHONE_TYPE_CDMA;

        case RILConstants.NETWORK_MODE_LTE_ONLY:
            if (BaseCommands.getLteOnCdmaModeStatic() == Phone.LTE_ON_CDMA_TRUE) {
                return Phone.PHONE_TYPE_CDMA;
            } else {
                return Phone.PHONE_TYPE_GSM;
            }
        default:
            return Phone.PHONE_TYPE_GSM;
        }
    }

    public static Phone getDefaultPhone() {
        if (sLooper != Looper.myLooper()) {
            throw new RuntimeException(
                "PhoneFactory.getDefaultPhone must be called from Looper thread");
        }

        if (!sMadeDefaults) {
            throw new IllegalStateException("Default phones haven't been made yet!");
        }
       return sProxyPhone;
    }

    public static Phone getCdmaPhone() {
        Phone phone;
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            switch (BaseCommands.getLteOnCdmaModeStatic()) {
                case Phone.LTE_ON_CDMA_TRUE: {
                    phone = new CDMALTEPhone(sContext, sCommandsInterface, sPhoneNotifier);
                    break;
                }
                case Phone.LTE_ON_CDMA_FALSE:
                case Phone.LTE_ON_CDMA_UNKNOWN:
                default: {
                    phone = new CDMAPhone(sContext, sCommandsInterface, sPhoneNotifier);
                    break;
                }
            }
        }
        return phone;
    }

    public static Phone getGsmPhone() {
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            Phone phone = new GSMPhone(sContext, sCommandsInterface, sPhoneNotifier);
            return phone;
        }
    }

    /**
     * Makes a {@link SipPhone} object.
     * @param sipUri the local SIP URI the phone runs on
     * @return the {@code SipPhone} object or null if the SIP URI is not valid
     */
    public static SipPhone makeSipPhone(String sipUri) {
        return SipPhoneFactory.makePhone(sipUri, sContext, sPhoneNotifier);
    }
}
