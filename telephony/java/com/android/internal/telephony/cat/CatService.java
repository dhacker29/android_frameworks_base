/*
 * Copyright (C) 2007 The Android Open Source Project
 * Copyright (c) 2012 Code Aurora Forum. All rights reserved.
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

package com.android.internal.telephony.cat;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemProperties;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.IccCardStatus.CardState;
import com.android.internal.telephony.uicc.IccRefreshResponse;
import com.android.internal.telephony.uicc.UiccController;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

import static  com.android.internal.telephony.cat.CatCmdMessage.SetupEventListConstants.*;

class RilMessage {
    int mId;
    Object mData;
    ResultCode mResCode;

    RilMessage(int msgId, String rawData) {
        mId = msgId;
        mData = rawData;
    }

    RilMessage(RilMessage other) {
        this.mId = other.mId;
        this.mData = other.mData;
        this.mResCode = other.mResCode;
    }
}

/**
 * Class that implements SIM Toolkit Telephony Service. Interacts with the RIL
 * and application.
 *
 * {@hide}
 */
public class CatService extends Handler implements AppInterface {

    // Class members
    private static IccRecords mIccRecords;
    private static UiccCardApplication mUiccApplication;

    // Service members.
    // Protects singleton instance lazy initialization.
    private static final Object sInstanceLock = new Object();
    private HandlerThread mHandlerThread;
    private CommandsInterface mCmdIf;
    private Context mContext;
    private int mSlotId;
    private CatCmdMessage mCurrntCmd = null;
    private CatCmdMessage mMenuCmd = null;
    private IconLoader mIconLoader = null;

    private RilMessageDecoder mMsgDecoder = null;
    private boolean mStkAppInstalled = false;

    private CardState mCardState = CardState.CARDSTATE_ABSENT;

    // Service constants.
    static final int MSG_ID_SESSION_END              = 1;
    static final int MSG_ID_PROACTIVE_COMMAND        = 2;
    static final int MSG_ID_EVENT_NOTIFY             = 3;
    static final int MSG_ID_CALL_SETUP               = 4;
    static final int MSG_ID_REFRESH                  = 5;
    static final int MSG_ID_RESPONSE                 = 6;
    static final int MSG_ID_ALPHA_NOTIFY             = 8;

    static final int MSG_ID_RIL_MSG_DECODED          = 10;

    // Events to signal SIM presence or absent in the device.
    private static final int MSG_ID_ICC_CHANGED      = 20;

    //Events to signal SIM REFRESH notificatations
    private static final int MSG_ID_ICC_REFRESH  = 30;

    private static final int DEV_ID_KEYPAD      = 0x01;
    private static final int DEV_ID_DISPLAY     = 0x02;
    private static final int DEV_ID_EARPIECE    = 0x03;
    private static final int DEV_ID_UICC        = 0x81;
    private static final int DEV_ID_TERMINAL    = 0x82;
    private static final int DEV_ID_NETWORK     = 0x83;

    static final String STK_DEFAULT = "Default Message";

    /* Intentionally private for singleton */
    public CatService(CommandsInterface ci, Context context, int slotId) {
        IccFileHandler fh = null;
        if (ci == null || context == null) {
            throw new NullPointerException(
                    "Service: Input parameters must not be null");
        }
        mCmdIf = ci;
        mContext = context;
        mSlotId = slotId;
        mHandlerThread = new HandlerThread("Cat Telephony service" + slotId);
        mHandlerThread.start();

        // Get the RilMessagesDecoder for decoding the messages.
        mMsgDecoder = RilMessageDecoder.getInstance();

        // Register ril events handling.
        mCmdIf.setOnCatSessionEnd(this, MSG_ID_SESSION_END, null);
        mCmdIf.setOnCatProactiveCmd(this, MSG_ID_PROACTIVE_COMMAND, null);
        mCmdIf.setOnCatEvent(this, MSG_ID_EVENT_NOTIFY, null);
        mCmdIf.setOnCatCallSetUp(this, MSG_ID_CALL_SETUP, null);
        //mCmdIf.setOnSimRefresh(this, MSG_ID_REFRESH, null);
        mCmdIf.setOnCatCcAlphaNotify(this, MSG_ID_ALPHA_NOTIFY, null);

        mCmdIf.registerForIccRefresh(this, MSG_ID_ICC_REFRESH, null);

        // Check if STK application is availalbe
        mStkAppInstalled = isStkAppInstalled();

        CatLog.d(this, "Running CAT service on Slotid: " + mSlotId + 
                ". STK app installed:" + mStkAppInstalled);
    }

