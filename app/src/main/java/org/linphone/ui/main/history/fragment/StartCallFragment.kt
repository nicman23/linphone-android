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
package org.linphone.ui.main.history.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.StartCallFragmentBinding
import org.linphone.ui.GenericActivity
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.fragment.GenericAddressPickerFragment
import org.linphone.ui.main.history.viewmodel.StartCallViewModel
import org.linphone.ui.main.model.GroupSetOrEditSubjectDialogModel
import org.linphone.ui.main.viewmodel.AbstractMainViewModel
import org.linphone.utils.DialogUtils
import org.linphone.utils.addCharacterAtPosition
import org.linphone.utils.hideKeyboard
import org.linphone.utils.removeCharacterAtPosition
import org.linphone.utils.setKeyboardInsetListener
import org.linphone.utils.showKeyboard

@UiThread
class StartCallFragment : GenericAddressPickerFragment() {
    companion object {
        private const val TAG = "[Start Call Fragment]"
    }

    private lateinit var binding: StartCallFragmentBinding

    override lateinit var viewModel: StartCallViewModel

    private var keyboardVisible = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = StartCallFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewModel = ViewModelProvider(this)[StartCallViewModel::class.java]

        postponeEnterTransition()
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner

        viewModel.title.value = getString(R.string.history_call_start_title)
        binding.viewModel = viewModel
        observeToastEvents(viewModel)

        // This fragment is the "Calls" tab of the bottom nav bar
        val navViewModel = ViewModelProvider(this)[AbstractMainViewModel::class.java]
        binding.navViewModel = navViewModel
        binding.bottomNavBar.root.visibility = View.VISIBLE
        initTabNavigation(navViewModel, R.id.startCallFragment)

        navViewModel.openDrawerMenuEvent.observe(viewLifecycleOwner) {
            it.consume {
                (requireActivity() as MainActivity).toggleDrawerMenu()
            }
        }

        binding.setBackClickListener {
            navViewModel.openDrawerMenu()
        }

        binding.setAskForGroupCallSubjectClickListener {
            showGroupCallSubjectDialog()
        }

        setupRecyclerView(binding.contactsAndSuggestionsList)

        viewModel.modelsList.observe(
            viewLifecycleOwner
        ) {
            Log.i("$TAG Contacts & suggestions list is ready with [${it.size}] items")
            adapter.submitList(it)

            attachAdapter()

            (view.parent as? ViewGroup)?.doOnPreDraw {
                startPostponedEnterTransition()
            }
        }

        viewModel.leaveFragmentEvent.observe(viewLifecycleOwner) {
            it.consume {
                // We are a tab, can't go back: reset the screen instead (search, group call selection)
                coreContext.postOnMainThread {
                    Log.i("$TAG Call started, resetting start call tab")
                    try {
                        val navOptions = NavOptions.Builder()
                            .setPopUpTo(R.id.startCallFragment, true)
                            .build()
                        findNavController().navigate(R.id.startCallFragment, null, navOptions)
                    } catch (e: Exception) {
                        Log.e("$TAG Failed to reset start call tab: $e")
                    }
                }
            }
        }

        viewModel.removedCharacterAtCurrentPositionEvent.observe(viewLifecycleOwner) {
            it.consume {
                binding.searchBar.removeCharacterAtPosition()
            }
        }

        viewModel.clearSearchBarEvent.observe(viewLifecycleOwner) {
            it.consume {
                binding.searchBar.setText("")
            }
        }

        viewModel.appendDigitToSearchBarEvent.observe(viewLifecycleOwner) {
            it.consume { digit ->
                binding.searchBar.addCharacterAtPosition(digit)
            }
        }

        viewModel.requestKeyboardVisibilityChangedEvent.observe(viewLifecycleOwner) {
            it.consume { show ->
                if (show) {
                    // To automatically open keyboard
                    binding.searchBar.showKeyboard()
                } else {
                    binding.searchBar.requestFocus()
                    binding.searchBar.hideKeyboard()
                }
            }
        }

        // Dialpad can't be swiped, tapped or backed away: it only makes room for the keyboard
        // and for the group call participants selection
        val bottomSheetBehavior = BottomSheetBehavior.from(binding.numpadLayout.root)
        bottomSheetBehavior.isDraggable = false
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        binding.numpadLayout.handle.visibility = View.INVISIBLE

        viewModel.multipleSelectionMode.observe(viewLifecycleOwner) {
            updateNumpadVisibility()
        }

        viewModel.isNumpadVisible.observe(viewLifecycleOwner) { visible ->
            if (visible) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            } else {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }

        viewModel.defaultAccountChangedEvent.observe(viewLifecycleOwner) {
            it.consume {
                viewModel.updateGroupCallButtonVisibility()
            }
        }

        binding.root.setKeyboardInsetListener { visible ->
            keyboardVisible = visible
            updateNumpadVisibility()
            val portraitOrientation = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            binding.bottomNavBar.root.isVisible = !portraitOrientation || !keyboardVisible
        }
    }

    override fun onResume() {
        super.onResume()

        updateNumpadVisibility()
    }

    private fun updateNumpadVisibility() {
        viewModel.isNumpadVisible.value = !keyboardVisible && viewModel.multipleSelectionMode.value != true
    }

    private fun showGroupCallSubjectDialog() {
        val model = GroupSetOrEditSubjectDialogModel("", isGroupConversation = false)

        val dialog = DialogUtils.getSetOrEditGroupSubjectDialog(
            requireContext(),
            viewLifecycleOwner,
            model
        )

        model.dismissEvent.observe(viewLifecycleOwner) {
            it.consume {
                Log.i("$TAG Set group call subject cancelled")
                dialog.dismiss()
            }
        }

        model.confirmEvent.observe(viewLifecycleOwner) {
            it.consume { newSubject ->
                if (newSubject.isNotEmpty()) {
                    Log.i(
                        "$TAG Group call subject has been set to [$newSubject]"
                    )
                    viewModel.subject.value = newSubject
                    viewModel.createGroupCall()

                    dialog.currentFocus?.hideKeyboard()
                    dialog.dismiss()
                } else {
                    val message = getString(R.string.conversation_invalid_empty_subject_toast)
                    val icon = R.drawable.warning_circle
                    (requireActivity() as GenericActivity).showRedToast(message, icon)
                }
            }
        }

        Log.i("$TAG Showing dialog to set group call subject")
        dialog.show()
    }
}
