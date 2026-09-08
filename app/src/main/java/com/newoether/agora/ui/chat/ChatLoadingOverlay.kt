package com.newoether.agora.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator

/** Original conversation-switch cover shared by ordinary and Remote pages. */
@Composable
internal fun ChatLoadingOverlay(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(modifier),
            contentAlignment = Alignment.Center,
        ) {
            MotionAwareCircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
