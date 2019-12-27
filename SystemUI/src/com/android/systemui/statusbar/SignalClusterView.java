/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.annotation.DrawableRes;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Animatable;
import android.telephony.SubscriptionInfo;
import android.os.SystemProperties;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.LinearLayout;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.SignalDrawable;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.policy.IconLogger;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkControllerImpl;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import com.mediatek.systemui.ext.ISystemUIStatusBarExt;
import com.mediatek.systemui.ext.OpSystemUICustomizationFactoryBase;
import com.mediatek.systemui.statusbar.util.FeatureOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import android.net.Uri;
import android.provider.Settings;
import android.provider.Settings.System;
import android.os.Handler;
import android.database.ContentObserver;

// Intimately tied to the design of res/layout/signal_cluster_view.xml
public class SignalClusterView extends LinearLayout implements NetworkControllerImpl.SignalCallback,
        SecurityController.SecurityControllerCallback, Tunable,
        DarkReceiver {

    static final String TAG = "SignalClusterView";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG) || FeatureOptions.LOG_ENABLE;

    private static final String SLOT_AIRPLANE = "airplane";
    private static final String SLOT_MOBILE = "mobile";
    private static final String SLOT_WIFI = "wifi";
    private static final String SLOT_ETHERNET = "ethernet";
    private static final String SLOT_VPN = "vpn";

    private final NetworkController mNetworkController;
    private final SecurityController mSecurityController;

    private boolean mNoSimsVisible = false;
    private boolean mVpnVisible = false;
    private boolean mSimDetected;
    private int mVpnIconId = 0;
    private int mLastVpnIconId = -1;
    private boolean mEthernetVisible = false;
    private int mEthernetIconId = 0;
    private int mLastEthernetIconId = -1;
    private boolean mWifiVisible = false;
    private int mWifiStrengthId = 0;
    private int mLastWifiStrengthId = -1;
    private boolean mWifiIn;
    private boolean mWifiOut;
    private int mLastWifiActivityId = -1;
    private boolean mIsAirplaneMode = false;
    private int mAirplaneIconId = 0;
    private int mLastAirplaneIconId = -1;
    private String mAirplaneContentDescription;
    private String mWifiDescription;
    private String mEthernetDescription;
    private ArrayList<PhoneState> mPhoneStates = new ArrayList<PhoneState>();
    private int mIconTint = Color.WHITE;
    private float mDarkIntensity;
    private final Rect mTintArea = new Rect();

    ViewGroup mEthernetGroup, mWifiGroup;
    View mNoSimsCombo;
    ImageView mVpn, mEthernet, mWifi, mAirplane, mNoSims, mEthernetDark, mWifiDark, mNoSimsDark;
    ImageView mWifiActivityIn;
    ImageView mWifiActivityOut;
    View mWifiAirplaneSpacer;
    View mWifiSignalSpacer;
    LinearLayout mMobileSignalGroup;

    private final int mMobileSignalGroupEndPadding;
    private final int mMobileDataIconStartPadding;
    private final int mWideTypeIconStartPadding;
    private final int mSecondaryTelephonyPadding;
    private final int mEndPadding;
    private final int mEndPaddingNothingVisible;
    private final float mIconScaleFactor;

    private boolean mBlockAirplane;
    private boolean mBlockMobile;
    private boolean mBlockWifi;
    private boolean mBlockEthernet;
    private boolean mActivityEnabled;
    private boolean mForceBlockWifi;

    /// M: Add for Plugin feature @ {
    private ISystemUIStatusBarExt mStatusBarExt;

    public static final String IS_ETHERNET_OPEN = Settings.IS_ETHERNET_OPEN;
    //private final EthernetClosedObserver mClosedObserver = new EthernetClosedObserver();
    /// @ }

    /// M: for vowifi
    boolean mIsWfcEnable;

    private final IconLogger mIconLogger = Dependency.get(IconLogger.class);

    public SignalClusterView(Context context) {
        this(context, null);
    }

    public SignalClusterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SignalClusterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        Resources res = getResources();
        mMobileSignalGroupEndPadding =
                res.getDimensionPixelSize(R.dimen.mobile_signal_group_end_padding);
        mMobileDataIconStartPadding =
                res.getDimensionPixelSize(R.dimen.mobile_data_icon_start_padding);
        mWideTypeIconStartPadding = res.getDimensionPixelSize(R.dimen.wide_type_icon_start_padding);
        mSecondaryTelephonyPadding = res.getDimensionPixelSize(R.dimen.secondary_telephony_padding);
        mEndPadding = res.getDimensionPixelSize(R.dimen.signal_cluster_battery_padding);
        mEndPaddingNothingVisible = res.getDimensionPixelSize(
                R.dimen.no_signal_cluster_battery_padding);

        TypedValue typedValue = new TypedValue();
        res.getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        mIconScaleFactor = typedValue.getFloat();
        mNetworkController = Dependency.get(NetworkController.class);
        mSecurityController = Dependency.get(SecurityController.class);
        updateActivityEnabled();

        /// M: Add for Plugin feature @ {
        mStatusBarExt = OpSystemUICustomizationFactoryBase.getOpFactory(context)
                                     .makeSystemUIStatusBar(context);
        /// @ }
        mIsWfcEnable = SystemProperties.get("persist.mtk_wfc_support").equals("1");
    }

    public void setForceBlockWifi() {
        mForceBlockWifi = true;
        mBlockWifi = true;
        if (isAttachedToWindow()) {
            // Re-register to get new callbacks.
            mNetworkController.removeCallback(this);
            mNetworkController.addCallback(this);
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!StatusBarIconController.ICON_BLACKLIST.equals(key)) {
            return;
        }
        ArraySet<String> blockList = StatusBarIconController.getIconBlacklist(newValue);
        boolean blockAirplane = blockList.contains(SLOT_AIRPLANE);
        boolean blockMobile = blockList.contains(SLOT_MOBILE);
        boolean blockWifi = blockList.contains(SLOT_WIFI);
        boolean blockEthernet = blockList.contains(SLOT_ETHERNET);

        if (blockAirplane != mBlockAirplane || blockMobile != mBlockMobile
                || blockEthernet != mBlockEthernet || blockWifi != mBlockWifi) {
            mBlockAirplane = blockAirplane;
            mBlockMobile = blockMobile;
            mBlockEthernet = blockEthernet;
            mBlockWifi = blockWifi || mForceBlockWifi;
            // Re-register to get new callbacks.
            mNetworkController.removeCallback(this);
            mNetworkController.addCallback(this);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mVpn            = findViewById(R.id.vpn);
        mEthernetGroup  = findViewById(R.id.ethernet_combo);
        mEthernet       = findViewById(R.id.ethernet);
        mEthernetDark   = findViewById(R.id.ethernet_dark);
        mWifiGroup      = findViewById(R.id.wifi_combo);
        mWifi           = findViewById(R.id.wifi_signal);
        mWifiDark       = findViewById(R.id.wifi_signal_dark);
        mWifiActivityIn = findViewById(R.id.wifi_in);
        mWifiActivityOut= findViewById(R.id.wifi_out);
        mAirplane       = findViewById(R.id.airplane);
        mNoSims         = findViewById(R.id.no_sims);
        mNoSimsDark     = findViewById(R.id.no_sims_dark);
        mNoSimsCombo    =             findViewById(R.id.no_sims_combo);
        mWifiAirplaneSpacer =         findViewById(R.id.wifi_airplane_spacer);
        mWifiSignalSpacer =           findViewById(R.id.wifi_signal_spacer);
        mMobileSignalGroup =          findViewById(R.id.mobile_signal_group);

        maybeScaleVpnAndNoSimsIcons();
    }

    /**
     * Extracts the icon off of the VPN and no sims views and maybe scale them by
     * {@link #mIconScaleFactor}. Note that the other icons are not scaled here because they are
     * dynamic. As such, they need to be scaled each time the icon changes in {@link #apply()}.
     */
    private void maybeScaleVpnAndNoSimsIcons() {
        if (mIconScaleFactor == 1.f) {
            return;
        }

        mVpn.setImageDrawable(new ScalingDrawableWrapper(mVpn.getDrawable(), mIconScaleFactor));

        mNoSims.setImageDrawable(
                new ScalingDrawableWrapper(mNoSims.getDrawable(), mIconScaleFactor));
        mNoSimsDark.setImageDrawable(
                new ScalingDrawableWrapper(mNoSimsDark.getDrawable(), mIconScaleFactor));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mVpnVisible = mSecurityController.isVpnEnabled();
        mVpnIconId = currentVpnIconId(mSecurityController.isVpnBranded());

        if (DEBUG) {
            Log.d(TAG, "onAttachedToWindow, mPhoneStates = " + mPhoneStates);
        }
        for (PhoneState state : mPhoneStates) {
            if (state.mMobileGroup.getParent() == null) {
                mMobileSignalGroup.addView(state.mMobileGroup);
            }
        }

        int endPadding = mMobileSignalGroup.getChildCount() > 0 ? mMobileSignalGroupEndPadding : 0;
        mMobileSignalGroup.setPaddingRelative(0, 0, endPadding, 0);

        Dependency.get(TunerService.class).addTunable(this, StatusBarIconController.ICON_BLACKLIST);

        /// M: Add for Plugin feature @ {
        mStatusBarExt.setCustomizedNoSimView(mNoSims);
        mStatusBarExt.setCustomizedNoSimView(mNoSimsDark);
        mStatusBarExt.addSignalClusterCustomizedView(mContext, this,
                indexOfChild(findViewById(R.id.mobile_signal_group)));
        /// @ }

        apply();
        applyIconTint();
        if (DEBUG) {
            Log.d(TAG, "onAttachedToWindow, addCallback = " + this);
        }
        mNetworkController.addCallback(this);
        mSecurityController.addCallback(this);

        //mContext.getContentResolver().registerContentObserver(System.getUriFor(IS_ETHERNET_OPEN), false, mClosedObserver);
    }

    @Override
    protected void onDetachedFromWindow() {
        mMobileSignalGroup.removeAllViews();
        Dependency.get(TunerService.class).removeTunable(this);
        mSecurityController.removeCallback(this);
        if (DEBUG) {
            Log.d(TAG, "onDetachedFromWindow, removeCallback = " + this);
        }
        mNetworkController.removeCallback(this);

        //mContext.getContentResolver().unregisterContentObserver(mClosedObserver);

        super.onDetachedFromWindow();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        // Re-run all checks against the tint area for all icons
        applyIconTint();
    }

    // From SecurityController.
    @Override
    public void onStateChanged() {
        post(new Runnable() {
            @Override
            public void run() {
                mVpnVisible = mSecurityController.isVpnEnabled();
                mVpnIconId = currentVpnIconId(mSecurityController.isVpnBranded());
                apply();
            }
        });
    }

    private void updateActivityEnabled() {
        mActivityEnabled = mContext.getResources().getBoolean(R.bool.config_showActivity);
    }

    @Override
    public void setWifiIndicators(boolean enabled, IconState statusIcon, IconState qsIcon,
            boolean activityIn, boolean activityOut, String description, boolean isTransient) {
        mWifiVisible = statusIcon.visible && !mBlockWifi;
        mWifiStrengthId = statusIcon.icon;
        mWifiDescription = statusIcon.contentDescription;
        mWifiIn = activityIn && mActivityEnabled && mWifiVisible;
        mWifiOut = activityOut && mActivityEnabled && mWifiVisible;

        apply();
    }
    ///M: Support[Network Type and volte on StatusBar]. Add more parameter networkType and volte .
    @Override
    public void setMobileDataIndicators(IconState statusIcon, IconState qsIcon, int statusType,
             int networkType, int volteIcon, int qsType, boolean activityIn, boolean activityOut,
             String typeContentDescription, String description, boolean isWide, int subId,
              boolean roaming) {
        PhoneState state = getState(subId);
        if (state == null) {
            return;
        }
        state.mMobileVisible = statusIcon.visible && !mBlockMobile;
        state.mMobileStrengthId = statusIcon.icon;
        state.mMobileTypeId = statusType;
        state.mMobileDescription = statusIcon.contentDescription;
        state.mMobileTypeDescription = typeContentDescription;
        state.mIsMobileTypeIconWide = statusType != 0 && isWide;
        /// M: for big network icon and volte icon.
        state.mNetworkIcon = networkType;
        state.mVolteIcon = volteIcon;
        state.mRoaming = roaming;
        state.mActivityIn = activityIn && mActivityEnabled;
        state.mActivityOut = activityOut && mActivityEnabled;

        /// M: Add for plugin features. @ {
        state.mDataActivityIn = activityIn;
        state.mDataActivityOut = activityOut;
        /// @ }

        //pjz add for operator because description is unless
        state.mOperator = description;
        Log.e("ccz","mMobileStrengthId="+statusIcon.icon);
        apply();
    }

    @Override
    public void setEthernetIndicators(IconState state) {
        mEthernetVisible = state.visible && !mBlockEthernet;
        mEthernetIconId = state.icon;
        mEthernetDescription = state.contentDescription;
        Log.e("cce","mEthernetVisible="+mEthernetVisible + " mEthernetIconId="+mEthernetIconId);

        //20190824 pjz add if check ethstate when eth closed don't show ethicon
        int ethState = Settings.System.getInt(mContext.getContentResolver(), IS_ETHERNET_OPEN, 0);
        Log.e("cce", "SignalClusterView getState()===" + ethState);
        if (mEthernetVisible && ethState == 0){
            Log.e("cce", "SignalClusterView some bug happen...  ");
        }else{
            apply();
        }
    }

    /*private final class EthernetClosedObserver extends ContentObserver {
        public EthernetClosedObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
              super.onChange(selfChange, uri, userId);

              int state = Settings.System.getInt(mContext.getContentResolver(), IS_ETHERNET_OPEN, 0);
              Log.e("cce", "SignalClusterView getState()  " + state);
              if (state == 0) {
                if (mEthernetGroup != null) {
                    int visible = mEthernetGroup.getVisibility();
                    Log.i("cce", "ethernet visible=" + visible + "  View.VISIBLE="+View.VISIBLE +" View.GONE="+View.GONE);
                    if (visible == View.VISIBLE) {
                        mEthernetGroup.setVisibility(View.GONE);
                    }
                }
              }

        }
    }*/

    @Override
    public void setNoSims(boolean show, boolean simDetected) {
        mNoSimsVisible = show && !mBlockMobile;
        mSimDetected = simDetected;
        apply();
    }

    @Override
    public void setSubs(List<SubscriptionInfo> subs) {
        if (DEBUG) {
            Log.d(TAG, "setSubs, this = " + this + ", size = " + subs.size()
                + ", subs = " + subs);
        }
        if (hasCorrectSubs(subs)) {
            if (DEBUG) {
                Log.d(TAG, "setSubs, hasCorrectSubs and return");
            }
            return;
        }
        mPhoneStates.clear();
        if (mMobileSignalGroup != null) {
            mMobileSignalGroup.removeAllViews();
        }
        final int n = subs.size();
        Log.d(TAG, "setSubs-clear subsize:" + subs.size() + "mStes" + mPhoneStates + ":" + this);
        for (int i = 0; i < n; i++) {
            inflatePhoneState(subs.get(i).getSubscriptionId());
        }
        if (isAttachedToWindow()) {
            applyIconTint();
        }
    }

    private boolean hasCorrectSubs(List<SubscriptionInfo> subs) {
        final int N = subs.size();
        Log.d(TAG, "hasCorrectSubs, subsInfo:" + subs + ",--mPhoneStates:" + mPhoneStates);
        if (N != mPhoneStates.size()) {
            return false;
        }
        for (int i = 0; i < N; i++) {
            if (mPhoneStates.get(i).mSubId != subs.get(i).getSubscriptionId()) {
                Log.d(TAG, "subId no same " + i + " " + subs.get(i).getSubscriptionId() + "mSubId:"
                      + mPhoneStates.get(i).mSubId);
                return false;
            }
            ///M: fix 2968114, if sim swap but subId has not changed, need to inflate PhoneState
            /// for op views. @ {
            if (mStatusBarExt.checkIfSlotIdChanged(subs.get(i).getSubscriptionId(),
                            subs.get(i).getSimSlotIndex())) {
                Log.d(TAG, "OP return it");
                return false;
            }
            /// @ }
        }
        return true;
    }

    private PhoneState getState(int subId) {
        Log.d(TAG, "setMDataInds-getState subId:" + subId + "sts" + mPhoneStates + ":" + this);
        for (PhoneState state : mPhoneStates) {
            if (state.mSubId == subId) {
                return state;
            }
        }
        Log.e(TAG, "Unexpected subscription " + subId + ",mSts:" + mPhoneStates + ":" + this);
        return null;
    }

    private PhoneState inflatePhoneState(int subId) {
        PhoneState state = new PhoneState(subId, mContext);
        if (mMobileSignalGroup != null) {
            mMobileSignalGroup.addView(state.mMobileGroup);
        }
        Log.d(TAG, "inflatePhoneState add subId:" + subId + ",state" + state + ":" + this);
        mPhoneStates.add(state);
        return state;
    }

    @Override
    public void setIsAirplaneMode(IconState icon) {
        mIsAirplaneMode = icon.visible && !mBlockAirplane;
        mAirplaneIconId = icon.icon;
        mAirplaneContentDescription = icon.contentDescription;

        apply();
    }

    @Override
    public void setMobileDataEnabled(boolean enabled) {
        // Don't care.
    }

    @Override
    public boolean dispatchPopulateAccessibilityEventInternal(AccessibilityEvent event) {
        // Standard group layout onPopulateAccessibilityEvent() implementations
        // ignore content description, so populate manually
        if (mEthernetVisible && mEthernetGroup != null &&
                mEthernetGroup.getContentDescription() != null)
            event.getText().add(mEthernetGroup.getContentDescription());
        if (mWifiVisible && mWifiGroup != null && mWifiGroup.getContentDescription() != null)
            event.getText().add(mWifiGroup.getContentDescription());
        for (PhoneState state : mPhoneStates) {
            state.populateAccessibilityEvent(event);
        }
        return super.dispatchPopulateAccessibilityEventInternal(event);
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);

        if (mEthernet != null) {
            mEthernet.setImageDrawable(null);
            mEthernetDark.setImageDrawable(null);
            mLastEthernetIconId = -1;
        }

        if (mWifi != null) {
            mWifi.setImageDrawable(null);
            mWifiDark.setImageDrawable(null);
            mLastWifiStrengthId = -1;
        }

        for (PhoneState state : mPhoneStates) {
            if (state.mMobileType != null) {
                state.mMobileType.setImageDrawable(null);
                state.mLastMobileTypeId = -1;
            }
        }

        if (mAirplane != null) {
            mAirplane.setImageDrawable(null);
            mLastAirplaneIconId = -1;
        }

        apply();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    // Run after each indicator change.
    private void apply() {
        if (mWifiGroup == null) return;

        if (mVpnVisible) {
            if (mLastVpnIconId != mVpnIconId) {
                setIconForView(mVpn, mVpnIconId);
                mLastVpnIconId = mVpnIconId;
            }
            mIconLogger.onIconShown(SLOT_VPN);
            mVpn.setVisibility(View.VISIBLE);
        } else {
            mIconLogger.onIconHidden(SLOT_VPN);
            mVpn.setVisibility(View.GONE);
        }
        if (DEBUG) Log.d(TAG, String.format("vpn: %s", mVpnVisible ? "VISIBLE" : "GONE"));

        if (mEthernetVisible) {
            if (mLastEthernetIconId != mEthernetIconId) {
                setIconForView(mEthernet, mEthernetIconId);
                setIconForView(mEthernetDark, mEthernetIconId);
                mLastEthernetIconId = mEthernetIconId;
            }
            mEthernetGroup.setContentDescription(mEthernetDescription);
            mIconLogger.onIconShown(SLOT_ETHERNET);
            mEthernetGroup.setVisibility(View.VISIBLE);
            Log.e("cce", "set ethernet visible");
        } else {
            mIconLogger.onIconHidden(SLOT_ETHERNET);
            mEthernetGroup.setVisibility(View.GONE);
            Log.i("cce", "set ethernet GONE");
        }

        if (DEBUG) Log.d(TAG,
                String.format("ethernet: %s",
                    (mEthernetVisible ? "VISIBLE" : "GONE")));

        if (mWifiVisible) {
            if (mWifiStrengthId != mLastWifiStrengthId) {
                setIconForView(mWifi, mWifiStrengthId);
                setIconForView(mWifiDark, mWifiStrengthId);
                mLastWifiStrengthId = mWifiStrengthId;
            }
            mIconLogger.onIconShown(SLOT_WIFI);
            mWifiGroup.setContentDescription(mWifiDescription);
            mWifiGroup.setVisibility(View.VISIBLE);
        } else {
            mIconLogger.onIconHidden(SLOT_WIFI);
            mWifiGroup.setVisibility(View.GONE);
        }

        if (DEBUG) Log.d(TAG,
                String.format("wifi: %s sig=%d",
                    (mWifiVisible ? "VISIBLE" : "GONE"),
                    mWifiStrengthId));

        mWifiActivityIn.setVisibility(mWifiIn ? View.VISIBLE : View.GONE);
        mWifiActivityOut.setVisibility(mWifiOut ? View.VISIBLE : View.GONE);

        boolean anyMobileVisible = false;
        /// M: Support for [Network Type on Statusbar]
        /// A spacer is set between networktype and WIFI icon @ {
        if (FeatureOptions.MTK_CTA_SET) {
            anyMobileVisible = true;
        }
        /// @ }
        int firstMobileTypeId = 0;
        for (PhoneState state : mPhoneStates) {
            Log.i("ccz","mMobileStrengthId="+state.mMobileStrengthId);
            if (state.apply(anyMobileVisible)) {
                if (!anyMobileVisible) {
                    firstMobileTypeId = state.mMobileTypeId;
                    anyMobileVisible = true;
                }
            }
        }
        if (anyMobileVisible) {
            mIconLogger.onIconShown(SLOT_MOBILE);
        } else {
            mIconLogger.onIconHidden(SLOT_MOBILE);
        }

        if (mIsAirplaneMode) {
            if (mLastAirplaneIconId != mAirplaneIconId) {
                setIconForView(mAirplane, mAirplaneIconId);
                mLastAirplaneIconId = mAirplaneIconId;
            }
            mAirplane.setContentDescription(mAirplaneContentDescription);
            mIconLogger.onIconShown(SLOT_AIRPLANE);
            mAirplane.setVisibility(View.VISIBLE);
        } else {
            mIconLogger.onIconHidden(SLOT_AIRPLANE);
            mAirplane.setVisibility(View.GONE);
        }

        if (mIsAirplaneMode && mWifiVisible) {
            mWifiAirplaneSpacer.setVisibility(View.VISIBLE);
        } else {
            mWifiAirplaneSpacer.setVisibility(View.GONE);
        }

        if (((anyMobileVisible && firstMobileTypeId != 0) || mNoSimsVisible) && mWifiVisible) {
            mWifiSignalSpacer.setVisibility(View.VISIBLE);
        } else {
            mWifiSignalSpacer.setVisibility(View.GONE);
        }

        if (mNoSimsVisible) {
            mIconLogger.onIconShown(SLOT_MOBILE);
            mNoSimsCombo.setVisibility(View.VISIBLE);
            if (!Objects.equals(mSimDetected, mNoSimsCombo.getTag())) {
                mNoSimsCombo.setTag(mSimDetected);
                /// M:alps03596830 Don't show lack of signal when airplane mode is on.
                if (mSimDetected && !mIsAirplaneMode) {
                    SignalDrawable d = new SignalDrawable(mNoSims.getContext());
                    d.setDarkIntensity(0);
                    mNoSims.setImageDrawable(d);
                    mNoSims.setImageLevel(SignalDrawable.getEmptyState(4));

                    SignalDrawable dark = new SignalDrawable(mNoSims.getContext());
                    dark.setDarkIntensity(1);
                    mNoSimsDark.setImageDrawable(dark);
                    mNoSimsDark.setImageLevel(SignalDrawable.getEmptyState(4));
                } else {
                    mNoSims.setImageResource(R.drawable.stat_sys_no_sims);
                    mNoSimsDark.setImageResource(R.drawable.stat_sys_no_sims);
                }
            }
        } else {
            mIconLogger.onIconHidden(SLOT_MOBILE);
            mNoSimsCombo.setVisibility(View.GONE);
        }

        /// M: Add for Plugin feature @ {
        mStatusBarExt.setCustomizedNoSimsVisible(mNoSimsVisible);
        mStatusBarExt.setCustomizedAirplaneView(mNoSimsCombo, mIsAirplaneMode);
        /// @ }

        boolean anythingVisible = mNoSimsVisible || mWifiVisible || mIsAirplaneMode
                || anyMobileVisible || mVpnVisible || mEthernetVisible;
        setPaddingRelative(0, 0, anythingVisible ? mEndPadding : mEndPaddingNothingVisible, 0);
    }

    /**
     * Sets the given drawable id on the view. This method will also scale the icon by
     * {@link #mIconScaleFactor} if appropriate.
     */
    private void setIconForView(ImageView imageView, @DrawableRes int iconId) {
        // Using the imageView's context to retrieve the Drawable so that theme is preserved.
        Drawable icon = imageView.getContext().getDrawable(iconId);

        if (mIconScaleFactor == 1.f) {
            imageView.setImageDrawable(icon);
        } else {
            imageView.setImageDrawable(new ScalingDrawableWrapper(icon, mIconScaleFactor));
        }
    }


    @Override
    public void onDarkChanged(Rect tintArea, float darkIntensity, int tint) {
        boolean changed = tint != mIconTint || darkIntensity != mDarkIntensity
                || !mTintArea.equals(tintArea);
        mIconTint = tint;
        mDarkIntensity = darkIntensity;
        mTintArea.set(tintArea);
        if (changed && isAttachedToWindow()) {
            applyIconTint();
        }
    }

    private void applyIconTint() {
        setTint(mVpn, DarkIconDispatcher.getTint(mTintArea, mVpn, mIconTint));
        setTint(mAirplane, DarkIconDispatcher.getTint(mTintArea, mAirplane, mIconTint));
        applyDarkIntensity(
                DarkIconDispatcher.getDarkIntensity(mTintArea, mNoSims, mDarkIntensity),
                mNoSims, mNoSimsDark);
        /// M: Add for noSim view in tint mode. @{
        mStatusBarExt.setNoSimIconTint(mIconTint, mNoSims);
        /// @}

        /// M: Add for plugin items tint handling. @{
        mStatusBarExt.setCustomizedPlmnTextTint(mIconTint);
        /// @}

        setTint(mEthernet, DarkIconDispatcher.getTint(mTintArea, mEthernet, mIconTint));
        setTint(mEthernetDark, DarkIconDispatcher.getTint(mTintArea, mEthernetDark, mIconTint));

        applyDarkIntensity(
                DarkIconDispatcher.getDarkIntensity(mTintArea, mWifi, mDarkIntensity),
                mWifi, mWifiDark);
        setTint(mWifiActivityIn,
                DarkIconDispatcher.getTint(mTintArea, mWifiActivityIn, mIconTint));
        setTint(mWifiActivityOut,
                DarkIconDispatcher.getTint(mTintArea, mWifiActivityOut, mIconTint));
        applyDarkIntensity(
                DarkIconDispatcher.getDarkIntensity(mTintArea, mEthernet, mDarkIntensity),
                mEthernet, mEthernetDark);
        for (int i = 0; i < mPhoneStates.size(); i++) {
            mPhoneStates.get(i).setIconTint(mIconTint, mDarkIntensity, mTintArea);
        }
    }

    private void applyDarkIntensity(float darkIntensity, View lightIcon, View darkIcon) {
        lightIcon.setAlpha(1 - darkIntensity);
        darkIcon.setAlpha(darkIntensity);
    }

    private void setTint(ImageView v, int tint) {
        v.setImageTintList(ColorStateList.valueOf(tint));
    }

    private int currentVpnIconId(boolean isBranded) {
        return isBranded ? R.drawable.stat_sys_branded_vpn : R.drawable.stat_sys_vpn_ic;
    }

    private class PhoneState {
        private final int mSubId;
        private boolean mMobileVisible = false;
        private int mMobileStrengthId = 0, mMobileTypeId = 0;
        ///M: Add for [Network Type and volte on Statusbar]
        private int mNetworkIcon = 0;
        private int mVolteIcon = 0;
        private String mOperator = "0";

        private int mLastMobileStrengthId = -1;
        private int mLastMobileTypeId = -1;
        private boolean mIsMobileTypeIconWide;
        private String mMobileDescription, mMobileTypeDescription;

        private ViewGroup mMobileGroup;
        private ImageView mMobile, mMobileDark, mMobileType, mMobileRoaming;
        public boolean mRoaming;
        private ImageView mMobileActivityIn;
        private ImageView mMobileActivityOut;

        private ImageView mMobileDataActivity;
        public boolean mActivityIn;
        public boolean mActivityOut;
        /// M: Add for new features @ {
        // Add for [Network Type and volte on Statusbar]
        private ImageView mNetworkType;
        private ImageView mVolteType;
        private TextView mOperatorType;
        private boolean mIsWfcCase;
        /// @ }

        /// M: Add for plugin features. @ {
        private boolean mDataActivityIn, mDataActivityOut;
        private ISystemUIStatusBarExt mPhoneStateExt;
        private Context yContext;
        /// @ }

        public PhoneState(int subId, Context context) {
            yContext = context;
            ViewGroup root = (ViewGroup) LayoutInflater.from(context)
                    .inflate(R.layout.mobile_signal_group_ext, null);

            /// M: Add data group for plugin feature. @ {
            mPhoneStateExt = OpSystemUICustomizationFactoryBase.getOpFactory(context)
                                .makeSystemUIStatusBar(context);
            mPhoneStateExt.addCustomizedView(subId, context, root);
            /// @ }

            setViews(root);
            mSubId = subId;
        }

        public void setViews(ViewGroup root) {
            mMobileGroup    = root;
            mMobile         = root.findViewById(R.id.mobile_signal);
            mMobileDark     = root.findViewById(R.id.mobile_signal_dark);
            mMobileType     = root.findViewById(R.id.mobile_type);
           ///M: Add for [Network Type and volte on Statusbar]
            mNetworkType    = (ImageView) root.findViewById(R.id.network_type);
            mVolteType      = (ImageView) root.findViewById(R.id.volte_indicator_ext);
            mOperatorType      = (TextView) root.findViewById(R.id.tv_operator);
            mMobileRoaming  = root.findViewById(R.id.mobile_roaming);
            mMobileActivityIn = root.findViewById(R.id.mobile_in);
            mMobileActivityOut = root.findViewById(R.id.mobile_out);

            mMobileDataActivity = root.findViewById(R.id.data_inout);
            // TODO: Remove the 2 instances because now the drawable can handle darkness.
            //20190510 pjz annotation
            /*mMobile.setImageDrawable(new SignalDrawable(mMobile.getContext()));
            SignalDrawable drawable = new SignalDrawable(mMobileDark.getContext());
            drawable.setDarkIntensity(1);
            mMobileDark.setImageDrawable(drawable);*/
        }

        public boolean apply(boolean isSecondaryIcon) {
            Log.e(TAG, "apply()  mMobileVisible = " + mMobileVisible
                                + ", mIsAirplaneMode = " + mIsAirplaneMode
                                + ", mIsWfcEnable = " + mIsWfcEnable
                                + ", mIsWfcCase = " + mIsWfcCase
                                + ", mVolteIcon = " + mVolteIcon);
            if (mMobileVisible && !mIsAirplaneMode) {
                 Log.e(TAG, "apply() into this code 1.. mMobileStrengthId=="+mMobileStrengthId);
                 Log.i("ccz", "apply() into this code 1.. mMobileStrengthId=="+mMobileStrengthId);
                if (mLastMobileStrengthId != mMobileStrengthId) {
                    /*mMobile.getDrawable().setLevel(mMobileStrengthId);
                    mMobileDark.getDrawable().setLevel(mMobileStrengthId);*/
                    //20190510 pjz show singal icon [S]
                    mMobile.setImageResource(mMobileStrengthId);
                    mMobileDark.setImageResource(mMobileStrengthId);
                    //20190510 pjz show singal icon [E]
                    
                    mLastMobileStrengthId = mMobileStrengthId;
                }

                if (mLastMobileTypeId != mMobileTypeId) {
                    if (!mPhoneStateExt.disableHostFunction()) {
                        mMobileType.setImageResource(mMobileTypeId);
                    }
                    mLastMobileTypeId = mMobileTypeId;
                }

                ////20190510 pjz show  date in out icon 
                mMobileDataActivity.setImageResource(
                    getDataActivityIcon(mDataActivityIn, mDataActivityOut));
                    //getDataActivityIcon(mActivityIn, mActivityOut));
                mMobileGroup.setContentDescription(mMobileTypeDescription
                        + " " + mMobileDescription);
                mMobileGroup.setVisibility(View.VISIBLE);
                showViewInWfcCase();
            } else {
                if (mIsAirplaneMode && (mIsWfcEnable && mVolteIcon != 0)) {
                     Log.e(TAG, "apply() into this code 2..");
                    /// M:Bug fix for show vowifi icon in flight mode
                    mMobileGroup.setVisibility(View.VISIBLE);
                    hideViewInWfcCase();
                } else {
                     Log.e(TAG, "apply() into this code 3..");
                    if (DEBUG) {
                        Log.d(TAG, "setVisibility as GONE, this = " + this
                                + ", mMobileVisible = " + mMobileVisible
                                + ", mIsAirplaneMode = " + mIsAirplaneMode
                                + ", mIsWfcEnable = " + mIsWfcEnable
                                + ", mVolteIcon = " + mVolteIcon);
                    }
                    mMobileGroup.setVisibility(View.GONE);
                }
            }

            /// M: Set all added or customised view. @ {
            setCustomizeViewProperty();
            /// @ }

            // When this isn't next to wifi, give it some extra padding between the signals.
            mMobileGroup.setPaddingRelative(isSecondaryIcon ? mSecondaryTelephonyPadding : 0,
                    0, 0, 0);
            mMobile.setPaddingRelative(
                    mIsMobileTypeIconWide ? mWideTypeIconStartPadding : mMobileDataIconStartPadding,
                    0, 0, 0);
            mMobileDark.setPaddingRelative(
                    mIsMobileTypeIconWide ? mWideTypeIconStartPadding : mMobileDataIconStartPadding,
                    0, 0, 0);

            if (true) Log.d(TAG, String.format("mobile: %s sig=%d typ=%d",
                        (mMobileVisible ? "VISIBLE" : "GONE"), mMobileStrengthId, mMobileTypeId));


             Log.e(TAG, "mActivityIn="+mActivityIn+" mActivityOut="+mActivityOut);   
            if(!mIsWfcCase) {
                //20190510 pjz hide small datatype icon x or 4G
                mMobileType.setVisibility(/*mMobileTypeId != 0 ? View.VISIBLE :*/ View.GONE);
                mMobileDataActivity.setVisibility(mMobileTypeId != 0 ? View.VISIBLE : View.GONE);//add
                mMobileRoaming.setVisibility(mRoaming ? View.VISIBLE : View.GONE);
                mMobileActivityIn.setVisibility(mActivityIn ? View.VISIBLE : View.GONE);
                mMobileActivityOut.setVisibility(mActivityOut ? View.VISIBLE : View.GONE);
            }

            /// M: Add for support plugin featurs. @ {
            setCustomizedOpViews();
            /// @ }

            return mMobileVisible;
        }

        public void populateAccessibilityEvent(AccessibilityEvent event) {
            if (mMobileVisible && mMobileGroup != null
                    && mMobileGroup.getContentDescription() != null) {
                event.getText().add(mMobileGroup.getContentDescription());
            }
        }

        public void setIconTint(int tint, float darkIntensity, Rect tintArea) {
            applyDarkIntensity(
                    DarkIconDispatcher.getDarkIntensity(tintArea, mMobile, darkIntensity),
                    mMobile, mMobileDark);
            setTint(mMobileType, DarkIconDispatcher.getTint(tintArea, mMobileType, tint));
            setTint(mMobileRoaming, DarkIconDispatcher.getTint(tintArea, mMobileRoaming,
                    tint));
            setTint(mMobileActivityIn,
                    DarkIconDispatcher.getTint(tintArea, mMobileActivityIn, tint));
            setTint(mMobileActivityOut,
                    DarkIconDispatcher.getTint(tintArea, mMobileActivityOut, tint));

            ///M: Add for [Network Type and volte on Statusbar]
            ////20190510 pjz add for dark color icon tint
            setTint(mMobile, DarkIconDispatcher.getTint(tintArea, mMobile, tint));
            setTint(mMobileDark, DarkIconDispatcher.getTint(tintArea, mMobileDark, tint));
            setTint(mMobileDataActivity, DarkIconDispatcher.getTint(tintArea, mMobileDataActivity, tint));
            mOperatorType.setTextColor(DarkIconDispatcher.getTint(tintArea, mOperatorType, tint));

            setTint(mNetworkType, DarkIconDispatcher.getTint(tintArea, mNetworkType, tint));
            setTint(mVolteType, DarkIconDispatcher.getTint(tintArea, mVolteType, tint));
            /// M: Add for op views in tint mode. @{
            mPhoneStateExt.setIconTint(DarkIconDispatcher.getTint(tintArea, mNetworkType, tint),
                    darkIntensity);
            /// @}
        }

        /// M: Set all added or customised view. @ {
        private void setCustomizeViewProperty() {
            // Add for [Network Type on Statusbar], the place to set network type icon.
            setNetworkIcon();
            /// M: Add for volte icon.
            setVolteIcon();
            ///pjz: add for operator text
            setOperatorText();
        }

        // pjz: add for operator text
        private void setOperatorText(){
            if ("0".equals(mOperator)) {
                mOperatorType.setVisibility(View.GONE);
            } else {
                mOperatorType.setText(mOperator);
                mOperatorType.setVisibility(View.VISIBLE);
            }
        }

        /// M: Add for volte icon on Statusbar @{
        private void setVolteIcon() {
            if (mVolteIcon == 0) {
                mVolteType.setVisibility(View.GONE);
            } else {
                mVolteType.setImageResource(mVolteIcon);
                mVolteType.setVisibility(View.VISIBLE);
            }
            /// M: customize VoLTE icon. @{
            mStatusBarExt.setCustomizedVolteView(mVolteIcon, mVolteType);
            mStatusBarExt.setDisVolteView(mSubId, mVolteIcon, mVolteType);
            /// M: customize VoLTE icon. @}
        }
        ///@}

        /// M : Add for [Network Type on Statusbar]
        private void setNetworkIcon() {
            // Network type is CTA feature, so non CTA project should not set this.
            if ((!FeatureOptions.MTK_CTA_SET) || mIsWfcCase) {
                return;
            }
            if (mNetworkIcon == 0) {
                mNetworkType.setVisibility(View.GONE);
            } else {
                if (!mPhoneStateExt.disableHostFunction()) {
                    mNetworkType.setImageResource(mNetworkIcon);
                }
                mNetworkType.setVisibility(View.VISIBLE);
            }
        }

        /// M: Add for plugin features. @ {
        private void setCustomizedOpViews() {
            mPhoneStateExt.SetHostViewInvisible(mMobileRoaming);
            mPhoneStateExt.SetHostViewInvisible(mMobileActivityIn);
            mPhoneStateExt.SetHostViewInvisible(mMobileActivityOut);
            if (mMobileVisible && !mIsAirplaneMode) {
                mPhoneStateExt.getServiceStateForCustomizedView(mSubId);

                mPhoneStateExt.setCustomizedAirplaneView(
                    mNoSimsCombo, mIsAirplaneMode);
                mPhoneStateExt.setCustomizedNetworkTypeView(
                    mSubId, mNetworkIcon, mNetworkType);
                mPhoneStateExt.setCustomizedDataTypeView(
                    mSubId, mMobileTypeId,
                    mDataActivityIn, mDataActivityOut);
                mPhoneStateExt.setCustomizedSignalStrengthView(
                    mSubId, mMobileStrengthId, mMobile);
                mPhoneStateExt.setCustomizedSignalStrengthView(
                    mSubId, mMobileStrengthId, mMobileDark);
                mPhoneStateExt.setCustomizedMobileTypeView(
                    mSubId, mMobileType);
                mPhoneStateExt.setCustomizedView(mSubId);
            }
        }
        /// @ }

        private void hideViewInWfcCase() {
            Log.d(TAG, "hideViewInWfcCase, isWfcEnabled = " + mIsWfcEnable + " mSubId =" + mSubId);
            mMobile.setVisibility(View.GONE);
            mMobileDark.setVisibility(View.GONE);
            mMobileType.setVisibility(View.GONE);
            mNetworkType.setVisibility(View.GONE);
            mMobileRoaming.setVisibility(View.GONE);
            mIsWfcCase = true;
        }

        private void showViewInWfcCase() {
            if (mIsWfcCase) {
                Log.d(TAG, "showViewInWfcCase: mSubId = " + mSubId);
                mMobile.setVisibility(View.VISIBLE);
                mMobileDark.setVisibility(View.VISIBLE);
                mMobileType.setVisibility(View.VISIBLE);
                mNetworkType.setVisibility(View.VISIBLE);
                mMobileRoaming.setVisibility(mRoaming ? View.VISIBLE : View.GONE);
                mIsWfcCase = false;
            }
        }

          //20190510 pjz add for data in out icon [S]  
         final int DATA_ACTIVITY_NONE = R.drawable.ct_stat_sys_signal_not_inout;
         final int DATA_ACTIVITY_IN = R.drawable.ct_stat_sys_signal_in;
         final int DATA_ACTIVITY_OUT = R.drawable.ct_stat_sys_signal_out;
         final int DATA_ACTIVITY_INOUT = R.drawable.ct_stat_sys_signal_inout;

        /**
         * M: getDataActivityIcon: Get DataActivity icon by dataActivity type.
         * @param activityIn : dataActivity Type
         * @param activityOut : dataActivity Type
         * @return  dataActivity icon ID
         */
         public int getDataActivityIcon(boolean activityIn, boolean activityOut) {
            Log.i(TAG, "mActivityIn="+mActivityIn+" mActivityOut="+mActivityOut);  
            int icon = DATA_ACTIVITY_NONE;

            if (activityIn && activityOut) {
                icon = DATA_ACTIVITY_INOUT;
            }else if (activityIn) {
                icon = DATA_ACTIVITY_IN;
            }else if (activityOut) {
                icon = DATA_ACTIVITY_OUT;
            }

            return icon;
        }
        //20190510 pjz add for data in out icon [S]  
    }
}