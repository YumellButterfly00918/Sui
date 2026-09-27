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
package rikka.sui.management

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import rikka.lifecycle.Resource
import rikka.lifecycle.Status
import rikka.sui.model.AppInfo
import rikka.sui.R
import rikka.sui.util.AppInfoCache
import rikka.sui.util.AppInfoComparator
import rikka.sui.util.BridgeServiceClient
import rikka.sui.util.UserHandleCompat

class ManagementViewModel : ViewModel() {

    private val fullList = ArrayList<AppInfo>()
    private var reloadJob: Job? = null

    val appList = MutableLiveData<Resource<List<AppInfo>>>(null)

    private fun handleList() {
        val list = fullList.sortedWith(AppInfoComparator()).toList()

        appList.postValue(Resource.success(list))
    }

    fun invalidateList() {
        if (appList.value?.status != Status.SUCCESS) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            handleList()
        }
    }

    fun reload(context: Context) {
        reload(context, null)
    }

    fun onPackageRemoved(context: Context, packageName: String, userId: Int?) {
        reload(context, packageName, userId)
    }

    private fun reload(context: Context, removedPackage: String?, removedUserId: Int? = null) {
        if (fullList.isEmpty() && appList.value?.status != Status.SUCCESS) {
            appList.postValue(Resource.loading(null))
        }

        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(Dispatchers.IO) {
            var stage = "requesting applications from the Sui service"
            try {
                if (fullList.isEmpty()) {
                    AppInfoCache.read(context)?.let {
                        fullList.addAll(it)
                        handleList()
                    }
                }

                if (removedPackage != null) {
                    fullList.removeAll {
                        it.packageInfo.packageName == removedPackage
                                && (removedUserId == null || UserHandleCompat.getUserId(it.packageInfo.applicationInfo.uid) == removedUserId)
                    }
                    AppInfoCache.write(context, fullList)
                    handleList()
                }

                val pm = context.packageManager
                val result = BridgeServiceClient.getApplications(-1 /* ALL */)
                stage = "loading labels and icons for ${result.size} applications"
                val iconSize = context.resources.getDimensionPixelSize(R.dimen.expected_app_icon_max_size)
                result.forEach {
                    val appInfo = it.packageInfo.applicationInfo
                    it.label = appInfo.loadLabel(pm).toString()
                    it.icon = try {
                        val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
                        val drawable = appInfo.loadIcon(pm)
                        drawable.setBounds(0, 0, iconSize, iconSize)
                        drawable.draw(Canvas(bitmap))
                        bitmap
                    } catch (e: Throwable) {
                        Log.w("SuiSettings", "Failed to cache icon for ${appInfo.packageName}", e)
                        null
                    }
                }

                fullList.clear()
                fullList.addAll(result)
                AppInfoCache.write(context, fullList)

                Log.i("SuiSettings", "Loaded metadata for ${result.size} applications")
                handleList()
            } catch (e: CancellationException) {

            } catch (e: Throwable) {
                Log.e("SuiSettings", "Failed while $stage", e)
                if (fullList.isEmpty()) {
                    appList.postValue(Resource.error(e, null))
                }
            }
        }
    }
}
