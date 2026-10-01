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

package rikka.sui.util;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ServiceManager;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.List;

import moe.shizuku.server.IShizukuService;
import rikka.parcelablelist.ParcelableListSlice;
import rikka.sui.model.AppInfo;

public class BridgeServiceClient {

    private static final String TAG = "SuiSettings";
    private static final int BINDER_TRANSACTION_getApplications = 10001;
    private static final int BINDER_TRANSACTION_getDiagnosticBuildId = 10004;
    private static final int BINDER_TRANSACTION_getGlobalAutoGrant = 10005;
    private static final int BINDER_TRANSACTION_setGlobalAutoGrant = 10006;

    private static IBinder binder;
    private static IShizukuService service;

    private static final int BRIDGE_TRANSACTION_CODE = ('_' << 24) | ('S' << 16) | ('U' << 8) | 'I';
    private static final String BRIDGE_SERVICE_DESCRIPTOR = "android.app.IActivityManager";
    private static final String BRIDGE_SERVICE_NAME = "activity";
    private static final int BRIDGE_ACTION_GET_BINDER = 2;

    private static final IBinder.DeathRecipient DEATH_RECIPIENT = () -> {
        Log.w(TAG, "Sui service binder died");
        binder = null;
        service = null;
    };

    private static IBinder requestBinderFromBridge() {
        IBinder activityBinder = ServiceManager.getService(BRIDGE_SERVICE_NAME);
        if (activityBinder == null) {
            Log.e(TAG, "Activity service binder is unavailable");
            return null;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(BRIDGE_SERVICE_DESCRIPTOR);
            data.writeInt(BRIDGE_ACTION_GET_BINDER);
            if (!activityBinder.transact(BRIDGE_TRANSACTION_CODE, data, reply, 0)) {
                Log.e(TAG, "Activity service did not handle the Sui bridge transaction");
                return null;
            }
            reply.readException();
            IBinder received = reply.readStrongBinder();
            if (received != null) {
                Log.i(TAG, "Received Sui service binder from activity bridge");
                return received;
            }
            Log.e(TAG, "Activity bridge returned an empty Sui service binder");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to request Sui service binder from activity bridge", e);
        } finally {
            data.recycle();
            reply.recycle();
        }
        return null;
    }

    protected static void setBinder(@Nullable IBinder binder) {
        if (BridgeServiceClient.binder == binder) return;

        if (BridgeServiceClient.binder != null) {
            BridgeServiceClient.binder.unlinkToDeath(DEATH_RECIPIENT, 0);
        }

        if (binder == null) {
            BridgeServiceClient.binder = null;
            BridgeServiceClient.service = null;
        } else {
            BridgeServiceClient.binder = binder;
            BridgeServiceClient.service = IShizukuService.Stub.asInterface(binder);

            try {
                BridgeServiceClient.binder.linkToDeath(DEATH_RECIPIENT, 0);
            } catch (Throwable ignored) {
            }
        }
    }

    public static IShizukuService getService() {
        if (service == null) {
            setBinder(requestBinderFromBridge());
        }
        return service;
    }

    public static List<AppInfo> getApplications(int userId) {
        IShizukuService currentService = getService();
        if (currentService == null) {
            throw new IllegalStateException("Sui service binder is unavailable");
        }

        IBinder targetBinder = currentService.asBinder();
        if (targetBinder == null) {
            throw new IllegalStateException("Sui service returned a null binder");
        }

        String descriptor = "<unavailable>";
        try {
            descriptor = targetBinder.getInterfaceDescriptor();
        } catch (Throwable e) {
            Log.w(TAG, "Unable to read Sui service binder descriptor", e);
        }

        String localInterfaceClass;
        try {
            Object localInterface = targetBinder.queryLocalInterface("moe.shizuku.server.IShizukuService");
            localInterfaceClass = localInterface == null ? "null" : localInterface.getClass().getName();
        } catch (Throwable e) {
            localInterfaceClass = "<error: " + e.getClass().getName() + ">";
            Log.w(TAG, "Unable to query local Sui service interface", e);
        }
        Log.i(TAG, "getApplications target binder: class=" + targetBinder.getClass().getName()
                + " descriptor=" + descriptor + " localInterface=" + localInterfaceClass);
        logDiagnosticBuildId(targetBinder);

        Log.d(TAG, "Requesting applications for user " + userId);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            data.writeInt(userId);
            boolean handled;
            try {
                handled = targetBinder.transact(BINDER_TRANSACTION_getApplications, data, reply, 0);
            } catch (Throwable e) {
                throw new RuntimeException("Sui getApplications transaction failed", e);
            }
            Log.i(TAG, "getApplications transact result: handled=" + handled
                    + " replySize=" + reply.dataSize());
            if (!handled) {
                throw new IllegalStateException("Sui service did not handle getApplications transaction");
            }

            reply.readException();
            List<AppInfo> result;
            if ((0 != reply.readInt())) {
                //noinspection unchecked
                result = ParcelableListSlice.CREATOR.createFromParcel(reply).getList();
            } else {
                throw new IllegalStateException("Sui service returned no application list");
            }

            if (result == null) {
                throw new IllegalStateException("Sui service returned a null application list");
            }
            Log.i(TAG, "Received " + result.size() + " applications for user " + userId);
            return result;
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to retrieve applications for user " + userId, e);
            throw e;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static boolean getGlobalAutoGrantEnabled() {
        return transactGlobalAutoGrant(BINDER_TRANSACTION_getGlobalAutoGrant, null);
    }

    public static boolean setGlobalAutoGrantEnabled(boolean enabled) {
        return transactGlobalAutoGrant(BINDER_TRANSACTION_setGlobalAutoGrant, enabled);
    }

    private static boolean transactGlobalAutoGrant(int transactionCode, Boolean enabled) {
        IShizukuService currentService = getService();
        if (currentService == null || currentService.asBinder() == null) {
            throw new IllegalStateException("Sui service binder is unavailable");
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            if (enabled != null) {
                data.writeInt(enabled ? 1 : 0);
            }
            boolean handled = currentService.asBinder().transact(transactionCode, data, reply, 0);
            if (!handled) {
                throw new IllegalStateException("Sui service did not handle global auto-grant transaction");
            }
            reply.readException();
            return reply.readInt() != 0;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Global auto-grant transaction failed", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void logDiagnosticBuildId(IBinder targetBinder) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
            boolean handled = targetBinder.transact(BINDER_TRANSACTION_getDiagnosticBuildId, data, reply, 0);
            Log.i(TAG, "diagnostic build-ID transact result: handled=" + handled
                    + " replySize=" + reply.dataSize());
            if (!handled) {
                Log.w(TAG, "Sui diagnostic build-ID transaction is not supported by this service");
                return;
            }

            reply.readException();
            Log.i(TAG, "SUI_SERVER_BUILD_ID=" + reply.readString());
        } catch (Throwable e) {
            Log.e(TAG, "Failed to read Sui diagnostic build ID", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

}
