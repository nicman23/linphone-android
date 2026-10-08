/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.fragment

import android.content.res.Configuration
import android.graphics.Outline
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.annotation.UiThread
import androidx.core.view.doOnPreDraw
import androidx.slidingpanelayout.widget.SlidingPaneLayout
import com.google.android.material.textfield.TextInputLayout
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.BottomNavBarBinding
import org.linphone.databinding.MainActivityTopBarBinding
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.viewmodel.AbstractMainViewModel
import org.linphone.utils.Event
import org.linphone.utils.SlidingPaneBackPressedCallback
import org.linphone.utils.hideKeyboard
import org.linphone.utils.setKeyboardInsetListener
import org.linphone.utils.showKeyboard

@UiThread
abstract class AbstractMainFragment : GenericMainFragment() {
    companion object {
        private const val TAG = "[Abstract Main Fragment]"

        private const val TIME_MS_AFTER_WHICH_REFRESH_DATA_ON_RESUME = 3600000 // 1 hour
    }

    protected val outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View?, outline: Outline?) {
            val radius = resources.getDimension(R.dimen.top_bar_rounded_corner_radius)
            view ?: return
            outline?.setRoundRect(0, 0, view.width, (view.height + radius).toInt(), radius)
        }
    }

    protected var lastOnPauseTimestamp: Long = -1L

    private lateinit var navigationBar: View

    private lateinit var viewModel: AbstractMainViewModel

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (viewModel.searchBarVisible.value == true) {
                viewModel.closeSearchBar()
                return
            }

            Log.i("$TAG Search bar is closed, going back")
            isEnabled = false
            try {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            } catch (ise: IllegalStateException) {
                Log.w("$TAG Can't go back: $ise")
            }
        }
    }

    abstract fun onDefaultAccountChanged()

    abstract fun onSlidingPaneClosed()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        postponeEnterTransition()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            backPressedCallback
        )

        lastOnPauseTimestamp = -1
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onPause() {
        lastOnPauseTimestamp = System.currentTimeMillis()
        super.onPause()
    }

    fun shouldRefreshDataInOnResume(): Boolean {
        if (lastOnPauseTimestamp == -1L) return false
        if (!corePreferences.keepServiceAlive) return false
        return System.currentTimeMillis() - lastOnPauseTimestamp > TIME_MS_AFTER_WHICH_REFRESH_DATA_ON_RESUME
    }

    fun setViewModel(abstractMainViewModel: AbstractMainViewModel) {
        (view?.parent as? ViewGroup)?.doOnPreDraw {
            startPostponedEnterTransition()
        }

        viewModel = abstractMainViewModel

        viewModel.openDrawerMenuEvent.observe(viewLifecycleOwner) {
            it.consume {
                (requireActivity() as MainActivity).toggleDrawerMenu()
            }
        }

        viewModel.searchFilter.observe(viewLifecycleOwner) { filter ->
            viewModel.applyFilter(filter.trim())
        }

        viewModel.missedCallsCount.observe(viewLifecycleOwner) {
            sharedViewModel.refreshDrawerMenuAccountsListEvent.value = Event(false)
        }

        viewModel.defaultAccountChangedEvent.observe(viewLifecycleOwner) {
            it.consume {
                onDefaultAccountChanged()
            }
        }

        sharedViewModel.resetMissedCallsCountEvent.observe(viewLifecycleOwner) {
            it.consume {
                viewModel.resetMissedCallsCount()
            }
        }

        sharedViewModel.forceUpdateAvailableNavigationItems.observe(viewLifecycleOwner) {
            it.consume {
                coreContext.postOnCoreThread {
                    viewModel.updateAvailableMenus()
                }
            }
        }
    }

    fun initViews(
        slidingPane: SlidingPaneLayout,
        topBar: MainActivityTopBarBinding,
        navBar: BottomNavBarBinding,
        @IdRes fragmentId: Int
    ) {
        navigationBar = navBar.root

        initSlidingPane(slidingPane)
        initSearchBar(topBar.search)
        initTabNavigation(viewModel, fragmentId)
    }

    private fun initSlidingPane(slidingPane: SlidingPaneLayout) {
        val slidingPaneBackPressedCallback = SlidingPaneBackPressedCallback(slidingPane)
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            slidingPaneBackPressedCallback
        )

        view?.doOnPreDraw {
            slidingPane.lockMode = SlidingPaneLayout.LOCK_MODE_LOCKED
            val slideable = slidingPane.isSlideable
            sharedViewModel.isSlidingPaneSlideable.value = slideable
            slidingPaneBackPressedCallback.isEnabled = slideable && slidingPane.isOpen
            Log.d("$TAG Sliding Pane is ${if (slideable) "slideable" else "flat"}")
        }

        sharedViewModel.closeSlidingPaneEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume {
                if (slidingPane.isSlideable) {
                    Log.d("$TAG Closing sliding pane")
                    ensureNavigationBarIsVisible()
                    onSlidingPaneClosed()

                    if (!slidingPane.closePane()) {
                        Log.d("$TAG Failed to close sliding pane!")
                        coreContext.postOnMainThreadDelayed( {
                            Log.d("$TAG Trying once again to close sliding pane")
                            slidingPane.close()
                        }, 200)
                    }
                }
            }
        }

        sharedViewModel.openSlidingPaneEvent.observe(
            viewLifecycleOwner
        ) {
            it.consume {
                if (slidingPane.isSlideable && viewModel.searchBarVisible.value == true) {
                    viewModel.focusSearchBarEvent.value = Event(false)
                }

                if (!slidingPane.isOpen) {
                    Log.d("$TAG Opening sliding pane")
                    if (slidingPane.isSlideable && viewModel.searchBarVisible.value == true) {
                        slidingPane.addPanelSlideListener(object : SlidingPaneLayout.SimplePanelSlideListener() {
                            override fun onPanelOpened(panel: View) {
                                Log.d("$TAG Closing search bar")
                                viewModel.closeSearchBar()
                                slidingPane.removePanelSlideListener(this)
                            }

                            override fun onPanelClosed(panel: View) {
                                ensureNavigationBarIsVisible()
                            }
                        })
                    }
                    slidingPane.openPane()
                }
            }
        }
    }

    private fun initSearchBar(searchBar: TextInputLayout) {
        searchBar.editText?.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                view.hideKeyboard()
                return@setOnEditorActionListener true
            }
            false
        }

        viewModel.searchBarVisible.observe(viewLifecycleOwner) { visible ->
            backPressedCallback.isEnabled = visible
        }

        viewModel.focusSearchBarEvent.observe(viewLifecycleOwner) {
            it.consume { show ->
                if (show) {
                    // To automatically open keyboard
                    searchBar.showKeyboard()
                } else {
                    searchBar.hideKeyboard()
                    ensureNavigationBarIsVisible()
                }
            }
        }

        searchBar.setKeyboardInsetListener { keyboardVisible ->
            val portraitOrientation = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            navigationBar.visibility = if (!portraitOrientation || !keyboardVisible) View.VISIBLE else View.GONE
        }
    }

    private fun ensureNavigationBarIsVisible() {
        if (::navigationBar.isInitialized) {
            navigationBar.visibility = View.VISIBLE
        }
    }
}