    public void dispose() {

        // Clean up stk icon if dispose is called
        broadcastCardStateAndIccRefreshResp(CardState.CARDSTATE_ABSENT, null);

        mCmdIf.unSetOnCatSessionEnd(this);
        mCmdIf.unSetOnCatProactiveCmd(this);
        mCmdIf.unSetOnCatEvent(this);
        mCmdIf.unSetOnCatCallSetUp(this);
        mCmdIf.unSetOnCatCcAlphaNotify(this);
        mCmdIf.unregisterForIccRefresh(this);
        mHandlerThread.quit();
        mHandlerThread = null;

        this.removeCallbacksAndMessages(null);
    }

    public void update(UiccCardApplication ca, CardState newState) {

        CatLog.d(this,"New Card State = " + newState + " " + "Old Card State = " + mCardState);

        if (mCardState == CardState.CARDSTATE_PRESENT &&
            newState != CardState.CARDSTATE_PRESENT) {
            // Card moved to ABSENT state.
            broadcastCardStateAndIccRefreshResp(newState, null);
        } else if (mCardState != CardState.CARDSTATE_PRESENT &&
            newState == CardState.CARDSTATE_PRESENT) {
            // Card moved to PRESENT STATE.
            mCmdIf.reportStkServiceIsRunning(null);
        }
        mCardState = newState;

        IccRecords ir = null;
        IccFileHandler fh = null;
        mUiccApplication = ca;
        if (mUiccApplication != null) {
            fh = mUiccApplication.getIccFileHandler();
            ir = mUiccApplication.getIccRecords();
        }
        if ((ir != null) && (mIccRecords != ir)) {
            CatLog.d(this, "Reinitialize the Service with IccRecords");
            mIccRecords = ir;
        }

        if (fh != null) {
            CatLog.d(this, "Reinitialize the Service with new IccFilehandler");
            if (mIconLoader != null) {
                mIconLoader.updateIccFileHandler(fh);
            } else {
                mIconLoader = new IconLoader(fh, mSlotId);
            }
        }
    }

    protected void finalize() {
        CatLog.d(this, "Service finalized");
    }

    private void handleRilMsg(RilMessage rilMsg) {
        if (rilMsg == null) {
            return;
        }

        // dispatch messages
        CommandParams cmdParams = null;
        switch (rilMsg.mId) {
        case MSG_ID_EVENT_NOTIFY:
            if (rilMsg.mResCode == ResultCode.OK) {
                cmdParams = (CommandParams) rilMsg.mData;
                if (cmdParams != null) {
                    handleCommand(cmdParams, false);
                }
            }
            break;
        case MSG_ID_PROACTIVE_COMMAND:
            try {
                cmdParams = (CommandParams) rilMsg.mData;
            } catch (ClassCastException e) {
                // for error handling : cast exception
                CatLog.d(this, "Fail to parse proactive command");
                // Don't send Terminal Resp if command detail is not available
                if (mCurrntCmd != null) {
                    sendTerminalResponse(mCurrntCmd.mCmdDet, ResultCode.CMD_DATA_NOT_UNDERSTOOD,
                                     false, 0x00, null);
                }
                break;
            }
            if (cmdParams != null) {
                if (rilMsg.mResCode == ResultCode.OK) {
                    handleCommand(cmdParams, true);
                } else {
                    // for proactive commands that couldn't be decoded
                    // successfully respond with the code generated by the
                    // message decoder.
                    sendTerminalResponse(cmdParams.cmdDet, rilMsg.mResCode,
                            false, 0, null);
                }
            }
            break;
        case MSG_ID_REFRESH:
            cmdParams = (CommandParams) rilMsg.mData;
            if (cmdParams != null) {
                handleCommand(cmdParams, false);
            }
            break;
        case MSG_ID_SESSION_END:
            handleSessionEnd();
            break;
        case MSG_ID_CALL_SETUP:
            // prior event notify command supplied all the information
            // needed for set up call processing.
            break;
        }
    }

    /**  This function validates the events in SETUP_EVENT_LIST which are currently
     *   supported by the Android framework. In case of SETUP_EVENT_LIST has NULL events
     *   or no events, all the events need to be reset.
     */
    private boolean isSupportedSetupEventCommand(CatCmdMessage cmdMsg) {
        boolean flag = true;
        int eventval;

        for (int i = 0; i < cmdMsg.getSetEventList().eventList.length ; i++) {
            eventval = cmdMsg.getSetEventList().eventList[i];
            CatLog.d(this,"Event: "+eventval);
            switch (eventval) {
                /* Currently android is supporting only the below events in SetupEventList
                 * Browser Termination,
                 * Idle Screen Available and
                 * Language Selection.  */
                case IDLE_SCREEN_AVAILABLE_EVENT:
                case LANGUAGE_SELECTION_EVENT:
                    break;
                default:
                    flag = false;
            }
        }
        return flag;
    }

