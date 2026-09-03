package com.newoether.agora.ui.chat.bottombar
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.chat.FileThumbnail
import com.newoether.agora.ui.common.LocalAgoraHaptics
private const val ATTACHMENT_STATUS_CROSSFADE_MS = 200
/** Projects the exact conversation-owned attachment snapshot and emits identity commands. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AttachmentPreviewRow(
    attachments: List<SelectedAttachment>,
    editable: Boolean,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
    onAllMediaClick: ((urls: List<String>, index: Int) -> Unit)?,
    onFileContentClick: ((fileName: String, content: String) -> Unit)?,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)?,
) {
    val haptics = LocalAgoraHaptics.current
    val mediaAttachments = remember(attachments) {
        attachments.filter {
            !it.unavailable && it.importState == AttachmentImportState.READY &&
                (it.type == "image" || it.type == "video")
        }
    }
    val allMediaUrls = remember(mediaAttachments) {
        mediaAttachments.map { it.localPath ?: it.uri }
    }
    val mediaIndexById = remember(mediaAttachments) {
        mediaAttachments.mapIndexed { index, attachment -> attachment.localId to index }.toMap()
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(attachments, key = SelectedAttachment::localId) { attachment ->
            val uriStr = attachment.uri
            val isVideo = attachment.type == "video"
            val isPdf = attachment.type == "pdf"
            val isFile = attachment.type == "file"
            val isReady = attachment.importState == AttachmentImportState.READY
            val isProcessing = attachment.importState == AttachmentImportState.PROCESSING
            val mediaIndex = mediaIndexById[attachment.localId]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(64.dp).padding(top = 5.dp)
            ) {
                Box {
                    val clickableMod = when {
                        attachment.unavailable || !isReady -> Modifier
                        isFile -> {
                            if (!attachment.storage.canPreview || attachment.preparedText == null) {
                                Modifier
                            } else if (onFileContentClick != null) Modifier.clickable {
                                onFileContentClick(attachment.fileName ?: uriStr, attachment.preparedText)
                            } else Modifier
                        }
                        isPdf -> {
                            if (onPdfPagesClick != null) Modifier.clickable {
                                onPdfPagesClick(attachment.preRenderedPaths.orEmpty(), 0)
                            } else Modifier
                        }
                        mediaIndex != null -> Modifier.combinedClickable(
                            onClick = { onAllMediaClick?.invoke(allMediaUrls, mediaIndex) },
                            onLongClick = { haptics.longPress() },
                            hapticFeedbackEnabled = false,
                        )
                        else -> Modifier
                    }
                    val thumbModifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(clickableMod)
                    when {
                        attachment.unavailable -> {
                            FileThumbnail(
                                fileName = attachment.fileName,
                                isPdf = isPdf,
                                modifier = thumbModifier,
                                fallbackLabel = attachment.type.uppercase().take(4)
                                    .ifEmpty { "FILE" },
                            )
                        }
                        isVideo && isReady && !attachment.processedFrames.isNullOrEmpty() -> {
                            coil.compose.AsyncImage(
                                model = attachment.processedFrames.first(),
                                contentDescription = stringResource(R.string.video_thumbnail),
                                modifier = thumbModifier,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.play),
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(24.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                    .padding(4.dp)
                            )
                        }
                        isVideo -> {
                            Box(
                                modifier = thumbModifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Videocam,
                                    stringResource(R.string.video),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        isPdf -> FileThumbnail(
                            fileName = attachment.fileName,
                            isPdf = true,
                            modifier = thumbModifier,
                        )
                        isFile -> FileThumbnail(
                            fileName = attachment.fileName ?: uriStr,
                            isPdf = false,
                            modifier = thumbModifier,
                        )
                        else -> {
                            coil.compose.AsyncImage(
                                model = attachment.localPath ?: uriStr,
                                contentDescription = null,
                                modifier = thumbModifier,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        }
                    }
                    Crossfade(
                        targetState = isProcessing,
                        animationSpec = tween(ATTACHMENT_STATUS_CROSSFADE_MS),
                        label = "attachmentProcessingOverlay",
                    ) { visible ->
                        if (visible) Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.4f))
                        )
                    }
                    Crossfade(
                        targetState = attachment.importState,
                        animationSpec = tween(ATTACHMENT_STATUS_CROSSFADE_MS),
                        label = "attachmentStatusIndicator",
                        modifier = Modifier.matchParentSize(),
                    ) { state ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            when (state) {
                                AttachmentImportState.PROCESSING -> CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                AttachmentImportState.FAILED -> Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.25f))
                                        .clickable(enabled = editable) {
                                            haptics.selection()
                                            onRetry(attachment.localId)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.ErrorOutline,
                                        contentDescription = stringResource(R.string.retry),
                                        tint = Color(0xFFB0B0B0),
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                                AttachmentImportState.READY -> Unit
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 5.dp, y = (-5).dp)
                            .size(18.dp)
                            .background(Color.Black.copy(alpha = 0.8f), CircleShape)
                            .clip(CircleShape)
                            .clickable(enabled = editable) {
                                haptics.selection()
                                onRemove(attachment.localId)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.remove),
                            tint = Color.White,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }
                if ((attachment.unavailable || isFile || isPdf) && attachment.fileName != null) {
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (attachment.unavailable) {
                    Text(
                        text = stringResource(R.string.attachment_unavailable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
