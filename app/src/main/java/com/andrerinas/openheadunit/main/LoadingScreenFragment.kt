package com.andrerinas.openheadunit.main

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Switch
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.PickMediaContract
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SettingsBackupManager
import com.andrerinas.openheadunit.utils.ToastUtils
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LoadingScreenFragment : Fragment() {

    private lateinit var settings: Settings

    private var previewArea: FrameLayout? = null
    private var previewPlaceholder: View? = null
    private var previewImage: ImageView? = null
    private var previewStatusText: View? = null
    private var toggleContainer: View? = null
    private var toggleShowText: Switch? = null
    private var toggleShowPill: Switch? = null
    private var toggleKeepAspectRatio: Switch? = null
    private var toggleLoopContainer: View? = null
    private var toggleLoopVideo: Switch? = null
    private var btnRemove: View? = null
    private var fullscreenOverlay: FrameLayout? = null
    private var fullscreenImage: ImageView? = null
    private var fullscreenStatusText: View? = null
    private var sliderScale: SeekBar? = null
    private var txtScaleValue: TextView? = null

    // Video playback managed programmatically (not in XML to avoid inflation issues)
    private var previewMediaPlayer: MediaPlayer? = null
    private var fullscreenMediaPlayer: MediaPlayer? = null

    // SurfaceView added to preview_area at runtime when the loaded media is a
    // video. Tracked so we can remove it on media swap / fragment teardown.
    private var previewSurfaceView: SurfaceView? = null

    // Ken Burns scale animator for static-image previews. Mirrors the live
    // loading screen so the user sees what AAP will actually render.
    private var previewKenBurnsAnimator: ObjectAnimator? = null

    private val filePicker = registerForActivityResult(PickMediaContract()) { uri ->
        uri?.let { handleFileSelected(it) }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showFallbackMediaPicker(useDownloads = true)
        } else {
            ToastUtils.showToast(context, R.string.storage_permission_denied_backup, Toast.LENGTH_LONG, force = true)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_loading_screen, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            setupViews(view)
        } catch (e: Exception) {
            AppLog.e("LoadingScreenFragment setup failed: ${e.message}")
            ToastUtils.showToast(context, R.string.loading_screen_file_error, Toast.LENGTH_SHORT, force = true)
            navigateBack()
        }
    }

    private fun setupViews(view: View) {
        settings = App.provide(requireContext()).settings

        previewArea = view.findViewById(R.id.preview_area)
        previewPlaceholder = view.findViewById(R.id.preview_placeholder)
        previewImage = view.findViewById(R.id.preview_image)
        previewStatusText = view.findViewById(R.id.preview_status_text)
        toggleContainer = view.findViewById(R.id.toggle_container)
        toggleShowText = view.findViewById(R.id.toggle_show_text)
        toggleShowPill = view.findViewById(R.id.toggle_show_pill)
        btnRemove = view.findViewById(R.id.btn_remove)
        fullscreenOverlay = view.findViewById(R.id.fullscreen_overlay)
        fullscreenImage = view.findViewById(R.id.fullscreen_image)
        fullscreenStatusText = view.findViewById(R.id.fullscreen_status_text)

        // Resolution and aspect ratio recommendation
        try {
            val dm = resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            view.findViewById<TextView>(R.id.recommendation_text)?.text =
                getString(R.string.loading_screen_recommendation, w, h)

            val gcd = gcd(w, h)
            val ratioW = w / gcd
            val ratioH = h / gcd
            view.findViewById<TextView>(R.id.aspect_ratio_text)?.text =
                getString(R.string.loading_screen_recommended_aspect_ratio, ratioW, ratioH)
        } catch (_: Exception) {}

        // Toolbar
        view.findViewById<MaterialToolbar>(R.id.toolbar)?.setNavigationOnClickListener {
            navigateBack()
        }

        // Back press
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenOverlay?.visibility == View.VISIBLE) {
                    hideFullscreen()
                } else {
                    navigateBack()
                }
            }
        })

        toggleKeepAspectRatio = view.findViewById(R.id.toggle_keep_aspect_ratio)
        toggleLoopContainer = view.findViewById(R.id.toggle_loop_container)
        toggleLoopVideo = view.findViewById(R.id.toggle_loop_video)

        sliderScale = view.findViewById<SeekBar>(R.id.slider_scale)
        txtScaleValue = view.findViewById<TextView>(R.id.txt_scale_value)

        val currentScale = settings.loadingScreenScalePercent
        txtScaleValue?.text = "$currentScale%"
        sliderScale?.progress = currentScale
        sliderScale?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                var actualProgress = progress
                if (actualProgress < 10) {
                    actualProgress = 10
                    if (fromUser) {
                        sliderScale?.progress = 10
                        return
                    }
                }
                settings.loadingScreenScalePercent = actualProgress
                txtScaleValue?.text = "$actualProgress%"
                applyPreviewScale()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Toggles
        toggleShowPill?.isChecked = settings.loadingScreenShowPill
        toggleShowPill?.setOnCheckedChangeListener { _, isChecked ->
            settings.loadingScreenShowPill = isChecked
        }

        toggleShowText?.isChecked = settings.loadingScreenShowText
        toggleShowText?.setOnCheckedChangeListener { _, isChecked ->
            settings.loadingScreenShowText = isChecked
            updateStatusTextVisibility()
        }

        toggleKeepAspectRatio?.isChecked = settings.loadingScreenKeepAspectRatio
        toggleKeepAspectRatio?.setOnCheckedChangeListener { _, isChecked ->
            settings.loadingScreenKeepAspectRatio = isChecked
            updatePreviewScaleType()
            val path = settings.loadingScreenMediaPath
            val type = settings.loadingScreenMediaType
            if (path.isNotEmpty() && type.isNotEmpty()) {
                loadImagePreview(path, type)
            }
        }

        toggleLoopVideo?.isChecked = settings.loadingScreenLoopVideo
        toggleLoopVideo?.setOnCheckedChangeListener { _, isChecked ->
            settings.loadingScreenLoopVideo = isChecked
        }

        // Select file
        view.findViewById<View>(R.id.btn_select_file)?.setOnClickListener {
            try {
                filePicker.launch(Unit)
            } catch (e: android.content.ActivityNotFoundException) {
                AppLog.e("File picker failed (no activity): ${e.message}")
                showNoFilePickerDialog()
            } catch (e: Exception) {
                AppLog.e("File picker failed: ${e.message}")
                ToastUtils.showToast(context, R.string.loading_screen_file_error, Toast.LENGTH_SHORT, force = true)
            }
        }

        // Remove
        btnRemove?.setOnClickListener { removeMedia() }

        // Preview tap → fullscreen
        previewArea?.setOnClickListener {
            if (settings.loadingScreenMediaPath.isNotEmpty()) {
                showFullscreen()
            }
        }

        // Fullscreen tap → close
        fullscreenOverlay?.setOnClickListener { hideFullscreen() }

        refreshUI()
    }

    override fun onPause() {
        super.onPause()
        releaseMediaPlayers()
    }

    override fun onDestroyView() {
        releaseMediaPlayers()
        super.onDestroyView()
    }

    private fun navigateBack() {
        try {
            if (!findNavController().navigateUp()) {
                requireActivity().finish()
            }
        } catch (_: Exception) {
            try { requireActivity().finish() } catch (_: Exception) {}
        }
    }

    // --- UI State ---

    private fun refreshUI() {
        val path = settings.loadingScreenMediaPath
        val type = settings.loadingScreenMediaType
        val file = if (path.isNotEmpty()) File(path) else null
        val hasMedia = file != null && file.exists() && type.isNotEmpty()

        val fullscreenHint = view?.findViewById<View>(R.id.fullscreen_hint)

        if (hasMedia) {
            previewPlaceholder?.visibility = View.GONE
            toggleContainer?.visibility = View.VISIBLE
            btnRemove?.visibility = View.VISIBLE
            fullscreenHint?.visibility = View.VISIBLE
            toggleLoopContainer?.visibility = if (type == "video") View.VISIBLE else View.GONE
            loadImagePreview(path, type)
        } else {
            previewPlaceholder?.visibility = View.VISIBLE
            previewImage?.visibility = View.GONE
            toggleContainer?.visibility = View.GONE
            btnRemove?.visibility = View.GONE
            fullscreenHint?.visibility = View.GONE
            toggleLoopContainer?.visibility = View.GONE
            if (path.isNotEmpty()) {
                settings.loadingScreenMediaPath = ""
                settings.loadingScreenMediaType = ""
            }
        }
        updateStatusTextVisibility()
        updatePreviewScaleType()
    }

    private fun updatePreviewScaleType() {
        val keepRatio = settings.loadingScreenKeepAspectRatio
        previewImage?.scaleType = if (keepRatio) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY
        fullscreenImage?.scaleType = if (keepRatio) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY
    }

    private fun updateStatusTextVisibility() {
        val hasMedia = settings.loadingScreenMediaPath.isNotEmpty()
        val show = hasMedia && settings.loadingScreenShowText
        previewStatusText?.visibility = if (show) View.VISIBLE else View.GONE
        fullscreenStatusText?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadImagePreview(path: String, type: String) {
        val file = File(path)
        if (!file.exists()) return

        // Tear down whatever the previous load left behind (SurfaceView,
        // MediaPlayer, animator) so we don't stack media on top of each other.
        clearPreviewMedia()

        val keepRatio = settings.loadingScreenKeepAspectRatio
        previewImage?.scaleType = if (keepRatio) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY

        try {
            when (type) {
                "video" -> {
                    // Hide the ImageView and play the video on a dynamically
                    // added SurfaceView, mirroring setupFullscreenVideo. Glide
                    // would otherwise only show a static first-frame thumbnail.
                    previewImage?.visibility = View.GONE
                    setupPreviewVideo(file)
                }
                "gif" -> {
                    previewImage?.visibility = View.VISIBLE
                    previewImage?.let { Glide.with(this).asGif().load(file).into(it) }
                    applyPreviewScale()
                }
                else -> {
                    previewImage?.visibility = View.VISIBLE
                    previewImage?.let { Glide.with(this).load(file).into(it) }
                    applyPreviewScale()
                    // Ken Burns matches the live loading screen for static
                    // images, so the user actually sees the slow zoom they're
                    // configuring rather than a frozen frame.
                    if (keepRatio) {
                        previewImage?.let { imageView ->
                            previewKenBurnsAnimator?.cancel()
                            val anim = ObjectAnimator.ofPropertyValuesHolder(
                                imageView,
                                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.05f),
                                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.05f)
                            ).apply {
                                duration = 8000
                                repeatMode = ObjectAnimator.REVERSE
                                repeatCount = ObjectAnimator.INFINITE
                                start()
                            }
                            previewKenBurnsAnimator = anim
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("Failed to load preview: ${e.message}")
            previewImage?.visibility = View.GONE
            previewPlaceholder?.visibility = View.VISIBLE
        }
    }

    private fun setupPreviewVideo(file: File) {
        val area = previewArea ?: return
        try {
            val surfaceView = SurfaceView(requireContext())
            surfaceView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Insert below the status-text overlay so the spinner+label still
            // float on top when the user enables show-text.
            area.addView(surfaceView, 0)
            previewSurfaceView = surfaceView

            val mp = MediaPlayer()
            previewMediaPlayer = mp
            mp.setDataSource(file.absolutePath)
            mp.isLooping = settings.loadingScreenLoopVideo
            mp.setVolume(0f, 0f)

            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    try {
                        mp.setDisplay(holder)
                        mp.prepareAsync()
                    } catch (_: Exception) {}
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    try { mp.setDisplay(null) } catch (_: Exception) {}
                }
            })

            mp.setOnPreparedListener { player ->
                applyPreviewScale()
                try { player.start() } catch (_: Exception) {}
            }
            mp.setOnErrorListener { _, _, _ ->
                AppLog.e("Preview video error")
                true
            }
        } catch (e: Exception) {
            AppLog.e("Preview video setup failed: ${e.message}")
            // Fall back to placeholder if the video can't be initialised.
            previewImage?.visibility = View.GONE
            previewPlaceholder?.visibility = View.VISIBLE
        }
    }

    private fun clearPreviewMedia() {
        previewKenBurnsAnimator?.cancel()
        previewKenBurnsAnimator = null
        previewImage?.scaleX = 1f
        previewImage?.scaleY = 1f

        try {
            previewMediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            previewMediaPlayer?.release()
        } catch (_: Exception) {}
        previewMediaPlayer = null

        previewSurfaceView?.let { sv ->
            try { previewArea?.removeView(sv) } catch (_: Exception) {}
        }
        previewSurfaceView = null
    }

    // --- File Selection ---

    private fun handleFileSelected(uri: Uri) {
        val ctx = context ?: return
        val contentResolver = ctx.contentResolver

        var mimeType = contentResolver.getType(uri)
        if (mimeType == null) {
            val path = uri.path
            if (path != null) {
                val ext = File(path).extension.lowercase()
                mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                if (mimeType == null) {
                    mimeType = when (ext) {
                        "png", "jpg", "jpeg", "webp", "bmp" -> "image/$ext"
                        "gif" -> "image/gif"
                        "mp4", "mkv", "webm", "3gp" -> "video/$ext"
                        else -> null
                    }
                }
            }
        }

        val mediaType = when {
            mimeType == null -> null
            mimeType == "image/gif" -> "gif"
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("video/") -> "video"
            else -> null
        }
        if (mediaType == null) {
            ToastUtils.showToast(ctx, R.string.loading_screen_unsupported_format, Toast.LENGTH_SHORT, force = true)
            return
        }

        val ext = when (mimeType) {
            "image/gif" -> "gif"; "image/png" -> "png"; "image/jpeg" -> "jpg"
            "image/webp" -> "webp"; "image/bmp" -> "bmp"
            "video/mp4" -> "mp4"; "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"; "video/3gpp" -> "3gp"
            else -> if (mimeType?.startsWith("video/") == true) "mp4" else "img"
        }

        val dir = File(ctx.filesDir, "loading_media")
        dir.listFiles()?.forEach { it.delete() }
        val destFile = File(dir, "loading_screen_${System.currentTimeMillis()}.$ext")
        // The media file can be up to 10 MB and may live on slow storage (SD
        // card, cloud-backed document provider). Run the size probe, the
        // previous-media cleanup and the actual copy on Dispatchers.IO so the
        // main thread doesn't stall and trigger an ANR. Result codes are
        // brought back to the main thread to update settings + UI.
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val size = if (uri.scheme == "file") {
                        uri.path?.let { File(it).length() } ?: -1L
                    } else {
                        try {
                            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                        } catch (_: Exception) { -1L }
                    }
                    if (size > 10L * 1024 * 1024) return@withContext CopyOutcome.TOO_LARGE

                    if (!dir.exists()) dir.mkdirs()
                    dir.listFiles()?.forEach { it.delete() }

                    if (uri.scheme == "file") {
                        val srcFile = uri.path?.let { File(it) }
                        if (srcFile != null && srcFile.exists()) {
                            srcFile.inputStream().use { input ->
                                destFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        } else return@withContext CopyOutcome.FAILED
                    } else {
                        contentResolver.openInputStream(uri)?.use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: return@withContext CopyOutcome.FAILED
                    }
                    CopyOutcome.OK
                } catch (e: Exception) {
                    AppLog.e("Copy failed: ${e.message}")
                    CopyOutcome.FAILED
                }
            }

            when (outcome) {
                CopyOutcome.TOO_LARGE -> {
                    ToastUtils.showToast(ctx, R.string.loading_screen_file_too_large, Toast.LENGTH_SHORT, force = true)
                }
                CopyOutcome.FAILED -> {
                    ToastUtils.showToast(ctx, R.string.loading_screen_file_error, Toast.LENGTH_SHORT, force = true)
                }
                CopyOutcome.OK -> {
                    settings.loadingScreenMediaPath = destFile.absolutePath
                    settings.loadingScreenMediaType = mediaType

                    releaseMediaPlayers()
                    try { previewImage?.let { Glide.with(this@LoadingScreenFragment).clear(it) } } catch (_: Exception) {}
                    refreshUI()
                }
            }
        }
    }

    private fun showNoFilePickerDialog() {
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.no_file_picker_title)
            .setMessage(R.string.no_file_picker_message)
            .setNegativeButton(R.string.cancel, null)

        val hasDownloadsAccess = SettingsBackupManager.canAccessDownloadsDirectory()
        if (hasDownloadsAccess) {
            builder.setPositiveButton(R.string.import_settings_downloads) { _, _ ->
                runWithStoragePermission {
                    showFallbackMediaPicker(useDownloads = true)
                }
            }
            builder.setNeutralButton(R.string.import_settings_app_folder) { _, _ ->
                showFallbackMediaPicker(useDownloads = false)
            }
        } else {
            builder.setPositiveButton(R.string.import_settings_app_folder) { _, _ ->
                showFallbackMediaPicker(useDownloads = false)
            }
        }
        builder.show()
    }

    private fun runWithStoragePermission(onGranted: () -> Unit) {
        val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            storagePermissionLauncher.launch(permission)
        }
    }

    private fun showFallbackMediaPicker(useDownloads: Boolean) {
        val ctx = context ?: return
        val dir = if (useDownloads) {
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        } else {
            val appDir = File(ctx.getExternalFilesDir(null), "loading_media")
            if (!appDir.exists()) appDir.mkdirs()
            appDir
        }

        val mediaFiles = try {
            dir.listFiles()?.filter { file ->
                file.isFile && (
                    file.name.endsWith(".png", ignoreCase = true) ||
                    file.name.endsWith(".jpg", ignoreCase = true) ||
                    file.name.endsWith(".jpeg", ignoreCase = true) ||
                    file.name.endsWith(".webp", ignoreCase = true) ||
                    file.name.endsWith(".gif", ignoreCase = true) ||
                    file.name.endsWith(".mp4", ignoreCase = true) ||
                    file.name.endsWith(".mkv", ignoreCase = true) ||
                    file.name.endsWith(".webm", ignoreCase = true) ||
                    file.name.endsWith(".3gp", ignoreCase = true)
                )
            }?.sortedByDescending { it.lastModified() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        if (mediaFiles.isEmpty()) {
            val isGerman = java.util.Locale.getDefault().language == "de"
            val message = if (useDownloads) {
                if (isGerman) {
                    "Keine Bilder oder Videos im Downloads-Ordner gefunden:\n${dir.absolutePath}\n\nBitte kopiere deine Datei in dieses Verzeichnis."
                } else {
                    "No images or videos found in Downloads folder:\n${dir.absolutePath}\n\nPlease copy your file to this directory."
                }
            } else {
                if (isGerman) {
                    "Keine Mediendateien im App-Ordner gefunden:\n${dir.absolutePath}\n\nBitte kopiere dein Startbild/Video in dieses Verzeichnis."
                } else {
                    "No media files found in app folder:\n${dir.absolutePath}\n\nPlease copy your startup image/video to this directory."
                }
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
                .setTitle("No files found")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val fileNames = mediaFiles.map { it.name }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
            .setTitle(if (useDownloads) R.string.import_settings_downloads else R.string.import_settings_app_folder)
            .setItems(fileNames) { _, which ->
                val selectedFile = mediaFiles[which]
                handleFileSelected(Uri.fromFile(selectedFile))
            }
            .show()
    }

    private enum class CopyOutcome { OK, TOO_LARGE, FAILED }

    private fun removeMedia() {
        val path = settings.loadingScreenMediaPath
        if (path.isNotEmpty()) {
            try { File(path).delete() } catch (_: Exception) {}
        }
        settings.loadingScreenMediaPath = ""
        settings.loadingScreenMediaType = ""
        settings.loadingScreenShowText = false

        releaseMediaPlayers()
        try {
            previewImage?.let { Glide.with(this).clear(it) }
            fullscreenImage?.let { Glide.with(this).clear(it) }
        } catch (_: Exception) {}
        refreshUI()
    }

    // --- Fullscreen Preview ---

    private fun showFullscreen() {
        val path = settings.loadingScreenMediaPath
        val type = settings.loadingScreenMediaType
        if (path.isEmpty() || type.isEmpty()) return

        val file = File(path)
        if (!file.exists()) return

        fullscreenOverlay?.visibility = View.VISIBLE
        fullscreenOverlay?.alpha = 0f
        fullscreenOverlay?.animate()?.alpha(1f)?.setDuration(200)?.start()

        // Apply aspect ratio setting to fullscreen image
        val keepRatio = settings.loadingScreenKeepAspectRatio
        fullscreenImage?.scaleType = if (keepRatio) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY

        try {
            if (type == "video") {
                fullscreenImage?.visibility = View.GONE
                setupFullscreenVideo(file)
            } else {
                fullscreenImage?.visibility = View.VISIBLE
                if (type == "gif") {
                    Glide.with(this).asGif().load(file).into(fullscreenImage!!)
                } else {
                    Glide.with(this).load(file).into(fullscreenImage!!)
                    // Ken Burns for static images
                    val anim = ObjectAnimator.ofPropertyValuesHolder(
                        fullscreenImage!!,
                        PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.05f),
                        PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.05f)
                    ).apply {
                        duration = 8000
                        repeatMode = ObjectAnimator.REVERSE
                        repeatCount = ObjectAnimator.INFINITE
                        start()
                    }
                    fullscreenImage?.tag = anim
                }
                fullscreenOverlay?.post { applyFullscreenScale() }
            }
        } catch (e: Exception) {
            AppLog.e("Fullscreen preview failed: ${e.message}")
            hideFullscreen()
        }

        updateStatusTextVisibility()
    }

    private fun setupFullscreenVideo(file: File) {
        try {
            val surfaceView = SurfaceView(requireContext())
            surfaceView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            fullscreenOverlay?.addView(surfaceView, 0)

            val mp = MediaPlayer()
            fullscreenMediaPlayer = mp
            mp.setDataSource(file.absolutePath)
            mp.isLooping = settings.loadingScreenLoopVideo
            mp.setVolume(0f, 0f)

            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    try {
                        mp.setDisplay(holder)
                        mp.prepareAsync()
                    } catch (_: Exception) {}
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    try { mp.setDisplay(null) } catch (_: Exception) {}
                }
            })

            mp.setOnPreparedListener { player ->
                applyFullscreenScale()
                player.start()
            }
            mp.setOnErrorListener { _, _, _ ->
                AppLog.e("Fullscreen video error")
                true
            }
        } catch (e: Exception) {
            AppLog.e("Video setup failed: ${e.message}")
        }
    }

    private fun hideFullscreen() {
        fullscreenOverlay?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
            fullscreenOverlay?.visibility = View.GONE
            // Release fullscreen media player
            releaseFullscreenMediaPlayer()
            // Remove any dynamically added SurfaceView
            val overlay = fullscreenOverlay ?: return@withEndAction
            for (i in overlay.childCount - 1 downTo 0) {
                val child = overlay.getChildAt(i)
                if (child is SurfaceView) overlay.removeView(child)
            }
            // Cancel Ken Burns
            (fullscreenImage?.tag as? ObjectAnimator)?.cancel()
            fullscreenImage?.scaleX = 1f
            fullscreenImage?.scaleY = 1f
            try { fullscreenImage?.let { Glide.with(this@LoadingScreenFragment).clear(it) } } catch (_: Exception) {}
        }?.start()
    }

    private fun applyPreviewScale() {
        val area = previewArea ?: return
        val scale = settings.loadingScreenScalePercent / 100f
        val cw = area.width
        val ch = area.height
        if (cw <= 0 || ch <= 0) return

        previewImage?.let { iv ->
            val lp = iv.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                lp.width = (cw * scale).toInt()
                lp.height = (ch * scale).toInt()
                lp.gravity = android.view.Gravity.CENTER
                iv.layoutParams = lp
            }
        }

        previewSurfaceView?.let { sv ->
            val lp = sv.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                val mp = previewMediaPlayer
                val keepRatio = settings.loadingScreenKeepAspectRatio
                if (keepRatio && mp != null) {
                    try {
                        val vw = mp.videoWidth
                        val vh = mp.videoHeight
                        if (vw > 0 && vh > 0) {
                            val videoRatio = vw.toFloat() / vh
                            val containerRatio = cw.toFloat() / ch
                            val baseWidth: Int
                            val baseHeight: Int
                            if (videoRatio > containerRatio) {
                                baseWidth = cw
                                baseHeight = (cw / videoRatio).toInt()
                            } else {
                                baseHeight = ch
                                baseWidth = (ch * videoRatio).toInt()
                            }
                            lp.width = (baseWidth * scale).toInt()
                            lp.height = (baseHeight * scale).toInt()
                        }
                    } catch (_: Exception) {}
                } else {
                    lp.width = (cw * scale).toInt()
                    lp.height = (ch * scale).toInt()
                }
                lp.gravity = android.view.Gravity.CENTER
                sv.layoutParams = lp
            }
        }
    }

    private fun applyFullscreenScale() {
        val overlay = fullscreenOverlay ?: return
        val scale = settings.loadingScreenScalePercent / 100f
        val cw = overlay.width
        val ch = overlay.height
        if (cw <= 0 || ch <= 0) return

        fullscreenImage?.let { iv ->
            val lp = iv.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                lp.width = (cw * scale).toInt()
                lp.height = (ch * scale).toInt()
                lp.gravity = android.view.Gravity.CENTER
                iv.layoutParams = lp
            }
        }

        for (i in 0 until overlay.childCount) {
            val child = overlay.getChildAt(i)
            if (child is SurfaceView) {
                val lp = child.layoutParams as? FrameLayout.LayoutParams
                if (lp != null) {
                    val mp = fullscreenMediaPlayer
                    val keepRatio = settings.loadingScreenKeepAspectRatio
                    if (keepRatio && mp != null) {
                        try {
                            val vw = mp.videoWidth
                            val vh = mp.videoHeight
                            if (vw > 0 && vh > 0) {
                                val videoRatio = vw.toFloat() / vh
                                val containerRatio = cw.toFloat() / ch
                                val baseWidth: Int
                                val baseHeight: Int
                                if (videoRatio > containerRatio) {
                                    baseWidth = cw
                                    baseHeight = (cw / videoRatio).toInt()
                                } else {
                                    baseHeight = ch
                                    baseWidth = (ch * videoRatio).toInt()
                                }
                                lp.width = (baseWidth * scale).toInt()
                                lp.height = (baseHeight * scale).toInt()
                            }
                        } catch (_: Exception) {}
                    } else {
                        lp.width = (cw * scale).toInt()
                        lp.height = (ch * scale).toInt()
                    }
                    lp.gravity = android.view.Gravity.CENTER
                    child.layoutParams = lp
                }
            }
        }
    }

    private fun releaseMediaPlayers() {
        releaseFullscreenMediaPlayer()
        clearPreviewMedia()
    }

    private fun releaseFullscreenMediaPlayer() {
        try {
            fullscreenMediaPlayer?.release()
            fullscreenMediaPlayer = null
        } catch (_: Exception) {}
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
