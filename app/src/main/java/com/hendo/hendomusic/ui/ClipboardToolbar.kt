package com.hendo.hendomusic.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.AnnotatedString

/**
 * Some OEM keyboards do not request Compose's selection toolbar on a double tap.
 * Explicitly opening the platform toolbar keeps copy/paste available everywhere the
 * app supplies a text input, matching normal Android text fields.
 */
@Composable
fun Modifier.hendoClipboardToolbar(currentText: () -> String, paste: (String) -> Unit): Modifier {
    val clipboard = LocalClipboardManager.current
    val toolbar = LocalTextToolbar.current
    return pointerInput(toolbar, clipboard) {
        detectTapGestures(onDoubleTap = { point ->
            toolbar.showMenu(
                rect = Rect(point, Size.Zero),
                onCopyRequested = { clipboard.setText(AnnotatedString(currentText())) },
                onPasteRequested = { clipboard.getText()?.text?.let(paste) },
                onCutRequested = { clipboard.setText(AnnotatedString(currentText())); paste("") },
                onSelectAllRequested = {},
            )
        })
    }
}
