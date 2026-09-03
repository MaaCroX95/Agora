package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.chat.StreamingTailAnchorHeight
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.NoAutoScrollSelectionContainer
import com.newoether.agora.viewmodel.normalizePersistedGenerationErrorText

/** Shared neutral text presentation for terminal generation information. */
@Composable
internal fun GenerationTerminalText(
    text: String,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    fillWidth: Boolean = false,
    normalizeError: Boolean = false,
) {
    val context = LocalContext.current
    val displayText = if (normalizeError) {
        normalizePersistedGenerationErrorText(context, text)
    } else {
        text
    }
    val textContent: @Composable () -> Unit = {
        Text(
            text = displayText,
            style = ChatType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
    }
    Box(
        modifier = Modifier
            .heightIn(min = StreamingTailAnchorHeight)
            .then(modifier)
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier),
    ) {
        if (selectable) {
            NoAutoScrollSelectionContainer {
                textContent()
            }
        } else {
            textContent()
        }
    }
}

/** Stateless shared presentation for a caller-owned generation error value. */
@Composable
internal fun GenerationErrorBar(
    errorText: String,
    modifier: Modifier = Modifier,
    precededByCard: Boolean = false,
) {
    GenerationTerminalText(
        text = errorText,
        modifier = modifier.padding(
            top = if (precededByCard) 12.dp else 8.dp,
            bottom = 4.dp,
        ),
        selectable = true,
        fillWidth = true,
        normalizeError = true,
    )
}

@Composable
internal fun StoppedGenerationBar(
    precededByCard: Boolean = false,
) {
    GenerationTerminalText(
        text = stringResource(R.string.generation_stopped),
        modifier = Modifier.padding(
            top = if (precededByCard) 12.dp else 8.dp,
        ),
    )
}
