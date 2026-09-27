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

import androidx.recyclerview.widget.DiffUtil
import rikka.recyclerview.BaseRecyclerViewAdapter
import rikka.recyclerview.ClassCreatorPool
import rikka.sui.model.AppInfo

class ManagementAdapter : BaseRecyclerViewAdapter<ClassCreatorPool>() {

    init {
        creatorPool.putRule(AppInfo::class.java, ManagementAppItemViewHolder.CREATOR)
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        val app = getItemAt<AppInfo>(position)
        val identity = app.packageInfo.packageName.hashCode().toLong() shl 32
        return identity xor app.packageInfo.applicationInfo.uid.toLong()
    }

    override fun onCreateCreatorPool(): ClassCreatorPool {
        return ClassCreatorPool()
    }

    fun updateData(data: List<AppInfo>) {
        val oldItems = getItems<AppInfo>().toList()
        val newItems = data.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldApp = oldItems[oldItemPosition]
                val newApp = newItems[newItemPosition]
                return oldApp.packageInfo.packageName == newApp.packageInfo.packageName
                        && oldApp.packageInfo.applicationInfo.uid == newApp.packageInfo.applicationInfo.uid
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldApp = oldItems[oldItemPosition]
                val newApp = newItems[newItemPosition]
                return oldApp.flags == newApp.flags
                        && oldApp.label?.toString() == newApp.label?.toString()
                        && oldApp.packageInfo.lastUpdateTime == newApp.packageInfo.lastUpdateTime
            }
        })

        getItems<AppInfo>().apply {
            clear()
            addAll(newItems)
        }
        diff.dispatchUpdatesTo(this)
    }
}
