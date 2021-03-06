/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2012, Code Aurora Forum. All rights reserved
 *
 * Not a Contribution, Apache license notifications and license are retained
 * for attribution purposes only.
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

package com.android.internal.policy.impl;

import com.android.internal.widget.LockPatternUtils;

import android.content.Context;
import com.android.internal.telephony.IccCard;
import android.telephony.MSimTelephonyManager;
import android.telephony.TelephonyManager;

/**
 * Knows how to create a lock pattern keyguard view, and answer questions about
 * it (even if it hasn't been created, per the interface specs).
 */
public class LockPatternKeyguardViewProperties implements KeyguardViewProperties {

    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardUpdateMonitor mUpdateMonitor;

    /**
     * @param lockPatternUtils Used to know whether the pattern enabled, and passed
     *   onto the keygaurd view when it is created.
     * @param updateMonitor Used to know whether the sim pin is enabled, and passed
     *   onto the keyguard view when it is created.
     */
    public LockPatternKeyguardViewProperties(LockPatternUtils lockPatternUtils,
            KeyguardUpdateMonitor updateMonitor) {
        mLockPatternUtils = lockPatternUtils;
        mUpdateMonitor = updateMonitor;
    }

    public KeyguardViewBase createKeyguardView(Context context,
            KeyguardViewCallback callback,
            KeyguardUpdateMonitor updateMonitor,
            KeyguardWindowController controller) {
        if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            return new MSimLockPatternKeyguardView(context, callback, updateMonitor,
                    mLockPatternUtils, controller);
        } else {
            return new LockPatternKeyguardView(context, callback, updateMonitor,
                    mLockPatternUtils, controller);
        }
    }

    public boolean isSecure() {
        return mLockPatternUtils.isSecure() || isSimPinSecure();
    }

    private boolean isSimPinSecure() {
        final IccCard.State[] simState;
        boolean isSimPinSecure = false;
        int numPhones = MSimTelephonyManager.getDefault().getPhoneCount();

        simState = new IccCard.State[numPhones];
        for (int i = 0; i < numPhones; i++) {
            simState[i] = mUpdateMonitor.getSimState(i);
            // isPinLocked returns true if SIM is PIN/PUK Locked.
            isSimPinSecure = isSimPinSecure || (simState[i].isPinLocked()
                    || simState[i] == IccCard.State.ABSENT
                    || simState[i] == IccCard.State.PERM_DISABLED);
            if (isSimPinSecure) break;
        }
        return isSimPinSecure;
    }

}
