/*
 * Copyright (c) 2021 Auxio Project
 * MainFragment.kt is part of Auxio.
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.PopupMenu
import android.widget.PopupWindow
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import com.google.android.material.R as MR
import com.google.android.material.bottomsheet.BackportBottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.transition.MaterialFadeThrough
import com.leinardi.android.speeddial.SpeedDialActionItem
import com.leinardi.android.speeddial.SpeedDialView
import dagger.hilt.android.AndroidEntryPoint
import java.lang.reflect.Method
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import org.oxycblt.auxio.databinding.FragmentMainBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.detail.Show
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.home.Outer
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicType
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.OpenPanel
import org.oxycblt.auxio.playback.PlaybackBottomSheetBehavior
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.queue.QueueBottomSheetBehavior
import org.oxycblt.auxio.plugin.similarity.ZoneAxis
import org.oxycblt.auxio.plugin.similarity.ZoneAxisValue
import org.oxycblt.auxio.plugin.similarity.ZoneAxisViewModel
import org.oxycblt.auxio.ui.DialogAwareNavigationListener
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.context
import org.oxycblt.auxio.util.coordinatorLayoutBehavior
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.auxio.util.getDimen
import org.oxycblt.auxio.util.lazyReflectedMethod
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.auxio.util.unlikelyToBeNull
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A wrapper around the home fragment that shows the playback fragment and high-level navigation.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class MainFragment :
    ViewBindingFragment<FragmentMainBinding>(),
    ViewTreeObserver.OnPreDrawListener,
    SpeedDialView.OnActionSelectedListener {
    private val musicModel: MusicViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val homeModel: HomeViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val zoneModel: ZoneAxisViewModel by viewModels()
    private var sheetBackCallback: SheetBackPressedCallback? = null
    private var detailBackCallback: DetailBackPressedCallback? = null
    private var selectionBackCallback: SelectionBackPressedCallback? = null
    private var speedDialBackCallback: SpeedDialBackPressedCallback? = null
    private var navigationListener: DialogAwareNavigationListener? = null
    private var lastInsets: WindowInsets? = null
    private var elevationNormal = 0f
    private var normalCornerSize = 0f
    // Caches of the last-applied value for EVERY render-affecting property
    // onPreDraw touches. Android's View property setters (alpha, translationZ)
    // and several Drawable setters invalidate and force a GPU re-render on
    // EVERY assignment call, regardless of whether the new value actually
    // differs from the current one - since onPreDraw runs every single frame,
    // unconditionally reassigning any of these recreates a self-perpetuating
    // render loop even when nothing is actually changing. Guarding each with
    // "only set if changed" is what actually breaks that loop.
    private var lastQueueSheetTopRightCorner: Float? = null
    private var lastMainSheetScrimAlpha: Float? = null
    private var lastPlaybackCornerSize: Float? = null
    private var lastPlaybackTranslationZ: Float? = null
    private var lastPlaybackBarAlpha: Float? = null
    private var lastPlaybackPanelAlpha: Float? = null
    private var lastQueueFragmentAlpha: Float? = null
    private var lastQueueSheetAlpha: Float? = null
    private var maxScaleXDistance = 0f
    private var sheetRising: Boolean? = null
    @Inject lateinit var uiSettings: UISettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentMainBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentMainBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // This Fragment INSTANCE persists across navigation (e.g. to Settings
        // and back), but its VIEW/binding gets destroyed and recreated each
        // time - a fresh view starts with default properties (alpha=1, etc.),
        // but the cache fields above are on the Fragment instance, so they'd
        // otherwise survive with STALE values from the old view. That made
        // onPreDraw's "skip if unchanged" guards wrongly skip re-applying the
        // correct value against a fresh view that never actually had it set -
        // causing the mini-player/panel overlap glitch. Reset every cache so
        // the first onPreDraw against this fresh binding always applies.
        lastQueueSheetTopRightCorner = null
        lastMainSheetScrimAlpha = null
        lastPlaybackCornerSize = null
        lastPlaybackTranslationZ = null
        lastPlaybackBarAlpha = null
        lastPlaybackPanelAlpha = null
        lastQueueFragmentAlpha = null
        lastQueueSheetAlpha = null

        val playbackSheetBehavior =
            binding.playbackSheet.coordinatorLayoutBehavior as PlaybackBottomSheetBehavior
        playbackSheetBehavior.uiSettings = uiSettings
        playbackSheetBehavior.makeBackgroundDrawable(requireContext())
        val queueSheetBehavior =
            binding.queueSheet.coordinatorLayoutBehavior as QueueBottomSheetBehavior?
        queueSheetBehavior?.uiSettings = uiSettings

        elevationNormal = binding.context.getDimen(MR.dimen.m3_sys_elevation_level1)

        // Currently all back press callbacks are handled in MainFragment, as it's not guaranteed
        // that instantiating these callbacks in their respective fragments would result in the
        // correct order.
        sheetBackCallback =
            SheetBackPressedCallback(
                playbackSheetBehavior = playbackSheetBehavior,
                queueSheetBehavior = queueSheetBehavior)
        val detailBackCallback =
            DetailBackPressedCallback(detailModel).also { detailBackCallback = it }
        val selectionBackCallback =
            SelectionBackPressedCallback(listModel).also { selectionBackCallback = it }
        speedDialBackCallback = SpeedDialBackPressedCallback()

        navigationListener = DialogAwareNavigationListener(::onExploreNavigate)

        // --- UI SETUP ---
        val context = requireActivity()

        binding.root.setOnApplyWindowInsetsListener { _, insets ->
            lastInsets = insets
            insets
        }

        // Send meaningful accessibility events for bottom sheets
        ViewCompat.setAccessibilityPaneTitle(
            binding.playbackSheet, context.getString(R.string.lbl_playback))
        ViewCompat.setAccessibilityPaneTitle(
            binding.queueSheet, context.getString(R.string.lbl_queue))

        if (queueSheetBehavior != null) {
            // In portrait mode, set up click listeners on the stacked sheets.
            L.d("Configuring stacked bottom sheets")
            unlikelyToBeNull(binding.queueHandleWrapper).setOnClickListener {
                playbackModel.openQueue()
            }
        } else {
            // Dual-pane mode, manually style the static queue sheet.
            L.d("Configuring dual-pane bottom sheet")
            binding.queueSheet.apply {
                // Emulate the elevated bottom sheet style.
                background =
                    MaterialShapeDrawable.createWithElevationOverlay(context).apply {
                        shapeAppearanceModel =
                            ShapeAppearanceModel.builder(
                                    context,
                                    MR.style.ShapeAppearance_Material3_Corner_ExtraLarge,
                                    MR.style.ShapeAppearanceOverlay_Material3_Corner_Top)
                                .build()
                        fillColor = context.getAttrColorCompat(MR.attr.colorSurfaceContainerHigh)
                    }
            }
        }

        normalCornerSize = playbackSheetBehavior.sheetBackgroundDrawable.topLeftCornerResolvedSize
        maxScaleXDistance =
            context.getDimen(MR.dimen.m3_back_progress_bottom_container_max_scale_x_distance)

        binding.playbackSheet.elevation = 0f

        binding.mainScrim.setOnClickListener { binding.homeNewPlaylistFab.close() }
        binding.sheetScrim.setOnClickListener { binding.homeNewPlaylistFab.close() }
        binding.homeShuffleFab.setOnClickListener { playbackModel.shuffleAll() }
        binding.homeNewPlaylistFab.apply {
            inflate(R.menu.new_playlist_actions)
            setOnActionSelectedListener(this@MainFragment)
            setChangeListener(::updateSpeedDial)
        }

        forceHideAllFabs()
        updateSpeedDial(false)
        updateFabVisibility(
            binding,
            homeModel.songList.value,
            homeModel.isFastScrolling.value,
            homeModel.currentTabType.value)

        // --- VIEWMODEL SETUP ---
        // This has to be done here instead of the playback panel to make sure that it's prioritized
        // by StateFlow over any detail fragment.
        // FIXME: This is a consequence of sharing events across several consumers. There has to be
        //  a better way of doing this.
        collect(detailModel.toShow.flow, ::handleShow)
        collectImmediately(detailModel.editedPlaylist, detailBackCallback::invalidateEnabled)
        collectImmediately(homeModel.showOuter.flow, ::handleShowOuter)
        collectImmediately(homeModel.currentTabType, ::updateCurrentTab)
        collectImmediately(homeModel.songList, homeModel.isFastScrolling, ::updateFab)
        collectImmediately(musicModel.indexingState, ::updateIndexerState)
        collectImmediately(listModel.selected, selectionBackCallback::invalidateEnabled)
        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(playbackModel.openPanel.flow, ::handlePanel)

        // --- ZONE AXIS (opt-in) ---
        setUpZoneAxis(binding)
        collectImmediately(playbackModel.song) { song -> zoneModel.onSongChanged(song) }
    }

    private fun setUpZoneAxis(binding: FragmentMainBinding) {
        val language = binding.queueZoneLanguage
        val type = binding.queueZoneType
        if (!zoneModel.enabled) {
            language.isVisible = false
            type.isVisible = false
            return
        }
        language.isVisible = true
        type.isVisible = true

        language.setOnClickListener {
            showZonePopup(it, ZoneAxis.LANGUAGE, zoneModel.languageValues.value) { valueId ->
                confirmThenAssign(
                    axis = ZoneAxis.LANGUAGE,
                    currentValueId = zoneModel.currentTag.value?.languageValueId,
                    newValueId = valueId,
                    values = zoneModel.languageValues.value) {
                        zoneModel.assignLanguage(valueId)
                    }
            }
        }
        type.setOnClickListener {
            showZonePopup(it, ZoneAxis.TYPE, zoneModel.typeValues.value) { valueId ->
                confirmThenAssign(
                    axis = ZoneAxis.TYPE,
                    currentValueId = zoneModel.currentTag.value?.typeValueId,
                    newValueId = valueId,
                    values = zoneModel.typeValues.value) {
                        zoneModel.assignType(valueId)
                    }
            }
        }

        val all = getString(R.string.lbl_zone_all)
        // Long-press the "Queue" title -> compact 3-slider queue-order popup.
        binding.queueTitle.setOnLongClickListener {
            showQueueOrderPopup(it)
            true
        }
        collectImmediately(zoneModel.languageValues, zoneModel.currentTag) { values, tag ->
            val label = values.firstOrNull { it.id == tag?.languageValueId }?.label ?: all
            language.text = getString(R.string.lbl_zone_value_arrow, label)
        }
        collectImmediately(zoneModel.typeValues, zoneModel.currentTag) { values, tag ->
            val label = values.firstOrNull { it.id == tag?.typeValueId }?.label ?: all
            type.text = getString(R.string.lbl_zone_value_arrow, label)
        }
    }

    /**
     * Compact anchored popup with the three within-ring ordering sliders
     * (Similarity, Frequency, Random), each -1..+1 with a center tick. Dragging
     * adjusts freely but does NOT persist; Apply commits all three and reshuffles
     * the queue; Reset zeroes the sliders (waits for Apply); closing without
     * Apply discards. Not a PopupMenu (can't host sliders) — a PopupWindow over
     * a custom card, width matched to the queue sheet so it never spills out.
     */
    private fun showQueueOrderPopup(anchor: View) {
        val view =
            LayoutInflater.from(requireContext())
                .inflate(R.layout.popup_queue_order, null, false)
        val simSlider = view.findViewById<com.google.android.material.slider.Slider>(
            R.id.queue_order_similarity)
        val freqSlider = view.findViewById<com.google.android.material.slider.Slider>(
            R.id.queue_order_frequency)
        val randSlider = view.findViewById<com.google.android.material.slider.Slider>(
            R.id.queue_order_random)
        val simValue = view.findViewById<android.widget.TextView>(
            R.id.queue_order_similarity_value)
        val freqValue = view.findViewById<android.widget.TextView>(
            R.id.queue_order_frequency_value)
        val randValue = view.findViewById<android.widget.TextView>(
            R.id.queue_order_random_value)
        val axisMetaSlider = view.findViewById<com.google.android.material.slider.Slider>(
            R.id.queue_order_axis_metadata)
        val axisMetaValue = view.findViewById<android.widget.TextView>(
            R.id.queue_order_axis_metadata_value)
        val typeFilterView = view.findViewById<android.widget.AutoCompleteTextView>(
            R.id.queue_order_type_filter)
        val languageFilterView = view.findViewById<android.widget.AutoCompleteTextView>(
            R.id.queue_order_language_filter)

        fun bind(slider: com.google.android.material.slider.Slider, label: android.widget.TextView) {
            label.text = "%+.2f".format(slider.value)
            slider.addOnChangeListener { _, v, _ -> label.text = "%+.2f".format(v) }
        }
        // Seed from persisted values (local until Apply).
        simSlider.value = zoneModel.queueOrderSimilarity.coerceIn(-1f, 1f)
        freqSlider.value = zoneModel.queueOrderFrequency.coerceIn(-1f, 1f)
        randSlider.value = zoneModel.queueOrderRandom.coerceIn(-1f, 1f)
        axisMetaSlider.value = zoneModel.queueOrderAxisMetadata.coerceIn(-1f, 1f)
        bind(simSlider, simValue)
        bind(freqSlider, freqValue)
        bind(randSlider, randValue)
        bind(axisMetaSlider, axisMetaValue)

        // Type/Language filters: "Any" + every configured value on that axis.
        // A picked filter hard-locks Stage 3's scope to that value, bypassing
        // Random's ranked-depth for that specific axis (see ChainRepository —
        // Random alone can't express "any Type, only Language=X" since Type is
        // always weighted higher in the ranked-depth formula).
        val anyLabel = getString(R.string.lbl_queue_order_filter_any)
        val typeValues = zoneModel.typeValues.value
        val languageValues = zoneModel.languageValues.value
        val typeOptions = listOf(anyLabel) + typeValues.map { it.label }
        val languageOptions = listOf(anyLabel) + languageValues.map { it.label }
        typeFilterView.setAdapter(
            android.widget.ArrayAdapter(
                requireContext(), android.R.layout.simple_list_item_1, typeOptions))
        languageFilterView.setAdapter(
            android.widget.ArrayAdapter(
                requireContext(), android.R.layout.simple_list_item_1, languageOptions))
        val currentTypeLabel =
            typeValues.find { it.id == zoneModel.queueOrderTypeFilterId }?.label ?: anyLabel
        val currentLangLabel =
            languageValues.find { it.id == zoneModel.queueOrderLanguageFilterId }?.label ?: anyLabel
        typeFilterView.setText(currentTypeLabel, false)
        languageFilterView.setText(currentLangLabel, false)

        val width =
            requireBinding().queueSheet.width.takeIf { it > 0 }
                ?: ViewGroup.LayoutParams.WRAP_CONTENT
        val popup = PopupWindow(view, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 8f * resources.displayMetrics.density

        view.findViewById<View>(R.id.queue_order_reset).setOnClickListener {
            simSlider.value = 0f
            freqSlider.value = 0f
            randSlider.value = 0f
            axisMetaSlider.value = 0f
            typeFilterView.setText(anyLabel, false)
            languageFilterView.setText(anyLabel, false)
        }
        view.findViewById<View>(R.id.queue_order_apply).setOnClickListener {
            zoneModel.queueOrderSimilarity = simSlider.value
            zoneModel.queueOrderFrequency = freqSlider.value
            zoneModel.queueOrderRandom = randSlider.value
            zoneModel.queueOrderAxisMetadata = axisMetaSlider.value
            zoneModel.queueOrderTypeFilterId =
                typeValues.find { it.label == typeFilterView.text?.toString() }?.id
            zoneModel.queueOrderLanguageFilterId =
                languageValues.find { it.label == languageFilterView.text?.toString() }?.id
            playbackModel.reapplyChainOrder()
            popup.dismiss()
        }
        popup.showAsDropDown(anchor)
    }

    /**
     * Assignment safety rule: a first-time tag (blank -> value) assigns instantly;
     * changing OR clearing an existing tag asks for confirmation first, so an
     * accidental tap can't silently overwrite a deliberate tag.
     */
    private fun confirmThenAssign(
        axis: String,
        currentValueId: Long?,
        newValueId: Long?,
        values: List<ZoneAxisValue>,
        doAssign: () -> Unit
    ) {
        // No-op: picking the value it already has.
        if (currentValueId == newValueId) return
        // Blank -> value: instant, nothing to lose.
        if (currentValueId == null) {
            doAssign()
            return
        }
        val all = getString(R.string.lbl_zone_all)
        val fromLabel = values.firstOrNull { it.id == currentValueId }?.label ?: all
        val message =
            if (newValueId == null) {
                // Value -> All (clear).
                getString(R.string.lbl_zone_assign_clear_confirm, axis, fromLabel)
            } else {
                val toLabel = values.firstOrNull { it.id == newValueId }?.label ?: all
                getString(R.string.lbl_zone_assign_change_confirm, axis, fromLabel, toLabel)
            }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_zone_assign_change_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> doAssign() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Show a popup listing [values] (plus "All" to clear) for [axis], anchored to
     * [anchor]. The axis name appears only as a disabled header while the popup
     * is open — the toolbar text itself only ever shows the current value.
     */
    private fun showZonePopup(
        anchor: View,
        axis: String,
        values: List<ZoneAxisValue>,
        onSelect: (Long?) -> Unit
    ) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(Menu.NONE, HEADER_ITEM_ID, 0, axis).isEnabled = false
        popup.menu.add(Menu.NONE, ALL_ITEM_ID, 1, getString(R.string.lbl_zone_all))
        values.forEachIndexed { index, value ->
            popup.menu.add(Menu.NONE, index, index + 2, value.label)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ALL_ITEM_ID -> onSelect(null)
                else -> onSelect(values.getOrNull(item.itemId)?.id)
            }
            true
        }
        popup.show()
    }

    override fun onStart() {
        super.onStart()
        val binding = requireBinding()
        // Once we add the destination change callback, we will receive another initialization call,
        // so handle that by resetting the flag.
        requireNotNull(navigationListener) { "NavigationListener was not available" }
            .attach(binding.exploreNavHost.findNavController())
        // Listener could still reasonably fire even if we clear the binding, attach/detach
        // our pre-draw listener our listener in onStart/onStop respectively.
        binding.playbackSheet.viewTreeObserver.addOnPreDrawListener(this@MainFragment)
    }

    override fun onResume() {
        super.onResume()
        // Override the back pressed listener so we can map back navigation to collapsing
        // navigation, navigation out of detail views, etc. We have to do this here in
        // onResume or otherwise the FragmentManager will have precedence.
        requireActivity().onBackPressedDispatcher.apply {
            addCallback(viewLifecycleOwner, requireNotNull(speedDialBackCallback))
            addCallback(viewLifecycleOwner, requireNotNull(selectionBackCallback))
            addCallback(viewLifecycleOwner, requireNotNull(detailBackCallback))
            addCallback(viewLifecycleOwner, requireNotNull(sheetBackCallback))
        }
    }

    override fun onStop() {
        super.onStop()
        val binding = requireBinding()
        requireNotNull(navigationListener) { "NavigationListener was not available" }
            .release(binding.exploreNavHost.findNavController())
        binding.playbackSheet.viewTreeObserver.removeOnPreDrawListener(this)
    }

    override fun onDestroyBinding(binding: FragmentMainBinding) {
        super.onDestroyBinding(binding)
        speedDialBackCallback = null
        sheetBackCallback = null
        detailBackCallback = null
        selectionBackCallback = null
        navigationListener = null
        binding.homeNewPlaylistFab.setChangeListener(null)
        binding.homeNewPlaylistFab.setOnActionSelectedListener(null)
    }

    override fun onPreDraw(): Boolean {
        // This is where I shove literally all the UI logic that won't behave any callback
        // or "normal" method I've tried. Surely running this on every frame will actually cause
        // it to work properly!

        // We overload CoordinatorLayout far too much to rely on any of it's typical
        // listener functionality. Just update all transitions before every draw. Should
        // probably be cheap enough.
        val binding = requireBinding()
        val playbackSheetBehavior =
            binding.playbackSheet.coordinatorLayoutBehavior as PlaybackBottomSheetBehavior
        val queueSheetBehavior =
            binding.queueSheet.coordinatorLayoutBehavior as QueueBottomSheetBehavior?

        val playbackRatio = max(playbackSheetBehavior.calculateSlideOffset(), 0f)
        // Stupid hack to prevent you from sliding the sheet up without closing the speed
        // dial. Filtering out ACTION_MOVE events will cause back gestures to close the
        // speed dial, which is super finicky behavior.
        val rising = playbackRatio > 0f
        if (rising != sheetRising) {
            sheetRising = rising
            updateFabVisibility(
                binding,
                homeModel.songList.value,
                homeModel.isFastScrolling.value,
                homeModel.currentTabType.value)
        }

        val playbackOutRatio = 1 - min(playbackRatio * 2, 1f)
        val playbackInRatio = max(playbackRatio - 0.5f, 0f) * 2

        val playbackMaxXScaleDelta = maxScaleXDistance / binding.playbackSheet.width
        val playbackEdgeRatio = max(playbackRatio - 0.9f, 0f) / 0.1f
        val playbackBackRatio =
            max(1 - ((1 - binding.playbackSheet.scaleX) / playbackMaxXScaleDelta), 0f)
        val playbackLastStretchRatio = min(playbackEdgeRatio * playbackBackRatio, 1f)
        if (playbackLastStretchRatio != lastMainSheetScrimAlpha) {
            lastMainSheetScrimAlpha = playbackLastStretchRatio
            binding.mainSheetScrim.alpha = playbackLastStretchRatio
        }

        val newPlaybackCornerSize = normalCornerSize * (1 - playbackLastStretchRatio)
        if (newPlaybackCornerSize != lastPlaybackCornerSize) {
            lastPlaybackCornerSize = newPlaybackCornerSize
            playbackSheetBehavior.sheetBackgroundDrawable.setCornerSize(newPlaybackCornerSize)
        }
        binding.exploreNavHost.isInvisible = playbackLastStretchRatio == 1f
        val newPlaybackTranslationZ = (1 - playbackLastStretchRatio) * elevationNormal
        if (newPlaybackTranslationZ != lastPlaybackTranslationZ) {
            lastPlaybackTranslationZ = newPlaybackTranslationZ
            binding.playbackSheet.translationZ = newPlaybackTranslationZ
        }

        if (queueSheetBehavior != null) {
            val queueRatio = max(queueSheetBehavior.calculateSlideOffset(), 0f)
            val queueInRatio = max(queueRatio - 0.5f, 0f) * 2

            val queueMaxXScaleDelta = maxScaleXDistance / binding.queueSheet.width
            val queueBackRatio =
                max(1 - ((1 - binding.queueSheet.scaleX) / queueMaxXScaleDelta), 0f)

            val queueEdgeRatio = max(queueRatio - 0.9f, 0f) / 0.1f

            val queueBarEdgeRatio = max(queueEdgeRatio - 0.5f, 0f) * 2
            val queueBarBackRatio = max(queueBackRatio - 0.5f, 0f) * 2
            val queueBarRatio = min(queueBarEdgeRatio * queueBarBackRatio, 1f)

            val queuePanelEdgeRatio = min(queueEdgeRatio * 2, 1f)
            val queuePanelBackRatio = min(queueBackRatio * 2, 1f)
            val queuePanelRatio = 1 - min(queuePanelEdgeRatio * queuePanelBackRatio, 1f)

            val newBarAlpha = max(playbackOutRatio, queueBarRatio)
            if (newBarAlpha != lastPlaybackBarAlpha) {
                lastPlaybackBarAlpha = newBarAlpha
                binding.playbackBarFragment.alpha = newBarAlpha
            }
            val newPanelAlpha = min(playbackInRatio, queuePanelRatio)
            if (newPanelAlpha != lastPlaybackPanelAlpha) {
                lastPlaybackPanelAlpha = newPanelAlpha
                binding.playbackPanelFragment.alpha = newPanelAlpha
            }
            if (queueInRatio != lastQueueFragmentAlpha) {
                lastQueueFragmentAlpha = queueInRatio
                binding.queueFragment.alpha = queueInRatio
            }

            if (playbackModel.song.value != null) {
                // Playback sheet intercepts queue sheet touch events, prevent that from
                // occurring by disabling dragging whenever the queue sheet is expanded.
                playbackSheetBehavior.isDraggable =
                    queueSheetBehavior.state == BackportBottomSheetBehavior.STATE_COLLAPSED
            }
        } else {
            // No queue sheet, fade normally based on the playback sheet
            if (playbackOutRatio != lastPlaybackBarAlpha) {
                lastPlaybackBarAlpha = playbackOutRatio
                binding.playbackBarFragment.alpha = playbackOutRatio
            }
            if (playbackInRatio != lastPlaybackPanelAlpha) {
                lastPlaybackPanelAlpha = playbackInRatio
                binding.playbackPanelFragment.alpha = playbackInRatio
            }
            val newTopRightCorner = normalCornerSize * (1 - playbackLastStretchRatio)
            if (newTopRightCorner != lastQueueSheetTopRightCorner) {
                lastQueueSheetTopRightCorner = newTopRightCorner
                (binding.queueSheet.background as MaterialShapeDrawable).shapeAppearanceModel =
                    ShapeAppearanceModel.builder()
                        .setTopLeftCornerSize(normalCornerSize)
                        .setTopRightCornerSize(newTopRightCorner)
                        .build()
            }
        }
        // Fade out the playback bar as the panel expands.
        binding.playbackBarFragment.apply {
            // Prevent interactions when the playback bar fully fades out.
            isInvisible = alpha == 0f
        }

        // Prevent interactions when the playback panel fully fades out.
        binding.playbackPanelFragment.isInvisible = binding.playbackPanelFragment.alpha == 0f

        binding.queueSheet.apply {
            // Queue sheet (not queue content) should fade out with the playback panel.
            if (playbackInRatio != lastQueueSheetAlpha) {
                lastQueueSheetAlpha = playbackInRatio
                alpha = playbackInRatio
            }
            // Prevent interactions when the queue sheet fully fades out.
            binding.queueSheet.isInvisible = alpha == 0f
        }

        // Prevent interactions when the queue content fully fades out.
        binding.queueFragment.isInvisible = binding.queueFragment.alpha == 0f

        if (playbackModel.song.value == null) {
            // Sometimes lingering drags can un-hide the playback sheet even when we intend to
            // hide it, make sure we keep it hidden.
            tryHideAllSheets()
        }

        // Since the navigation listener is also reliant on the bottom sheets, we must also update
        // it every frame.
        requireNotNull(sheetBackCallback) { "SheetBackPressedCallback was not available" }
            .invalidateEnabled()

        // Stop the FrameLayout containing the fabs from eating touch events elsewhere
        binding.mainFabContainer.isVisible =
            binding.homeNewPlaylistFab.mainFab.isVisible || binding.homeShuffleFab.isVisible

        return true
    }

    override fun onActionSelected(actionItem: SpeedDialActionItem): Boolean {
        when (actionItem.id) {
            R.id.action_new_playlist -> {
                L.d("Creating playlist")
                musicModel.createPlaylist()
            }
            R.id.action_import_playlist -> {
                L.d("Importing playlist")
                musicModel.importPlaylist()
            }
            else -> {}
        }
        // Returning false to close the speed dial results in no animation, manually close instead.
        // Adapted from Material Files: https://github.com/zhanghai/MaterialFiles
        requireBinding().homeNewPlaylistFab.close()
        return true
    }

    private fun onExploreNavigate() {
        listModel.dropSelection()
        updateFabVisibility(
            requireBinding(),
            homeModel.songList.value,
            homeModel.isFastScrolling.value,
            homeModel.currentTabType.value)
    }

    private fun updateCurrentTab(tabType: MusicType) {
        val binding = requireBinding()
        updateFabVisibility(
            binding, homeModel.songList.value, homeModel.isFastScrolling.value, tabType)
    }

    private fun updateIndexerState(state: IndexingState?) {
        if (state is IndexingState.Completed && state.error == null) {
            L.d("Received ok response")
            val binding = requireBinding()
            updateFabVisibility(
                binding,
                homeModel.songList.value,
                homeModel.isFastScrolling.value,
                homeModel.currentTabType.value)
        }
    }

    private fun updateFab(songs: List<Song>, isFastScrolling: Boolean) {
        val binding = requireBinding()
        updateFabVisibility(binding, songs, isFastScrolling, homeModel.currentTabType.value)
    }

    private fun updateFabVisibility(
        binding: FragmentMainBinding,
        songs: List<Song>,
        isFastScrolling: Boolean,
        tabType: MusicType
    ) {
        // If there are no songs, it's likely that the library has not been loaded, so
        // displaying the shuffle FAB makes no sense. We also don't want the fast scroll
        // popup to overlap with the FAB, so we hide the FAB when fast scrolling too.
        if (shouldHideAllFabs(binding, songs, isFastScrolling)) {
            L.d("Hiding fab: [empty: ${songs.isEmpty()} scrolling: $isFastScrolling]")
            forceHideAllFabs()
        } else {
            if (tabType != MusicType.PLAYLISTS) {
                if (binding.homeShuffleFab.isOrWillBeShown) {
                    return
                }

                if (binding.homeNewPlaylistFab.mainFab.isOrWillBeShown) {
                    L.d("Animating transition")
                    binding.homeNewPlaylistFab.hide(
                        object : FloatingActionButton.OnVisibilityChangedListener() {
                            override fun onHidden(fab: FloatingActionButton) {
                                super.onHidden(fab)
                                if (shouldHideAllFabs(
                                    binding,
                                    homeModel.songList.value,
                                    homeModel.isFastScrolling.value)) {
                                    return
                                }
                                binding.homeShuffleFab.show()
                            }
                        })
                } else {
                    L.d("Showing immediately")
                    binding.homeShuffleFab.show()
                }
            } else {
                L.d("Showing playlist button")
                if (binding.homeNewPlaylistFab.mainFab.isOrWillBeShown) {
                    return
                }

                if (binding.homeShuffleFab.isOrWillBeShown) {
                    L.d("Animating transition")
                    binding.homeShuffleFab.hide(
                        object : FloatingActionButton.OnVisibilityChangedListener() {
                            override fun onHidden(fab: FloatingActionButton) {
                                super.onHidden(fab)
                                if (shouldHideAllFabs(
                                    binding,
                                    homeModel.songList.value,
                                    homeModel.isFastScrolling.value)) {
                                    return
                                }
                                binding.homeNewPlaylistFab.show()
                            }
                        })
                } else {
                    L.d("Showing immediately")
                    binding.homeNewPlaylistFab.show()
                }
            }
        }
    }

    private fun shouldHideAllFabs(
        binding: FragmentMainBinding,
        songs: List<Song>,
        isFastScrolling: Boolean
    ) =
        binding.exploreNavHost.findNavController().currentDestination?.id != R.id.home_fragment ||
            sheetRising == true ||
            songs.isEmpty() ||
            isFastScrolling

    private fun forceHideAllFabs() {
        val binding = requireBinding()
        if (binding.homeShuffleFab.isOrWillBeShown) {
            FAB_HIDE_FROM_USER_FIELD.invoke(binding.homeShuffleFab, null, false)
        }
        if (binding.homeNewPlaylistFab.isOpen) {
            binding.homeNewPlaylistFab.close()
        }
        if (binding.homeNewPlaylistFab.mainFab.isOrWillBeShown) {
            FAB_HIDE_FROM_USER_FIELD.invoke(binding.homeNewPlaylistFab.mainFab, null, false)
        }
    }

    private fun updateSpeedDial(open: Boolean) {
        requireNotNull(speedDialBackCallback) { "SpeedDialBackPressedCallback was not available" }
            .invalidateEnabled(open)
        val binding = requireBinding()
        binding.mainScrim.isInvisible = !open
        binding.sheetScrim.isInvisible = !open
    }

    private fun handleShow(show: Show?) {
        when (show) {
            is Show.SongAlbumDetails,
            is Show.ArtistDetails,
            is Show.AlbumDetails -> playbackModel.openMain()
            is Show.SongDetails,
            is Show.SongArtistDecision,
            is Show.AlbumArtistDecision,
            is Show.GenreDetails,
            is Show.PlaylistDetails,
            null -> {}
        }
    }

    private fun handleShowOuter(outer: Outer?) {
        val directions =
            when (outer) {
                is Outer.Settings -> MainFragmentDirections.preferences()
                is Outer.About -> MainFragmentDirections.about()
                null -> return
            }
        findNavController().navigateSafe(directions)
        homeModel.showOuter.consume()
    }

    private fun updateSong(song: Song?) {
        if (song != null) {
            tryShowSheets()
        } else {
            tryHideAllSheets()
        }
    }

    private fun handlePanel(panel: OpenPanel?) {
        if (panel == null) return
        L.d("Trying to update panel to $panel")
        when (panel) {
            OpenPanel.MAIN -> tryClosePlaybackPanel()
            OpenPanel.PLAYBACK -> tryOpenPlaybackPanel()
            OpenPanel.QUEUE -> tryOpenQueuePanel()
        }
        playbackModel.openPanel.consume()
    }

    private fun tryOpenPlaybackPanel() {
        val binding = requireBinding()
        val playbackSheetBehavior =
            binding.playbackSheet.coordinatorLayoutBehavior as PlaybackBottomSheetBehavior

        if (playbackSheetBehavior.targetState == BackportBottomSheetBehavior.STATE_COLLAPSED) {
            // Playback sheet is not expanded and not hidden, we can expand it.
            L.d("Expanding playback sheet")
            playbackSheetBehavior.state = BackportBottomSheetBehavior.STATE_EXPANDED
            return
        }

        val queueSheetBehavior =
            (binding.queueSheet.coordinatorLayoutBehavior ?: return) as QueueBottomSheetBehavior
        if (playbackSheetBehavior.state == BackportBottomSheetBehavior.STATE_EXPANDED &&
            queueSheetBehavior.targetState == BackportBottomSheetBehavior.STATE_EXPANDED) {
            // Queue sheet and playback sheet is expanded, close the queue sheet so the
            // playback panel can shown.
            L.d("Collapsing queue sheet")
            queueSheetBehavior.state = BackportBottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun tryClosePlaybackPanel() {
        val binding = requireBinding()
        val playbackSheetBehavior =
            binding.playbackSheet.coordinatorLayoutBehavior as PlaybackBottomSheetBehavior
        if (playbackSheetBehavior.targetState == BackportBottomSheetBehavior.STATE_EXPANDED) {
            // Playback sheet (and possibly queue) needs to be collapsed.
            L.d("Collapsing playback and queue sheets")
            val queueSheetBehavior =
                binding.queueSheet.coordinatorLayoutBehavior as QueueBottomSheetBehavior?
            playbackSheetBehavior.state = BackportBottomSheetBehavior.STATE_COLLAPSED
            queueSheetBehavior?.state = BackportBottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun tryOpenQueuePanel() {
        val binding = requireBinding()
        val playbackSheetBehavior =
            binding.playbackSheet.coordinatorLayoutBehavior as PlaybackBottomSheetBehavior
        val queueSheetBehavior =
            (binding.queueSheet.coordinatorLayoutBehavior ?: return) as QueueBottomSheetBehavior
        if (playbackSheetBehavior.state == BackportBottomSheetBehavior.STATE_EXPANDED &&
            queueSheetBehavior.targetState == BackportBottomSheetBehavior.STATE_COLLAPSED) {
            // Playback sheet is expanded and queue sheet is collapsed, we can expand it.
            queueSheetBehavior.state = BackportBottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun tryShowSheets() {
        val binding = requireBinding()
        val playbackSheetBehavior =
            binding.playbackSheet.coordinatorLayoutBehavior as PlaybackBottomSheetBehavior
        if (playbackSheetBehavior.targetState == BackportBottomSheetBehavior.STATE_HIDDEN) {
            L.d("Unhiding and enabling playback sheet")
            val queueSheetBehavior =
                binding.queueSheet.coordinatorLayoutBehavior as QueueBottomSheetBehavior?
            // Queue sheet behavior is either collapsed or expanded, no hiding needed
            queueSheetBehavior?.isDraggable = true
            playbackSheetBehavior.apply {
                // Make sure the view is draggable, at least until the draw checks kick in.
                isDraggable = true
                state = BackportBottomSheetBehavior.STATE_COLLAPSED
            }
        }
    }

    private fun tryHideAllSheets() {
        val binding = requireBinding()
        val playbackSheetBehavior =
            binding.playbackSheet.coordinatorLayoutBehavior as PlaybackBottomSheetBehavior
        if (playbackSheetBehavior.targetState != BackportBottomSheetBehavior.STATE_HIDDEN) {
            val queueSheetBehavior =
                binding.queueSheet.coordinatorLayoutBehavior as QueueBottomSheetBehavior?

            L.d("Hiding and disabling playback and queue sheets")

            // Make both bottom sheets non-draggable so the user can't halt the hiding event.
            queueSheetBehavior?.apply {
                isDraggable = false
                state = BackportBottomSheetBehavior.STATE_COLLAPSED
            }

            playbackSheetBehavior.apply {
                isDraggable = false
                state = BackportBottomSheetBehavior.STATE_HIDDEN
            }
        }
    }

    private class SheetBackPressedCallback(
        private val playbackSheetBehavior: PlaybackBottomSheetBehavior<*>,
        private val queueSheetBehavior: QueueBottomSheetBehavior<*>?
    ) : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            if (queueSheetShown()) {
                unlikelyToBeNull(queueSheetBehavior).startBackProgress(backEvent)
            }

            if (playbackSheetShown()) {
                playbackSheetBehavior.startBackProgress(backEvent)
                return
            }
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            if (queueSheetShown()) {
                unlikelyToBeNull(queueSheetBehavior).updateBackProgress(backEvent)
                return
            }

            if (playbackSheetShown()) {
                playbackSheetBehavior.updateBackProgress(backEvent)
                return
            }
        }

        override fun handleOnBackPressed() {
            if (queueSheetShown()) {
                unlikelyToBeNull(queueSheetBehavior).handleBackInvoked()
                return
            }

            if (playbackSheetShown()) {
                playbackSheetBehavior.handleBackInvoked()
                return
            }
        }

        override fun handleOnBackCancelled() {
            if (queueSheetShown()) {
                unlikelyToBeNull(queueSheetBehavior).cancelBackProgress()
                return
            }

            if (playbackSheetShown()) {
                playbackSheetBehavior.cancelBackProgress()
                return
            }
        }

        fun invalidateEnabled() {
            isEnabled = queueSheetShown() || playbackSheetShown()
        }

        private fun playbackSheetShown() =
            playbackSheetBehavior.targetState != BackportBottomSheetBehavior.STATE_COLLAPSED &&
                playbackSheetBehavior.targetState != BackportBottomSheetBehavior.STATE_HIDDEN

        private fun queueSheetShown() =
            queueSheetBehavior != null &&
                playbackSheetBehavior.state == BackportBottomSheetBehavior.STATE_EXPANDED &&
                queueSheetBehavior.targetState != BackportBottomSheetBehavior.STATE_COLLAPSED
    }

    private class DetailBackPressedCallback(private val detailModel: DetailViewModel) :
        OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (detailModel.dropPlaylistEdit()) {
                L.d("Dropped playlist edits")
            }
        }

        fun invalidateEnabled(playlistEdit: List<Song>?) {
            isEnabled = playlistEdit != null
        }
    }

    private inner class SelectionBackPressedCallback(private val listModel: ListViewModel) :
        OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (listModel.dropSelection()) {
                L.d("Dropped selection")
            }
        }

        fun invalidateEnabled(selection: List<Music>) {
            isEnabled = selection.isNotEmpty()
        }
    }

    private inner class SpeedDialBackPressedCallback : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val binding = requireBinding()
            if (binding.homeNewPlaylistFab.isOpen) {
                binding.homeNewPlaylistFab.close()
            }
        }

        fun invalidateEnabled(open: Boolean) {
            isEnabled = open
        }
    }

    private companion object {
        val FAB_HIDE_FROM_USER_FIELD: Method by
            lazyReflectedMethod(
                FloatingActionButton::class,
                "hide",
                FloatingActionButton.OnVisibilityChangedListener::class,
                Boolean::class)

        // Menu item ids for the zone popup, kept clear of the 0-based value
        // indices used for the actual axis values.
        const val HEADER_ITEM_ID = -2
        const val ALL_ITEM_ID = -1
    }
}
