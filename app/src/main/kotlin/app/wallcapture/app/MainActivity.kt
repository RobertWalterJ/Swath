package app.wallcapture.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Root(vm)
            }
        }
    }
}

@Composable
private fun Root(vm: AppViewModel) {
    BackHandler(enabled = vm.screen !is Screen.Library) { vm.screen = Screen.Library }
    when (val s = vm.screen) {
        Screen.Library -> LibraryScreen(vm)
        is Screen.Capture -> CaptureScreen(vm, s.sessionId)
        is Screen.Viewer -> ViewerScreen(vm, s)
        Screen.Search -> SearchScreen(vm)
    }
}
