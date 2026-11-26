package `is`.xyz.mpv

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import `is`.xyz.mpv.ui.PotatoTheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Hosts the player UI (PlayScreen) for a single file/URI handed in via Intent.
 * This Activity is the SOLE owner of requestedOrientation and window/system UI settings —
 * PlayerViewModel and PlayScreen must never set them directly. OrientationController resolves policy;
 * this class only applies the result.
 */
class PlayerActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels {
        PlayerViewModel.factory(applicationContext)
    }

    private val orientationController = OrientationController()

    private var areControlsVisible: Boolean = true
    private val isPipMode = mutableStateOf(false)
    private var hideSystemBarsJob: kotlinx.coroutines.Job? = null

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_PLAY_PAUSE) {
                viewModel.togglePlayPause()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        val filter = IntentFilter(ACTION_PIP_PLAY_PAUSE)
        ContextCompat.registerReceiver(this, pipActionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        loadFromIntent(intent)

        collectOrientationState()
        collectPipState()
        collectPlaybackEnded()

        setContent {
            PotatoTheme {
                PlayScreen(
                    viewModel = viewModel,
                    isPipMode = isPipMode.value,
                    onPipClick = { enterPipMode() },
                    onBackClick = { finish() },
                    onControlsVisibilityChanged = { visible ->
                        setSystemBarsVisible(visible)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isPipMode.value) {
            setSystemBarsVisible(areControlsVisible)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isPipMode.value) {
            setSystemBarsVisible(areControlsVisible)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!isPipMode.value) {
            setSystemBarsVisible(areControlsVisible)
        }
    }

    private fun setSystemBarsVisible(visible: Boolean) {
        areControlsVisible = visible
        Log.i(TAG, "setSystemBarsVisible: visible=$visible")
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hideSystemBarsJob?.cancel()
        if (visible) {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            hideSystemBarsJob = lifecycleScope.launch {
                delay(800)
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (viewModel.uiState.value.isPlaying == true) {
            enterPipMode()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPipMode.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            // Hide immediately on PiP entry, no delay
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            setSystemBarsVisible(areControlsVisible)
            applyOrientation(viewModel.orientationState.value)
        }
    }

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                Log.w(TAG, "Picture-in-Picture is not supported on this device")
                return
            }
            val params = buildPipParams(autoEnter = true)
            try {
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enter Picture-in-Picture mode", e)
            }
        }
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
            try {
                val isPlaying = viewModel.uiState.value.isPlaying == true
                setPictureInPictureParams(buildPipParams(autoEnter = isPlaying))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update PiP params", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(autoEnter: Boolean = true): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        val isPlaying = viewModel.uiState.value.isPlaying == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter && isPlaying)
        }

        val state = viewModel.orientationState.value
        val rotation = ((state.videoRotationDegrees % 360) + 360) % 360
        val isSwapped = rotation == 90 || rotation == 270
        val width = if (isSwapped) state.videoHeight else state.videoWidth
        val height = if (isSwapped) state.videoWidth else state.videoHeight
        if (width > 0 && height > 0) {
            try {
                val rational = Rational(width, height)
                val aspect = rational.toFloat()
                if (aspect >= (1f / 2.39f) && aspect <= 2.39f) {
                    builder.setAspectRatio(rational)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set PiP aspect ratio: ${width}x$height", e)
            }
        }

        val actionIntent = Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_CODE_PLAY_PAUSE,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val iconRes = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val title = if (isPlaying) "Pause" else "Play"
        val action = RemoteAction(
            Icon.createWithResource(this, iconRes),
            title,
            title,
            pendingIntent
        )
        builder.setActions(listOf(action))

        return builder.build()
    }

    private fun collectPipState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.uiState, viewModel.orientationState) { ui, orientation ->
                    ui.isPlaying to (orientation.videoWidth to orientation.videoHeight)
                }.collect {
                    updatePipParams()
                }
            }
        }
    }

    private fun collectPlaybackEnded() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playbackEnded.collect {
                    finish()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadFromIntent(intent)
    }

    private fun loadFromIntent(intent: Intent?) {
        val uri: Uri? = intent?.data
            ?: if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_URI)
            }
        val path: String? = intent?.getStringExtra(EXTRA_PATH)

        when {
            uri != null -> viewModel.loadUri(uri)
            path != null -> viewModel.loadFile(path)
        }
    }

    /**
     * Single collector — the only place requestedOrientation is ever written.
     * Resolved via OrientationController from state the ViewModel exposes.
     */
    private fun collectOrientationState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.orientationState.collect { state ->
                    applyOrientation(state)
                }
            }
        }
    }

    private fun applyOrientation(state: OrientationState) {
        if (isPipMode.value) return
        val effectiveMode = state.manualOverride ?: state.mode
        val input = OrientationInput(
            mode = effectiveMode,
            videoWidth = state.videoWidth,
            videoHeight = state.videoHeight,
            videoRotationDegrees = state.videoRotationDegrees,
        )
        when (val result = orientationController.resolve(input)) {
            is OrientationResult.FollowSystem -> {
                Log.i(TAG, "Orientation resolved: FollowSystem (mode=${effectiveMode}, dims=${state.videoWidth}x${state.videoHeight})")
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            is OrientationResult.Locked -> {
                val name = when (result.androidOrientation) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> "PORTRAIT"
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "LANDSCAPE"
                    else -> result.androidOrientation.toString()
                }
                Log.i(TAG, "Orientation resolved: Locked($name) (mode=${effectiveMode}, dims=${state.videoWidth}x${state.videoHeight})")
                requestedOrientation = result.androidOrientation
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pipActionReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister PiP receiver", e)
        }
        Log.i(TAG, "onDestroy: resetting requestedOrientation to UNSPECIFIED")
        // Release any lock so it doesn't leak to whatever screen is shown next
        // (e.g. MainActivity) before that screen gets a chance to set its own.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    companion object {
        private const val TAG = "PlayerActivity"
        private const val EXTRA_URI = "is.xyz.mpv.extra.URI"
        private const val EXTRA_PATH = "is.xyz.mpv.extra.PATH"
        private const val ACTION_PIP_PLAY_PAUSE = "is.xyz.mpv.ACTION_PIP_PLAY_PAUSE"
        private const val REQUEST_CODE_PLAY_PAUSE = 101

        fun intentForUri(context: Context, uri: Uri): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri)
            }

        fun intentForPath(context: Context, path: String): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
            }
    }
}
