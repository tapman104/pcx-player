package `is`.xyz.mpv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import `is`.xyz.mpv.ui.PotatoTheme

/**
 * File picker / launcher only. Does not host the player UI — selecting or
 * receiving a file starts PlayerActivity, which owns playback + orientation.
 */
class MainActivity : ComponentActivity() {

    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>

    private val preferencesRepository by lazy {
        UserPreferencesRepository(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to take persistable URI permission for $uri", e)
                }
                startActivity(PlayerActivity.intentForUri(this, uri))
            }
        }

        handleIntent(intent)

        setContent {
            val themeMode by preferencesRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val amoledEnabled by preferencesRepository.amoledEnabled.collectAsState(initial = false)

            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            PotatoTheme(
                darkTheme = darkTheme,
                amoled = darkTheme && amoledEnabled,
            ) {
                var showSettings by remember { mutableStateOf(false) }

                BackHandler(enabled = showSettings) {
                    showSettings = false
                }

                if (showSettings) {
                    SettingsScreen(
                        preferencesRepository = preferencesRepository,
                        onBackClick = { showSettings = false }
                    )
                } else {
                    HomeScreen(
                        onOpenFileClick = { openDocumentLauncher.launch(arrayOf("video/*")) },
                        onSettingsClick = { showSettings = true }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                startActivity(PlayerActivity.intentForUri(this, uri))
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
