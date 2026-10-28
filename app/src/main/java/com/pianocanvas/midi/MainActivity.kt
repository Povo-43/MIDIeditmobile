package com.pianocanvas.midi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.pianocanvas.midi.editor.EditorViewModel
import com.pianocanvas.midi.ui.PianoCanvasApp

/**
 * Single-activity host. The editor itself is Compose, with the piano roll as a
 * custom Android view inside it. Opening a .mid from a file manager arrives as
 * ACTION_VIEW and is imported as soon as the autosave restore has finished.
 */
class MainActivity : ComponentActivity() {

    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only honour ACTION_VIEW on a fresh launch. After a process restart
        // the autosave is newer than the file that was originally opened, and
        // re-importing it here would silently discard the restored edits.
        val initialUri = if (savedInstanceState == null) intent?.dataString else null
        setContent {
            PianoCanvasApp(vm = vm, initialUri = initialUri)
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
