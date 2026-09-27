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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DefaultItemAnimator
import rikka.core.res.resolveColor
import rikka.core.res.resolveDimension
import rikka.lifecycle.Resource
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.recyclerview.fixEdgeEffect
import rikka.sui.R
import rikka.sui.app.AppFragment
import rikka.sui.databinding.ManagementBinding
import rikka.sui.model.AppInfo
import rikka.widget.borderview.BorderView.OnBorderVisibilityChangedListener

class ManagementFragment : AppFragment() {

    private var _binding: ManagementBinding? = null
    private val binding: ManagementBinding get() = _binding!!

    private val viewModel by viewModels { ManagementViewModel().apply { sync(requireAppActivity()) } }
    private val adapter = ManagementAdapter()
    private var hasBeenStopped = false
    private val packageChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        viewModel.sync(context)
                    }
                }
                Intent.ACTION_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_FULLY_REMOVED -> viewModel.sync(context)
                Intent.ACTION_PACKAGE_REMOVED -> {
                    if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        viewModel.sync(context)
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = view.context

        binding.list.apply {
            borderVisibilityChangedListener =
                OnBorderVisibilityChangedListener { top: Boolean, _: Boolean, _: Boolean, _: Boolean ->
                    appActivity?.appBar?.setRaised(!top)
                }
            adapter = this@ManagementFragment.adapter
            (itemAnimator as DefaultItemAnimator).supportsChangeAnimations = false
            isVerticalScrollBarEnabled = false
            fixEdgeEffect()

            layoutAnimationListener = object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {}
                override fun onAnimationRepeat(animation: Animation?) {}
            }
        }

        binding.swipeRefresh.apply {
            setOnRefreshListener {
                viewModel.reload(context)
            }
            setColorSchemeColors(
                context.theme.resolveColor(android.R.attr.colorAccent)
            )
            val actionBarSize = context.theme.resolveDimension(androidx.appcompat.R.attr.actionBarSize, 0f).toInt()
            setProgressViewOffset(false, actionBarSize, (64 * resources.displayMetrics.density + actionBarSize).toInt())
        }

        viewModel.appList.observe(viewLifecycleOwner) {
            when (it?.status) {
                Status.SUCCESS -> onSuccess(it)
                Status.ERROR -> onError(it.error)
                else -> {}
            }
        }
        viewModel.syncCompleted.observe(viewLifecycleOwner) {
            binding.swipeRefresh.isRefreshing = false
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(packageChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(packageChangedReceiver, filter)
        }
        if (hasBeenStopped) {
            hasBeenStopped = false
            viewModel.sync(requireContext())
        }
    }

    override fun onStop() {
        requireContext().unregisterReceiver(packageChangedReceiver)
        hasBeenStopped = true
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun onError(e: Throwable) {
        binding.apply {
            swipeRefresh.isEnabled = true
            swipeRefresh.isRefreshing = false
            list.isVisible = true
        }

        val detail = e.localizedMessage ?: e.javaClass.simpleName
        Toast.makeText(
            requireContext(),
            getString(R.string.management_load_error, detail.take(160)),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun onSuccess(data: Resource<List<AppInfo>?>) {
        binding.apply {
            swipeRefresh.isEnabled = true
            swipeRefresh.isRefreshing = false
            list.isVisible = true
        }

        data.data?.let {
            adapter.updateData(it)
        }
    }
}
