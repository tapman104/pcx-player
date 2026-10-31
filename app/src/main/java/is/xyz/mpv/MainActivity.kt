package `is`.xyz.mpv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import `is`.xyz.mpv.ui.PotatoTheme

/**
 * File picker / launcher only. Does not host the player UI — selecting or
 * receiving a file starts PlayerActivity, which owns playback + orientation.
 */
class MainActivity : ComponentActivity() {

    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>

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
            PotatoTheme {
                HomeScreen(
                    onOpenFileClick = {
                        openDocumentLauncher.launch(arrayOf("video/*"))
                    }
                )
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
