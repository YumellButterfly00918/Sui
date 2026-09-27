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
import android.os.Looper
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

class ManagementViewModel : ViewModel() {

    private val fullList = ArrayList<AppInfo>()
    private var reloadJob: Job? = null
    @Volatile
    private var hasPublishedList = false

    val appList = MutableLiveData<Resource<List<AppInfo>>>(null)
    val syncCompleted = MutableLiveData<Unit>()

    private fun handleList() {
        val list = fullList.sortedWith(AppInfoComparator()).toList()
        hasPublishedList = true
        val result = Resource.success(list)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appList.value = result
        } else {
            appList.postValue(result)
        }
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
        reload(context, silent = false)
    }

    fun sync(context: Context) {
        reload(context, silent = true)
    }

    fun onPackageRemoved(context: Context) {
        sync(context)
    }

    private fun reload(context: Context, silent: Boolean) {
        if (!hasPublishedList) {
            AppInfoCache.read(context)?.let {
                fullList.clear()
                fullList.addAll(it)
                handleList()
            }
        }

        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(Dispatchers.IO) {
            var stage = "requesting applications from the Sui service"
            try {
                val pm = context.packageManager
                val result = BridgeServiceClient.getApplications(-1 /* ALL */)
                stage = "loading labels for ${result.size} applications"
                result.forEach {
                    it.label = it.packageInfo.applicationInfo.loadLabel(pm).toString()
                }

                if (!hasPublishedList || !sameApps(fullList, result)) {
                    stage = "loading icons for ${result.size} applications"
                    val iconSize = context.resources.getDimensionPixelSize(R.dimen.expected_app_icon_max_size)
                    result.forEach {
                        val appInfo = it.packageInfo.applicationInfo
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
                }
            } catch (e: CancellationException) {

            } catch (e: Throwable) {
                Log.e("SuiSettings", "Failed while $stage", e)
                if (!silent) {
                    appList.postValue(Resource.error(e, null))
                }
            } finally {
                syncCompleted.postValue(Unit)
            }
        }
    }

    private fun sameApps(current: List<AppInfo>, fresh: List<AppInfo>): Boolean {
        if (current.size != fresh.size) return false

        val currentByIdentity = current.associateBy {
            it.packageInfo.packageName to it.packageInfo.applicationInfo.uid
        }
        return fresh.all { app ->
            val identity = app.packageInfo.packageName to app.packageInfo.applicationInfo.uid
            val cached = currentByIdentity[identity] ?: return@all false
            cached.flags == app.flags
                    && cached.label?.toString() == app.label?.toString()
                    && cached.packageInfo.lastUpdateTime == app.packageInfo.lastUpdateTime
        }
    }
}
