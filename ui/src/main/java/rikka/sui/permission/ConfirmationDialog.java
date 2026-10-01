/*
 * This file is part of Sui.
 *
 * Sui is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Sui is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Sui.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2021 Sui Contributors
 */

package rikka.sui.permission;

import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerHidden;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Objects;

import dev.rikka.tools.refine.Refine;
import rikka.html.text.HtmlCompat;
import rikka.sui.R;
import rikka.sui.databinding.ConfirmationDialogBinding;
import rikka.sui.ktx.HandlerKt;
import rikka.sui.ktx.WindowKt;
import rikka.sui.util.AppLabel;
import rikka.sui.util.BridgeServiceClient;
import rikka.sui.util.Logger;
import rikka.sui.util.UserHandleCompat;

public class ConfirmationDialog {

    private static final IBinder TOKEN = new Binder();
    private static final Logger LOGGER = new Logger("ConfirmationDialog");

    private final Context context;
    private final Resources resources;

    public ConfirmationDialog(Application application, Resources resources) {
        this.context = application;
        this.resources = resources;
    }

    public void show(int requestUid, int requestPid, String requestPackageName, int requestCode) {
        try {
            HandlerKt.getMainHandler().post(() -> {
                try {
                    showInternal(requestUid, requestPid, requestPackageName, requestCode);
                } catch (Throwable t) {
                    Log.e("SuiPermission", "CRASH", t);
                    rejectAfterDialogFailure(requestUid, requestPid, requestCode);
                }
            });
        } catch (Throwable t) {
            Log.e("SuiPermission", "CRASH", t);
            rejectAfterDialogFailure(requestUid, requestPid, requestCode);
        }
    }

    private void rejectAfterDialogFailure(int requestUid, int requestPid, int requestCode) {
        try {
            setResult(requestUid, requestPid, requestCode, false, true);
        } catch (Throwable resultError) {
            Log.e("SuiPermission", "Failed to reject after dialog initialization failure", resultError);
        }
    }

    private void setResult(int requestUid, int requestPid, int requestCode, boolean allowed, boolean onetime) {
        Bundle data = new Bundle();
        data.putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed);
        data.putBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime);

        try {
            BridgeServiceClient.getService().dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data);
        } catch (Throwable e) {
            LOGGER.e("dispatchPermissionConfirmationResult");
        }
    }

    private void showInternal(int requestUid, int requestPid, String requestPackageName, int requestCode) {
        LayoutInflater layoutInflater = LayoutInflater.from(context);
        Resources.Theme theme = context.getTheme();
        boolean isNight = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        float density = resources.getDisplayMetrics().density;
        int primaryTextColor = isNight ? Color.WHITE : Color.rgb(32, 33, 36);
        if (isNight) {
            theme.applyStyle(android.R.style.Theme_DeviceDefault_Dialog, true);
        } else {
            theme.applyStyle(android.R.style.Theme_DeviceDefault_Light_Dialog, true);
        }

        SystemDialogRootView root = new SystemDialogRootView(context) {

            @Override
            public boolean onBackPressed() {
                return false;
            }

            @Override
            public void onClose() {
                setResult(requestUid, requestPid, requestCode, false, true);
            }
        };

        View view = layoutInflater.inflate(resources.getLayout(R.layout.confirmation_dialog), root, false);
        ConfirmationDialogBinding binding = ConfirmationDialogBinding.bind(view);
        root.addView(binding.getRoot());

        String label = requestPackageName;
        int userId = UserHandleCompat.getUserId(requestUid);
        PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo ai = Objects.requireNonNull(Refine.<PackageManagerHidden>unsafeCast(pm))
                    .getApplicationInfoAsUser(requestPackageName, PackageManagerHidden.MATCH_UNINSTALLED_PACKAGES, userId);
            label = AppLabel.getAppLabel(ai, context);
        } catch (Throwable e) {
            LOGGER.e("getApplicationInfoAsUser");
        }

        int iconSize = Math.round(56 * density);
        LinearLayout.LayoutParams iconLayoutParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLayoutParams.gravity = Gravity.CENTER_HORIZONTAL;
        binding.icon.setLayoutParams(iconLayoutParams);
        binding.icon.setGravity(Gravity.CENTER);
        binding.icon.setText("#");
        binding.icon.setTextSize(24);
        binding.icon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        binding.icon.setTextColor(Color.WHITE);
        binding.icon.setBackground(createRoundedBackground(Color.parseColor("#2F4577"), 16 * density));
        int iconPadding = Math.round(12 * density);
        binding.icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
        binding.title.setText(HtmlCompat.fromHtml(
                String.format(resources.getString(R.string.permission_warning_template), label, resources.getString(R.string.permission_description))));
        binding.title.setTextColor(primaryTextColor);
        binding.button1.setTextColor(Color.WHITE);
        binding.button2.setTextColor(Color.WHITE);
        binding.button3.setTextColor(Color.WHITE);
        binding.button1.setText(resources.getString(R.string.grant_dialog_button_allow_always));
        binding.button2.setText(resources.getString(R.string.grant_dialog_button_allow_one_time));
        binding.button3.setText(resources.getString(R.string.grant_dialog_button_deny_and_dont_ask_again));
        binding.button1.setEnabled(true);
        binding.button2.setEnabled(true);
        binding.button3.setEnabled(true);

        binding.getRoot().setBackground(createRoundedBackground(
            isNight ? Color.rgb(27, 27, 31) : Color.WHITE, 28 * density));
        binding.getRoot().setClipToOutline(true);
        binding.button1.setBackground(createRoundedBackground(Color.parseColor("#2F4577"), 16 * density));
        binding.button2.setBackground(createRoundedBackground(Color.parseColor("#2F4577"), 16 * density));
        binding.button3.setBackground(createRoundedBackground(Color.parseColor("#2F4577"), 16 * density));

        binding.button1.setOnClickListener(v -> {
            setResult(requestUid, requestPid, requestCode, true, false);
            root.dismiss();
        });
        binding.button2.setOnClickListener(v -> {
            setResult(requestUid, requestPid, requestCode, true, true);
            root.dismiss();
        });
        binding.button3.setOnClickListener(v -> {
            setResult(requestUid, requestPid, requestCode, false, false);
            root.dismiss();
        });

        WindowManager.LayoutParams attr = new WindowManager.LayoutParams();
        attr.width = ViewGroup.LayoutParams.MATCH_PARENT;
        attr.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        attr.flags = WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        attr.type = WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
        attr.token = TOKEN;
        attr.gravity = Gravity.CENTER;
        attr.windowAnimations = android.R.style.Animation_Dialog;
        attr.dimAmount = 0.32f;
        attr.format = PixelFormat.TRANSLUCENT;
        WindowKt.setPrivateFlags(attr, WindowKt.getPrivateFlags(attr) | WindowKt.getSYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS());

        root.show(attr);
    }

    private static GradientDrawable createRoundedBackground(int color, float cornerRadiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(cornerRadiusPx);
        return drawable;
    }
}