    /**
     * Handles RIL_UNSOL_STK_EVENT_NOTIFY or RIL_UNSOL_STK_PROACTIVE_COMMAND command
     * from RIL.
     * Sends valid proactive command data to the application using intents.
     * RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE will be send back if the command is
     * from RIL_UNSOL_STK_PROACTIVE_COMMAND.
     */
    private void handleCommand(CommandParams cmdParams, boolean isProactiveCmd) {
        CatLog.d(this, cmdParams.getCommandType().name());

        CharSequence message;
        ResultCode resultCode;
        CatCmdMessage cmdMsg = new CatCmdMessage(cmdParams);
        switch (cmdParams.getCommandType()) {
            case SET_UP_MENU:
                if (removeMenu(cmdMsg.getMenu())) {
                    mMenuCmd = null;
                } else {
                    mMenuCmd = cmdMsg;
                }
                resultCode = cmdParams.loadIconFailed ? ResultCode.PRFRMD_ICON_NOT_DISPLAYED : ResultCode.OK;
                sendTerminalResponse(cmdParams.cmdDet,resultCode, false, 0,null);
                break;
            case DISPLAY_TEXT:
                // when application is not required to respond, send an immediate response.
                if (!cmdMsg.geTextMessage().responseNeeded) {
                    resultCode = cmdParams.loadIconFailed ? ResultCode.PRFRMD_ICON_NOT_DISPLAYED : ResultCode.OK;
                    sendTerminalResponse(cmdParams.cmdDet,resultCode, false, 0,null);
                }
                break;
            case REFRESH:
                // ME side only handles refresh commands which meant to remove IDLE
                // MODE TEXT.
                cmdParams.cmdDet.typeOfCommand = CommandType.SET_UP_IDLE_MODE_TEXT.value();
                break;
            case SET_UP_IDLE_MODE_TEXT:
                resultCode = cmdParams.loadIconFailed ? ResultCode.PRFRMD_ICON_NOT_DISPLAYED : ResultCode.OK;
                sendTerminalResponse(cmdParams.cmdDet,resultCode, false, 0,null);
                break;
            case SET_UP_EVENT_LIST:
                if (isSupportedSetupEventCommand(cmdMsg)) {
                    sendTerminalResponse(cmdParams.cmdDet, ResultCode.OK, false, 0, null);
                } else {
                    sendTerminalResponse(cmdParams.cmdDet, ResultCode.BEYOND_TERMINAL_CAPABILITY,
                            false, 0, null);
                }
                break;
            case PROVIDE_LOCAL_INFORMATION:
                ResponseData resp;
                switch (cmdParams.cmdDet.commandQualifier) {
                    case CommandParamsFactory.DTTZ_SETTING:
                        resp = new DTTZResponseData(null);
                        sendTerminalResponse(cmdParams.cmdDet, ResultCode.OK, false, 0, resp);
                        break;
                    case CommandParamsFactory.LANGUAGE_SETTING:
                        resp = new LanguageResponseData(Locale.getDefault().getLanguage());
                        sendTerminalResponse(cmdParams.cmdDet, ResultCode.OK, false, 0, resp);
                        break;
                    default:
                        sendTerminalResponse(cmdParams.cmdDet, ResultCode.OK, false, 0, null);
                }
                // No need to start STK app here.
                return;
            case LAUNCH_BROWSER:
                if ((((LaunchBrowserParams) cmdParams).confirmMsg.text != null)
                        && (((LaunchBrowserParams) cmdParams).confirmMsg.text.equals(STK_DEFAULT))) {
                    message = mContext.getText(com.android.internal.R.string.launchBrowserDefault);
                    ((LaunchBrowserParams) cmdParams).confirmMsg.text = message.toString();
                }
                break;
            case SELECT_ITEM:
            case GET_INPUT:
            case GET_INKEY:
                break;
            case SEND_DTMF:
            case SEND_SMS:
            case SEND_SS:
            case SEND_USSD:
                if ((((DisplayTextParams)cmdParams).textMsg.text != null)
                        && (((DisplayTextParams)cmdParams).textMsg.text.equals(STK_DEFAULT))) {
                    message = mContext.getText(com.android.internal.R.string.sending);
                    ((DisplayTextParams)cmdParams).textMsg.text = message.toString();
                }
                break;
            case PLAY_TONE:
                break;
            case SET_UP_CALL:
                if ((((CallSetupParams) cmdParams).confirmMsg.text != null)
                        && (((CallSetupParams) cmdParams).confirmMsg.text.equals(STK_DEFAULT))) {
                    message = mContext.getText(com.android.internal.R.string.SetupCallDefault);
                    ((CallSetupParams) cmdParams).confirmMsg.text = message.toString();
                }
                break;
            case OPEN_CHANNEL:
            case CLOSE_CHANNEL:
            case RECEIVE_DATA:
            case SEND_DATA:
                BIPClientParams cmd = (BIPClientParams) cmdParams;
                /*
                 * If the text mesg is null, need to send the response
                 * back to the card in the following scenarios
                 * - It has alpha ID tag with no Text Msg (or)
                 * - If alphaUsrCnf is not set. In the above cases
                 *   there should be no UI indication given to the user.
                 */
                boolean alphaUsrCnf = SystemProperties.getBoolean(
                         TelephonyProperties.PROPERTY_ALPHA_USRCNF, false);
                CatLog.d(this, "alphaUsrCnf: " + alphaUsrCnf + ", bHasAlphaId: " + cmd.bHasAlphaId);

                if (( cmd.textMsg.text == null) && ( cmd.bHasAlphaId || !alphaUsrCnf)) {
                    CatLog.d(this, "cmd " + cmdParams.getCommandType() + " with null alpha id");
                    // If alpha length is zero, we just respond with OK.
                    if (isProactiveCmd) {
                        sendTerminalResponse(cmdParams.cmdDet, ResultCode.OK, false, 0, null);
                    } else if (cmdParams.getCommandType() == CommandType.OPEN_CHANNEL) {
                        mCmdIf.handleCallSetupRequestFromSim(true, null);
                    }
                    return;
                }
                // Respond with permanent failure to avoid retry if STK app is not present.
                if (!mStkAppInstalled) {
                    CatLog.d(this, "No STK application found.");
                    if (isProactiveCmd) {
                        sendTerminalResponse(cmdParams.cmdDet,
                                             ResultCode.BEYOND_TERMINAL_CAPABILITY,
                                             false, 0, null);
                        return;
                    }
                }
                /*
                 * CLOSE_CHANNEL, RECEIVE_DATA and SEND_DATA can be delivered by
                 * either PROACTIVE_COMMAND or EVENT_NOTIFY.
                 * If PROACTIVE_COMMAND is used for those commands, send terminal
                 * response here.
                 */
                if (isProactiveCmd &&
                    ((cmdParams.getCommandType() == CommandType.CLOSE_CHANNEL) ||
                     (cmdParams.getCommandType() == CommandType.RECEIVE_DATA) ||
                     (cmdParams.getCommandType() == CommandType.SEND_DATA))) {
                    sendTerminalResponse(cmdParams.cmdDet, ResultCode.OK, false, 0, null);
                }
                break;
            default:
                CatLog.d(this, "Unsupported command");
                return;
        }
        mCurrntCmd = cmdMsg;
        Intent intent = new Intent(AppInterface.CAT_CMD_ACTION);
        intent.putExtra("STK CMD", cmdMsg);
        intent.putExtra("SLOT_ID", mSlotId);
        CatLog.d(this, "Sending CmdMsg: "+cmdMsg+ " on slotid:"+ mSlotId);
        mContext.sendBroadcast(intent);
    }

