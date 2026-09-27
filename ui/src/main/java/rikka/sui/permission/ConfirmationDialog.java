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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerHidden;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Window;
import android.view.WindowManager;

import java.util.Objects;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import dev.rikka.tools.refine.Refine;
import rikka.html.text.HtmlCompat;
import rikka.sui.R;
import rikka.sui.ktx.HandlerKt;
import rikka.sui.ktx.TextViewKt;
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
    private final Context dialogContext;

    private static final class PermissionDialogContext extends ContextWrapper {
        private final Resources resources;
        private final Resources.Theme theme;

        PermissionDialogContext(Context base, Resources resources) {
            super(base);
            this.resources = resources;
            theme = resources.newTheme();
            theme.applyStyle(R.style.Theme_Sui_PermissionDialog, true);
        }

        @Override
        public AssetManager getAssets() {
            return resources.getAssets();
        }

        @Override
        public Resources getResources() {
            return resources;
        }

        @Override
        public Resources.Theme getTheme() {
            return theme;
        }
    }

    public ConfirmationDialog(Application application, Resources resources) {
        this.context = application;
        this.resources = resources;
        this.dialogContext = DynamicColors.wrapContextIfAvailable(new PermissionDialogContext(application, resources));
    }

    public void show(int requestUid, int requestPid, String requestPackageName, int requestCode) {
        HandlerKt.getMainHandler().post(() -> showInternal(requestUid, requestPid, requestPackageName, requestCode));
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

        CharSequence title = HtmlCompat.fromHtml(String.format(
                resources.getString(R.string.permission_warning_template),
                label,
                resources.getString(R.string.permission_description)));
        boolean[] resultSent = {false};

        AlertDialog dialog = new MaterialAlertDialogBuilder(dialogContext, R.style.ThemeOverlay_Sui_PermissionDialog)
                .setIcon(resources.getDrawable(R.drawable.ic_su_24, dialogContext.getTheme()))
                .setTitle(title)
                .setNeutralButton(R.string.grant_dialog_button_allow_always, (d, which) -> {
                    resultSent[0] = true;
                    setResult(requestUid, requestPid, requestCode, true, false);
                })
                .setPositiveButton(R.string.grant_dialog_button_allow_one_time, (d, which) -> {
                    resultSent[0] = true;
                    setResult(requestUid, requestPid, requestCode, true, true);
                })
                .setNegativeButton(R.string.grant_dialog_button_deny_and_dont_ask_again, (d, which) -> {
                    resultSent[0] = true;
                    setResult(requestUid, requestPid, requestCode, false, false);
                })
                .create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(d -> {
            TextViewKt.applyCountdown(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), 1, null, 0);
            TextViewKt.applyCountdown(dialog.getButton(AlertDialog.BUTTON_POSITIVE), 1, null, 0);
            TextViewKt.applyCountdown(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), 1, null, 0);
        });

        BroadcastReceiver closeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                dialog.dismiss();
            }
        };
        boolean[] receiverRegistered = {false};
        IntentFilter closeFilter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(closeReceiver, closeFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(closeReceiver, closeFilter);
            }
            receiverRegistered[0] = true;
        } catch (Throwable e) {
            LOGGER.w(e, "register close receiver");
        }
        dialog.setOnDismissListener(d -> {
            if (receiverRegistered[0]) {
                try {
                    context.unregisterReceiver(closeReceiver);
                } catch (Throwable e) {
                    LOGGER.w(e, "unregister close receiver");
                }
            }
            if (!resultSent[0]) {
                resultSent[0] = true;
                setResult(requestUid, requestPid, requestCode, false, true);
            }
        });

        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.type = WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
            attributes.token = TOKEN;
            attributes.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            attributes.dimAmount = 0.32f;
            WindowKt.setPrivateFlags(attributes, WindowKt.getPrivateFlags(attributes)
                    | WindowKt.getSYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS());
            window.setAttributes(attributes);
        }
        dialog.show();
    }
}
