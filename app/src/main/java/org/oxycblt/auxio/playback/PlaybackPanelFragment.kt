/*
 * Copyright (c) 2021 Auxio Project
 * PlaybackPanelFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentPlaybackPanelBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.ui.ControlledCoverView
import org.oxycblt.auxio.playback.ui.StyledSeekBar
import org.oxycblt.auxio.plugin.similarity.ZoneAxis
import org.oxycblt.auxio.plugin.similarity.ZoneAxisValue
import org.oxycblt.auxio.plugin.similarity.ZoneAxisViewModel
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.systemBarInsetsCompat
import android.widget.ArrayAdapter
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A [ViewBindingFragment] more information about the currently playing song, alongside all
 * available controls.
 *
 * @author Alexander Capehart (OxygenCobalt)
 *
 * TODO: Improve flickering situation on play button
 */
@AndroidEntryPoint
class PlaybackPanelFragment :
    ViewBindingFragment<FragmentPlaybackPanelBinding>(),
    Toolbar.OnMenuItemClickListener,
    StyledSeekBar.Listener,
    ControlledCoverView.OnSwipeListener,
    ViewTreeObserver.OnGlobalLayoutListener {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val zoneModel: ZoneAxisViewModel by viewModels()
    private var equalizerLauncher: ActivityResultLauncher<Intent>? = null
    private var lastCoverWidth = 0

    // Cached current dropdown option lists, so a selection index maps back to a
    // value id. Index 0 is always the "unset" (—) entry.
    private var languageOptions: List<ZoneAxisValue> = emptyList()
    private var typeOptions: List<ZoneAxisValue> = emptyList()

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentPlaybackPanelBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentPlaybackPanelBinding,
        savedInstanceState: Bundle?
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        // AudioEffect expects you to use startActivityForResult with the panel intent. There is no
        // contract analogue for this intent, so the generic contract is used instead.
        equalizerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                // Nothing to do
            }

        // --- UI SETUP ---
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsetsCompat
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        binding.playbackToolbar.apply {
            setNavigationOnClickListener { playbackModel.openMain() }
            setOnMenuItemClickListener(this@PlaybackPanelFragment)
        }

        binding.playbackCover.onSwipeListener = this
        binding.playbackSong.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentSong() }
        }
        binding.playbackArtist.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentArtist() }
        }
        binding.playbackAlbum?.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentAlbum() }
        }

        binding.playbackSeekBar?.listener = this

        // Set up actions
        // TODO: Add better playback button accessibility
        binding.playbackRepeat.setOnClickListener { playbackModel.toggleRepeatMode() }
        binding.playbackSkipPrev.setOnClickListener { playbackModel.prev() }
        binding.playbackPlayPause.setOnClickListener { playbackModel.togglePlaying() }
        binding.playbackSkipNext.setOnClickListener { playbackModel.next() }
        binding.playbackShuffle.setOnClickListener { playbackModel.toggleShuffled() }
        binding.playbackMore?.setOnClickListener {
            playbackModel.song.value?.let {
                listModel.openMenu(R.menu.playback_song, it, PlaySong.ByItself)
            }
        }

        // --- VIEWMODEL SETUP --
        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(playbackModel.parent, ::updateParent)
        collectImmediately(playbackModel.positionDs, ::updatePosition)
        collectImmediately(playbackModel.repeatMode, ::updateRepeat)
        collectImmediately(playbackModel.isPlaying, ::updatePlaying)
        collectImmediately(playbackModel.isShuffled, ::updateShuffled)

        // --- ZONE AXIS (opt-in) ---
        setUpZoneAxis(binding)
    }

    private fun setUpZoneAxis(binding: FragmentPlaybackPanelBinding) {
        // Not present on shorter-height layout variants; nothing to wire up.
        val row = binding.playbackZoneRow ?: return
        // Entire row is hidden unless the Zone Axis plugin is enabled.
        if (!zoneModel.enabled) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE

        binding.playbackZoneLanguage?.setOnItemClickListener { _, _, position, _ ->
            // position 0 == "unset"; otherwise map to the value at position-1.
            val valueId = languageOptions.getOrNull(position - 1)?.id
            zoneModel.assignLanguage(valueId)
        }
        binding.playbackZoneType?.setOnItemClickListener { _, _, position, _ ->
            val valueId = typeOptions.getOrNull(position - 1)?.id
            zoneModel.assignType(valueId)
        }

        collectImmediately(zoneModel.languageValues) { values ->
            languageOptions = values
            populateDropdown(binding, isLanguage = true)
        }
        collectImmediately(zoneModel.typeValues) { values ->
            typeOptions = values
            populateDropdown(binding, isLanguage = false)
        }
        collectImmediately(zoneModel.currentTag) { applyCurrentTag(binding) }
    }

    private fun populateDropdown(binding: FragmentPlaybackPanelBinding, isLanguage: Boolean) {
        val context = requireContext()
        val unset = getString(R.string.lbl_zone_unset)
        val options = if (isLanguage) languageOptions else typeOptions
        val labels = listOf(unset) + options.map { it.label }
        val adapter =
            ArrayAdapter(context, android.R.layout.simple_list_item_1, labels)
        val view = if (isLanguage) binding.playbackZoneLanguage else binding.playbackZoneType
        view?.setAdapter(adapter)
        applyCurrentTag(binding)
    }

    private fun applyCurrentTag(binding: FragmentPlaybackPanelBinding) {
        val tag = zoneModel.currentTag.value
        val unset = getString(R.string.lbl_zone_unset)
        val langLabel =
            languageOptions.firstOrNull { it.id == tag?.languageValueId }?.label ?: unset
        val typeLabel = typeOptions.firstOrNull { it.id == tag?.typeValueId }?.label ?: unset
        // setText(..., false) updates the shown value without filtering the list.
        binding.playbackZoneLanguage?.setText(langLabel, false)
        binding.playbackZoneType?.setText(typeLabel, false)
    }

    override fun onStart() {
        super.onStart()
        playbackModel.song.value?.let { requireBinding().playbackCover.bind(it) }
        requireBinding().root.viewTreeObserver.addOnGlobalLayoutListener(this)
    }

    override fun onStop() {
        super.onStop()
        requireBinding().root.viewTreeObserver.removeOnGlobalLayoutListener(this)
    }

    override fun onGlobalLayout() {
        if (binding == null || lastCoverWidth < 0) {
            return
        }
        // Hacky workaround for cover radius not being preserved in between sizing changes
        // (i.e split screen or landscape mode)
        // For some reason ConstraintLayout does several passes on 1:1 elements that causes their
        // size to radically change, so we wait until it stabilizes and then force an image
        // reload if needed. Optimistically this is a no-op from coil caching, but when the cover
        // did accidentally load the wrong image (with weird corner radius intended for bigger
        // covers) we can force it to reload.
        // If this breaks, it's fine since we also started a load as we normally did w/state
        // updates, so the cover will not break.
        val binding = requireBinding()
        val coverWidth = binding.playbackCover.width
        if (lastCoverWidth != coverWidth) {
            lastCoverWidth = coverWidth
        } else {
            playbackModel.song.value?.let { binding.playbackCover.bind(it) }
            lastCoverWidth = -1
        }
    }

    override fun onDestroyBinding(binding: FragmentPlaybackPanelBinding) {
        equalizerLauncher = null
        binding.playbackSong.isSelected = false
        binding.playbackArtist.isSelected = false
        binding.playbackAlbum?.isSelected = false
        binding.playbackToolbar.setOnMenuItemClickListener(null)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_open_equalizer) {
            // Launch the system equalizer app, if possible.
            L.d("Launching equalizer")
            val equalizerIntent =
                Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    // Provide audio session ID so the equalizer can show options for this app
                    // in particular.
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, playbackModel.currentAudioSessionId)
                    // Signal music type so that the equalizer settings are appropriate for
                    // music playback.
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            try {
                requireNotNull(equalizerLauncher) { "Equalizer panel launcher was not available" }
                    .launch(equalizerIntent)
            } catch (e: ActivityNotFoundException) {
                requireContext().showToast(R.string.err_no_app)
            }
            return true
        }

        return false
    }

    override fun onSeekConfirmed(positionDs: Long) {
        playbackModel.seekTo(positionDs)
    }

    override fun onSwipePrevious() {
        playbackModel.prev()
    }

    override fun onSwipeNext() {
        playbackModel.next()
    }

    override fun onStepBack() {
        playbackModel.stepBack()
    }

    override fun onStepForward() {
        playbackModel.stepForward()
    }

    private fun updateSong(song: Song?) {
        if (song == null) {
            // Nothing to do.
            return
        }

        val binding = requireBinding()
        val context = requireContext()
        L.d("Updating song display: $song")
        binding.playbackCover.bind(song)
        binding.playbackSong.text = song.name.resolve(context)
        binding.playbackArtist.text = song.artists.resolveNames(context)
        binding.playbackAlbum?.text = song.album.name.resolve(context)
        binding.playbackSeekBar?.durationDs = song.durationMs.msToDs()
        // Refresh the zone dropdowns for the new song (no-op if plugin disabled).
        if (zoneModel.enabled) {
            zoneModel.onSongChanged(song)
        }
    }

    private fun updateParent(parent: MusicParent?) {
        val binding = requireBinding()
        val context = requireContext()
        binding.playbackToolbar.subtitle =
            parent?.run { name.resolve(context) } ?: context.getString(R.string.lbl_all_songs)
    }

    private fun updatePosition(positionDs: Long) {
        requireBinding().playbackSeekBar?.positionDs = positionDs
    }

    private fun updateRepeat(repeatMode: RepeatMode) {
        requireBinding().playbackRepeat.apply {
            setIconResource(repeatMode.icon)
            isActivated = repeatMode != RepeatMode.NONE
        }
    }

    private fun updatePlaying(isPlaying: Boolean) {
        requireBinding().playbackPlayPause.isActivated = isPlaying
    }

    private fun updateShuffled(isShuffled: Boolean) {
        requireBinding().playbackShuffle.isActivated = isShuffled
    }

    private fun navigateToCurrentSong() {
        playbackModel.song.value?.let(detailModel::showAlbum)
    }

    private fun navigateToCurrentArtist() {
        playbackModel.song.value?.let(detailModel::showArtist)
    }

    private fun navigateToCurrentAlbum() {
        playbackModel.song.value?.let { detailModel.showAlbum(it.album) }
    }
}