    /**
     * Handles RIL_UNSOL_STK_SESSION_END unsolicited command from RIL.
     *
     */
    private void handleSessionEnd() {
        CatLog.d(this, "SESSION END on "+ mSlotId);

        mCurrntCmd = mMenuCmd;
        Intent intent = new Intent(AppInterface.CAT_SESSION_END_ACTION);
        intent.putExtra("SLOT_ID", mSlotId);
        mContext.sendBroadcast(intent);
    }

    private void sendTerminalResponse(CommandDetails cmdDet,
            ResultCode resultCode, boolean includeAdditionalInfo,
            int additionalInfo, ResponseData resp) {

        if (cmdDet == null) {
            return;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        Input cmdInput = null;
        if (mCurrntCmd != null) {
            cmdInput = mCurrntCmd.geInput();
        }

        // command details
        int tag = ComprehensionTlvTag.COMMAND_DETAILS.value();
        if (cmdDet.compRequired) {
            tag |= 0x80;
        }
        buf.write(tag);
        buf.write(0x03); // length
        buf.write(cmdDet.commandNumber);
        buf.write(cmdDet.typeOfCommand);
        buf.write(cmdDet.commandQualifier);

        // device identities
        // According to TS102.223/TS31.111 section 6.8 Structure of
        // TERMINAL RESPONSE, "For all SIMPLE-TLV objects with Min=N,
        // the ME should set the CR(comprehension required) flag to
        // comprehension not required.(CR=0)"
        // Since DEVICE_IDENTITIES and DURATION TLVs have Min=N,
        // the CR flag is not set.
        tag = ComprehensionTlvTag.DEVICE_IDENTITIES.value();
        buf.write(tag);
        buf.write(0x02); // length
        buf.write(DEV_ID_TERMINAL); // source device id
        buf.write(DEV_ID_UICC); // destination device id

        // result
        tag = ComprehensionTlvTag.RESULT.value();
        if (cmdDet.compRequired) {
            tag |= 0x80;
        }
        buf.write(tag);
        int length = includeAdditionalInfo ? 2 : 1;
        buf.write(length);
        buf.write(resultCode.value());

        // additional info
        if (includeAdditionalInfo) {
            buf.write(additionalInfo);
        }

        // Fill optional data for each corresponding command
        if (resp != null) {
            resp.format(buf);
        } else {
            encodeOptionalTags(cmdDet, resultCode, cmdInput, buf);
        }

        byte[] rawData = buf.toByteArray();
        String hexString = IccUtils.bytesToHexString(rawData);
        if (false) {
            CatLog.d(this, "TERMINAL RESPONSE: " + hexString);
        }

        mCmdIf.sendTerminalResponse(hexString, null);
    }

    private void encodeOptionalTags(CommandDetails cmdDet,
            ResultCode resultCode, Input cmdInput, ByteArrayOutputStream buf) {
        CommandType cmdType = AppInterface.CommandType.fromInt(cmdDet.typeOfCommand);
        if (cmdType != null) {
            switch (cmdType) {
                case GET_INKEY:
                    // ETSI TS 102 384,27.22.4.2.8.4.2.
                    // If it is a response for GET_INKEY command and the response timeout
                    // occured, then add DURATION TLV for variable timeout case.
                    if ((resultCode.value() == ResultCode.NO_RESPONSE_FROM_USER.value()) &&
                        (cmdInput != null) && (cmdInput.duration != null)) {
                        getInKeyResponse(buf, cmdInput);
                    }
                    break;
                case PROVIDE_LOCAL_INFORMATION:
                    if ((cmdDet.commandQualifier == CommandParamsFactory.LANGUAGE_SETTING) &&
                        (resultCode.value() == ResultCode.OK.value())) {
                        getPliResponse(buf);
                    }
                    break;
                default:
                    CatLog.d(this, "encodeOptionalTags() Unsupported Cmd details=" + cmdDet);
                    break;
            }
        } else {
            CatLog.d(this, "encodeOptionalTags() bad Cmd details=" + cmdDet);
        }
    }

    private void getInKeyResponse(ByteArrayOutputStream buf, Input cmdInput) {
        int tag = ComprehensionTlvTag.DURATION.value();

        buf.write(tag);
        buf.write(0x02); // length
        buf.write(cmdInput.duration.timeUnit.SECOND.value()); // Time (Unit,Seconds)
        buf.write(cmdInput.duration.timeInterval); // Time Duration
    }

    private void getPliResponse(ByteArrayOutputStream buf) {

        // Locale Language Setting
        String lang = SystemProperties.get("persist.sys.language");

        if (lang != null) {
            // tag
            int tag = ComprehensionTlvTag.LANGUAGE.value();
            buf.write(tag);
            ResponseData.writeLength(buf, lang.length());
            buf.write(lang.getBytes(), 0, lang.length());
        }
    }

    private void sendMenuSelection(int menuId, boolean helpRequired) {

        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // tag
        int tag = BerTlv.BER_MENU_SELECTION_TAG;
        buf.write(tag);

        // length
        buf.write(0x00); // place holder

        // device identities
        tag = 0x80 | ComprehensionTlvTag.DEVICE_IDENTITIES.value();
        buf.write(tag);
        buf.write(0x02); // length
        buf.write(DEV_ID_KEYPAD); // source device id
        buf.write(DEV_ID_UICC); // destination device id

        // item identifier
        tag = 0x80 | ComprehensionTlvTag.ITEM_ID.value();
        buf.write(tag);
        buf.write(0x01); // length
        buf.write(menuId); // menu identifier chosen

        // help request
        if (helpRequired) {
            tag = ComprehensionTlvTag.HELP_REQUEST.value();
            buf.write(tag);
            buf.write(0x00); // length
        }

        byte[] rawData = buf.toByteArray();

        // write real length
        int len = rawData.length - 2; // minus (tag + length)
        rawData[1] = (byte) len;

        String hexString = IccUtils.bytesToHexString(rawData);

        mCmdIf.sendEnvelope(hexString, null);
    }

    private void eventDownload(int event, int sourceId, int destinationId,
            byte[] additionalInfo, boolean oneShot) {

        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // tag
        int tag = BerTlv.BER_EVENT_DOWNLOAD_TAG;
        buf.write(tag);

        // length
        buf.write(0x00); // place holder, assume length < 128.

        // event list
        tag = 0x80 | ComprehensionTlvTag.EVENT_LIST.value();
        buf.write(tag);
        buf.write(0x01); // length
        buf.write(event); // event value

        // device identities
        tag = 0x80 | ComprehensionTlvTag.DEVICE_IDENTITIES.value();
        buf.write(tag);
        buf.write(0x02); // length
        buf.write(sourceId); // source device id
        buf.write(destinationId); // destination device id

        /*
         * Check for type of event download to be sent to UICC - Browser
         * termination,Idle screen available, User activity, Language selection
         * etc as mentioned under ETSI TS 102 223 section 7.5
         */

        /*
         * Currently the below events are supported:
         * Browser Termination,
         * Idle Screen Available and
         * Language Selection Event.
         * Other event download commands should be encoded similar way
         */
        /* TODO: eventDownload should be extended for other Envelope Commands */
        switch (event) {
            case IDLE_SCREEN_AVAILABLE_EVENT:
                CatLog.d(this, " Sending Idle Screen Available event download to ICC");
                break;
            case LANGUAGE_SELECTION_EVENT:
                CatLog.d(this, " Sending Language Selection event download to ICC");
                tag = 0x80 | ComprehensionTlvTag.LANGUAGE.value();
                buf.write(tag);
                // Language length should be 2 byte
                buf.write(0x02);
                break;
            default:
                break;
        }

        // additional information
        if (additionalInfo != null) {
            for (byte b : additionalInfo) {
                buf.write(b);
            }
        }

        byte[] rawData = buf.toByteArray();

        // write real length
        int len = rawData.length - 2; // minus (tag + length)
        rawData[1] = (byte) len;

        String hexString = IccUtils.bytesToHexString(rawData);

        CatLog.d(this, "ENVELOPE COMMAND: " + hexString);

        mCmdIf.sendEnvelope(hexString, null);
    }

    @Override
    public void handleMessage(Message msg) {

        CatLog.d(this, msg.what + "arrived on slotid: "+ mSlotId);
        switch (msg.what) {
        case MSG_ID_SESSION_END:
        case MSG_ID_PROACTIVE_COMMAND:
        case MSG_ID_EVENT_NOTIFY:
        case MSG_ID_REFRESH:
            CatLog.d(this, "ril message arrived");
            String data = null;
            if (msg.obj != null) {
                AsyncResult ar = (AsyncResult) msg.obj;
                if (ar != null && ar.result != null) {
                    try {
                        data = (String) ar.result;
                    } catch (ClassCastException e) {
                        CatLog.d(this,"Exception caught for proactive cmd");
                        break;
                    }
                }
            }
            mMsgDecoder.sendStartDecodingMessageParams( this, mIconLoader,
                    new RilMessage(msg.what, data));
            break;
        case MSG_ID_CALL_SETUP:
            mMsgDecoder.sendStartDecodingMessageParams( this, mIconLoader,
                    new RilMessage(msg.what, null));
            break;
        case MSG_ID_RIL_MSG_DECODED:
            handleRilMsg((RilMessage) msg.obj);
            break;
        case MSG_ID_RESPONSE:
            handleCmdResponse((CatResponseMessage) msg.obj);
            break;
        case MSG_ID_ICC_REFRESH:
            if (msg.obj != null) {
                AsyncResult ar = (AsyncResult) msg.obj;
                if (ar != null && ar.result != null) {
                    broadcastCardStateAndIccRefreshResp(CardState.
                            CARDSTATE_PRESENT, (IccRefreshResponse) ar.result);
                } else {
                    CatLog.d(this,"Icc REFRESH with exception: " + ar.exception);
                }
            } else {
                CatLog.d(this, "IccRefresh Message is null");
            }
            break;
        case MSG_ID_ALPHA_NOTIFY:
            CatLog.d(this, "Received STK CC Alpha message from card");
            if (msg.obj != null) {
                AsyncResult ar = (AsyncResult) msg.obj;
                if (ar != null && ar.result != null) {
                    String alphaString = (String)ar.result;
                    CatLog.d(this, "Broadcasting STK Alpha message from card: " + alphaString);
                    Intent intent = new Intent(AppInterface.CAT_ALPHA_NOTIFY_ACTION);
                    intent.putExtra(AppInterface.ALPHA_STRING, alphaString);
                    intent.putExtra("SLOT_ID", mSlotId);
                    mContext.sendBroadcast(intent);
                } else {
                    CatLog.d(this, "STK Alpha message: ar.result is null");
                }
            } else {
                CatLog.d(this, "STK Alpha message: msg.obj is null");
            }
            break;
        default:
            throw new AssertionError("Unrecognized CAT command: " + msg.what);
        }
    }

    /**
     ** This function sends a CARD status (ABSENT, PRESENT, REFRESH) to STK_APP.
     ** This is triggered during ICC_REFRESH or CARD STATE changes. In case
     ** REFRESH, additional information is sent in 'refresh_result'
     **
     **/
    private void  broadcastCardStateAndIccRefreshResp(CardState cardState,
            IccRefreshResponse IccRefreshState) {
        Intent intent = new Intent(AppInterface.CAT_ICC_STATUS_CHANGE);
        boolean cardStatus = true;

        if (IccRefreshState != null) {
            //This case is when MSG_ID_ICC_REFRESH is received.
            intent.putExtra(AppInterface.REFRESH_RESULT,IccRefreshState.refreshResult);
            CatLog.d(this, "Sending IccResult with Result: "
                    + IccRefreshState.refreshResult);
        }

        if(cardState != CardState.CARDSTATE_PRESENT)
            cardStatus = false;

        // This sends an intent with CARD_ABSENT (0 - false) /CARD_PRESENT (1 - true).
        // In case of CARD_ABSENT, StkAppService
        // will clean up the Idle mode Text and Stk toolkit icon
        intent.putExtra(AppInterface.CARD_STATUS, cardStatus);
        intent.putExtra("SLOT_ID", mSlotId);
        CatLog.d(this, "Sending Card Status: "
                + cardState + " " + "cardStatus: " + cardStatus);

        mContext.sendBroadcast(intent);
    }

    public synchronized void onCmdResponse(CatResponseMessage resMsg) {
        if (resMsg == null) {
            return;
        }
        // queue a response message.
        Message msg = this.obtainMessage(MSG_ID_RESPONSE, resMsg);
        msg.sendToTarget();
    }

    private boolean validateResponse(CatResponseMessage resMsg) {
        boolean validResponse = false;
        if ((resMsg.cmdDet.typeOfCommand == CommandType.SET_UP_EVENT_LIST.value())
                || (resMsg.cmdDet.typeOfCommand == CommandType.SET_UP_MENU.value())) {
            CatLog.d(this, "CmdType: " + resMsg.cmdDet.typeOfCommand);
            validResponse = true;
        } else if (mCurrntCmd != null) {
            validResponse = resMsg.cmdDet.compareTo(mCurrntCmd.mCmdDet);
            CatLog.d(this, "isResponse for last valid cmd: " + validResponse);
        }
        return validResponse;
    }

    private boolean removeMenu(Menu menu) {
        try {
            if (menu.items.size() == 1 && menu.items.get(0) == null) {
                return true;
            }
        } catch (NullPointerException e) {
            CatLog.d(this, "Unable to get Menu's items size");
            return true;
        }
        return false;
    }

    private void handleCmdResponse(CatResponseMessage resMsg) {
        // Make sure the response details match the last valid command. An invalid
        // response is a one that doesn't have a corresponding proactive command
        // and sending it can "confuse" the baseband/ril.
        // One reason for out of order responses can be UI glitches. For example,
        // if the application launch an activity, and that activity is stored
        // by the framework inside the history stack. That activity will be
        // available for relaunch using the latest application dialog
        // (long press on the home button). Relaunching that activity can send
        // the same command's result again to the StkService and can cause it to
        // get out of sync with the SIM. This can happen in case of
        // non-interactive type Setup Event List and SETUP_MENU proactive commands.
        // Stk framework would have already sent Terminal Response to Setup Event
        // List and SETUP_MENU proactive commands. After sometime Stk app will send
        // Envelope Command/Event Download. In which case, the response details doesn't
        // match with last valid command (which are not related).
        // However, we should allow Stk framework to send the message to ICC.
        if (!validateResponse(resMsg)) {
            return;
        }
        ResponseData resp = null;
        boolean helpRequired = false;
        CommandDetails cmdDet = resMsg.getCmdDetails();
        AppInterface.CommandType type = AppInterface.CommandType.fromInt(cmdDet.typeOfCommand);

        switch (resMsg.resCode) {
        case HELP_INFO_REQUIRED:
            helpRequired = true;
            // fall through
        case OK:
        case PRFRMD_WITH_PARTIAL_COMPREHENSION:
        case PRFRMD_WITH_MISSING_INFO:
        case PRFRMD_WITH_ADDITIONAL_EFS_READ:
        case PRFRMD_ICON_NOT_DISPLAYED:
        case PRFRMD_MODIFIED_BY_NAA:
        case PRFRMD_LIMITED_SERVICE:
        case PRFRMD_WITH_MODIFICATION:
        case PRFRMD_NAA_NOT_ACTIVE:
        case PRFRMD_TONE_NOT_PLAYED:
        case LAUNCH_BROWSER_ERROR:
            switch (type) {
            case SET_UP_MENU:
                helpRequired = resMsg.resCode == ResultCode.HELP_INFO_REQUIRED;
                sendMenuSelection(resMsg.usersMenuSelection, helpRequired);
                return;
            case SELECT_ITEM:
                resp = new SelectItemResponseData(resMsg.usersMenuSelection);
                break;
            case GET_INPUT:
            case GET_INKEY:
                Input input = mCurrntCmd.geInput();
                if (!input.yesNo) {
                    // when help is requested there is no need to send the text
                    // string object.
                    if (!helpRequired) {
                        resp = new GetInkeyInputResponseData(resMsg.usersInput,
                                input.ucs2, input.packed);
                    }
                } else {
                    resp = new GetInkeyInputResponseData(
                            resMsg.usersYesNoSelection);
                }
                break;
            case DISPLAY_TEXT:
            case LAUNCH_BROWSER:
                break;
            case OPEN_CHANNEL:
            case SET_UP_CALL:
                mCmdIf.handleCallSetupRequestFromSim(resMsg.usersConfirm, null);
                // No need to send terminal response for SET UP CALL. The user's
                // confirmation result is send back using a dedicated ril message
                // invoked by the CommandInterface call above.
                mCurrntCmd = null;
                return;
            case SET_UP_EVENT_LIST:
                if (IDLE_SCREEN_AVAILABLE_EVENT == resMsg.eventValue) {
                    eventDownload(resMsg.eventValue, DEV_ID_DISPLAY, DEV_ID_UICC,
                            resMsg.addedInfo, false);
                } else {
                    eventDownload(resMsg.eventValue, DEV_ID_TERMINAL, DEV_ID_UICC,
                            resMsg.addedInfo, false);
                }
                // No need to send the terminal response after event download.
                return;
            }
            break;
        case BACKWARD_MOVE_BY_USER:
        case USER_NOT_ACCEPT:
            // if the user dismissed the alert dialog for a
            // setup call/open channel, consider that as the user
            // rejecting the call. Use dedicated API for this, rather than
            // sending a terminal response.
            if (type == CommandType.SET_UP_CALL || type == CommandType.OPEN_CHANNEL) {
                mCmdIf.handleCallSetupRequestFromSim(false, null);
                mCurrntCmd = null;
                return;
            } else {
                resp = null;
            }
            break;
        case TERMINAL_CRNTLY_UNABLE_TO_PROCESS:
            //For screenbusy case there will be addtional information in the terminal
            //response. And the value of the additional information byte is 0x01.
            resMsg.includeAdditionalInfo = true;
            resMsg.additionalInfo = 0x01;
        case NO_RESPONSE_FROM_USER:
        case UICC_SESSION_TERM_BY_USER:
            resp = null;
            break;
        default:
            return;
        }
        sendTerminalResponse(cmdDet, resMsg.resCode, resMsg.includeAdditionalInfo,
                resMsg.additionalInfo, resp);
        mCurrntCmd = null;
    }

    private boolean isStkAppInstalled() {
        Intent intent = new Intent(AppInterface.CAT_CMD_ACTION);
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> broadcastReceivers =
                            pm.queryBroadcastReceivers(intent, PackageManager.GET_META_DATA);
        int numReceiver = broadcastReceivers == null ? 0 : broadcastReceivers.size();

        return (numReceiver > 0);
    }
}
