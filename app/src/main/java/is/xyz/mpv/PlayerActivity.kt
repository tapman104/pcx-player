package `is`.xyz.mpv

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import `is`.xyz.mpv.ui.PotatoTheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        loadFromIntent(intent)

        collectOrientationState()

        setContent {
            PotatoTheme {
                PlayScreen(
                    viewModel = viewModel,
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
        setSystemBarsVisible(areControlsVisible)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setSystemBarsVisible(areControlsVisible)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setSystemBarsVisible(areControlsVisible)
    }

    private fun setSystemBarsVisible(visible: Boolean) {
        areControlsVisible = visible
        Log.i(TAG, "setSystemBarsVisible: visible=$visible")
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
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
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: resetting requestedOrientation to UNSPECIFIED")
        // Release any lock so it doesn't leak to whatever screen is shown next
        // (e.g. MainActivity) before that screen gets a chance to set its own.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    companion object {
        private const val TAG = "PlayerActivity"
        private const val EXTRA_URI = "is.xyz.mpv.extra.URI"
        private const val EXTRA_PATH = "is.xyz.mpv.extra.PATH"

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
