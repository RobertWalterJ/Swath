package app.wallcapture.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.wallcapture.app.ui.ProvideSpeech
import app.wallcapture.app.ui.SwathTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SwathTheme {
                ProvideSpeech {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root(vm) }
                }
            }
        }
    }
}

@Composable
private fun Root(vm: AppViewModel) {
    BackHandler(enabled = vm.showHowTo || vm.screen !is Screen.Library) {
        if (vm.showHowTo) vm.showHowTo = false else vm.screen = Screen.Library
    }
    when (val s = vm.screen) {
        Screen.Library -> LibraryScreen(vm)
        is Screen.Capture -> CaptureScreen(vm, s.sessionId)
        is Screen.Processing -> ProcessingScreen(vm, s.sessionId)
        is Screen.Viewer -> ViewerScreen(vm, s)
        Screen.Search -> SearchScreen(vm)
    }
    if (vm.showHowTo) HowToOverlay(onDone = { vm.showHowTo = false; vm.seenOnboarding = true })
}
