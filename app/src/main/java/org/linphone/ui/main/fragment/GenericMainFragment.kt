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

import android.os.Bundle
import android.view.View
import androidx.annotation.IdRes
import androidx.annotation.UiThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.ui.GenericFragment
import org.linphone.ui.main.viewmodel.AbstractMainViewModel
import org.linphone.ui.main.viewmodel.SharedMainViewModel
import org.linphone.utils.Event

@UiThread
abstract class GenericMainFragment : GenericFragment() {
    companion object {
        private const val TAG = "[Generic Main Fragment]"
    }

    protected lateinit var sharedViewModel: SharedMainViewModel

    private var currentTabId: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedViewModel = requireActivity().run {
            ViewModelProvider(this)[SharedMainViewModel::class.java]
        }
    }

    override fun onResume() {
        super.onResume()

        if (currentTabId > 0) {
            sharedViewModel.currentlyDisplayedFragment.value = currentTabId
        }
    }

    // Wires the bottom navigation bar of a "tab" fragment (Contacts, Calls, History, Meetings)
    protected fun initTabNavigation(navViewModel: AbstractMainViewModel, @IdRes tabId: Int) {
        currentTabId = tabId

        val destinations = listOf<Pair<MutableLiveData<Event<Boolean>>, Int>>(
            navViewModel.navigateToContactsEvent to R.id.contactsListFragment,
            navViewModel.navigateToStartCallEvent to R.id.startCallFragment,
            navViewModel.navigateToHistoryEvent to R.id.historyListFragment,
            navViewModel.navigateToConversationsEvent to R.id.conversationsListFragment,
            navViewModel.navigateToMeetingsEvent to R.id.meetingsListFragment,
            sharedViewModel.navigateToContactsEvent to R.id.contactsListFragment,
            sharedViewModel.navigateToHistoryEvent to R.id.historyListFragment,
            sharedViewModel.navigateToConversationsEvent to R.id.conversationsListFragment,
            sharedViewModel.navigateToMeetingsEvent to R.id.meetingsListFragment
        )
        for ((event, destination) in destinations) {
            event.observe(viewLifecycleOwner) {
                it.consume { goToTab(destination) }
            }
        }

        sharedViewModel.currentlyDisplayedFragment.observe(viewLifecycleOwner) {
            navViewModel.contactsSelected.value = it == R.id.contactsListFragment
            navViewModel.callsSelected.value = it == R.id.startCallFragment
            navViewModel.historySelected.value = it == R.id.historyListFragment
            navViewModel.meetingsSelected.value = it == R.id.meetingsListFragment
        }
    }

    private fun goToTab(@IdRes destination: Int) {
        if (destination == currentTabId) return

        Log.i("$TAG Leaving tab [$currentTabId] for [$destination]")
        try {
            val navOptions = NavOptions.Builder()
                .setPopUpTo(currentTabId, true)
                .setLaunchSingleTop(true)
                .build()
            findNavController().navigate(destination, null, navOptions)
        } catch (e: Exception) {
            Log.e("$TAG Failed to navigate: $e")
        }
    }

    protected fun getFragmentRealClassName(): String {
        return "[${this.javaClass.name}]"
    }

    protected open fun goBack(): Boolean {
        Log.d("$TAG ${getFragmentRealClassName()} Going back")
        try {
            Log.d("$TAG ${getFragmentRealClassName()} Calling onBackPressed on activity dispatcher")
            requireActivity().onBackPressedDispatcher.onBackPressed()
        } catch (ise: IllegalStateException) {
            Log.w("$TAG ${getFragmentRealClassName()} Can't go back: $ise")
            return false
        }
        return true
    }
}
