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
package rikka.sui.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.Log
import rikka.sui.model.AppInfo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

internal object AppInfoCache {

    private const val FILE_NAME = "management-apps.cache"
    private const val CACHE_VERSION = 2
    private const val MAX_APP_COUNT = 100_000
    private const val MAX_ICON_BYTES = 16 * 1024 * 1024

    fun read(context: Context): List<AppInfo>? {
        val file = AtomicFile(File(context.filesDir, FILE_NAME))
        if (!file.baseFile.exists()) return null

        return try {
            DataInputStream(BufferedInputStream(file.openRead())).use { input ->
                val version = input.readInt()
                if (version !in 1..CACHE_VERSION) return@use null
                val count = input.readInt()
                if (count !in 0..MAX_APP_COUNT) throw IOException("Invalid cached app count: $count")

                ArrayList<AppInfo>(count).apply {
                    repeat(count) {
                        val packageName = input.readUTF()
                        val uid = input.readInt()
                        val lastUpdateTime = if (version >= 2) input.readLong() else 0L
                        val flags = input.readInt()
                        val label = input.readUTF()
                        val iconSize = input.readInt()
                        val icon = if (iconSize == -1) {
                            null
                        } else {
                            if (iconSize !in 0..MAX_ICON_BYTES) throw IOException("Invalid cached icon size: $iconSize")
                            BitmapFactory.decodeByteArray(ByteArray(iconSize).also { input.readFully(it) }, 0, iconSize)
                        }
                        val applicationInfo = ApplicationInfo().apply {
                            this.packageName = packageName
                            this.uid = uid
                        }
                        val packageInfo = PackageInfo().apply {
                            this.packageName = packageName
                            this.applicationInfo = applicationInfo
                            this.lastUpdateTime = lastUpdateTime
                        }
                        add(AppInfo().apply {
                            this.packageInfo = packageInfo
                            this.flags = flags
                            this.label = label
                            this.icon = icon
                        })
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("SuiSettings", "Failed to read cached applications", e)
            null
        }
    }

    fun write(context: Context, apps: List<AppInfo>) {
        val file = AtomicFile(File(context.filesDir, FILE_NAME))
        try {
            val output = file.startWrite()
            try {
                val data = DataOutputStream(BufferedOutputStream(output))
                data.writeInt(CACHE_VERSION)
                data.writeInt(apps.size)
                apps.forEach { app ->
                    data.writeUTF(app.packageInfo.packageName)
                    data.writeInt(app.packageInfo.applicationInfo.uid)
                    data.writeLong(app.packageInfo.lastUpdateTime)
                    data.writeInt(app.flags)
                    data.writeUTF(app.label?.toString().orEmpty())
                    val icon = app.icon
                    if (icon == null) {
                        data.writeInt(-1)
                    } else {
                        val bytes = ByteArrayOutputStream().use { buffer ->
                            if (!icon.compress(Bitmap.CompressFormat.PNG, 100, buffer)) {
                                throw IOException("Failed to encode app icon")
                            }
                            buffer.toByteArray()
                        }
                        data.writeInt(bytes.size)
                        data.write(bytes)
                    }
                }
                data.flush()
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }
        } catch (e: Exception) {
            Log.w("SuiSettings", "Failed to cache applications", e)
        }
    }
}