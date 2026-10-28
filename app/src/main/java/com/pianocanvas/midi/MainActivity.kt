package com.pianocanvas.midi

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.pianocanvas.midi.editor.EditorViewModel
import com.pianocanvas.midi.ui.MidiEditMobileApp

/**
 * Single-activity host. The editor itself is Compose, with the piano roll as a
 * custom Android view inside it. Opening a .mid from a file manager arrives as
 * ACTION_VIEW and is imported as soon as the autosave restore has finished.
 */
class MainActivity : ComponentActivity() {

    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge-to-edge, then inset the Compose content with safeDrawingPadding
        // in MidiEditMobileApp. This keeps the top bar below the status bar even
        // on devices/ROMs that force edge-to-edge.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // enableEdgeToEdge switches the window to LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
        // which pushes the window under the display cutout in every orientation.
        // The top bar then depends entirely on the ROM dispatching cutout insets;
        // ROMs that skip them in landscape left the title, transport and menu
        // hidden behind the camera cutout (still touchable, never visible).
        // DEFAULT keeps edge-to-edge in portrait and lets the system letterbox
        // the window around the cutout in landscape, so the top bar stays clear
        // even when the cutout inset never arrives. (Cutout modes require API 28.)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        // Only honour ACTION_VIEW on a fresh launch. After a process restart
        // the autosave is newer than the file that was originally opened, and
        // re-importing it here would silently discard the restored edits.
        val initialUri = if (savedInstanceState == null) intent?.dataString else null
        setContent {
            MidiEditMobileApp(vm = vm, initialUri = initialUri)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { vm.importMidi(it) }
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }
}
