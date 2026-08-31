package com.newoether.agora.ui.chat.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.model.markdownInlineContent
import com.newoether.agora.R
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.ui.chat.caseInsensitiveMatchRanges
import com.newoether.agora.ui.components.SmoothBottomSheet
import com.newoether.agora.ui.components.rememberSmoothBottomSheetState
import com.newoether.agora.ui.theme.ChatType
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.net.URI

private const val CitationTokenStart = 0xE300
private const val CitationTokenEnd = 0xF8FF
private val PlainCitationArtifact = Regex("cite(?:turn\\d+[a-z]+\\d+)+", RegexOption.IGNORE_CASE)
private val PlainCitationSourceId = Regex("turn\\d+[a-z]+\\d+", RegexOption.IGNORE_CASE)
private val TrailingPlainCitationArtifact = Regex("cite(?:turn\\d+[a-z]+\\d+)*(?:turn(?:\\d+(?:[a-z]+\\d*)?)?)$", RegexOption.IGNORE_CASE)
internal const val CITATION_CAPSULE_TONAL_ELEVATION_DP = 2
internal const val CITATION_CAPSULE_FOREGROUND_ALPHA = 0.7f
internal const val CITATION_CAPSULE_FADE_DURATION_MS = 320
internal const val CITATION_SOURCES_SUMMARY_MIN_HEIGHT_DP = 36
internal const val CITATION_SOURCES_SUMMARY_HORIZONTAL_PADDING_DP = 16
internal const val CITATION_SOURCES_SUMMARY_VERTICAL_PADDING_DP = 8
internal const val CITATION_SOURCES_SUMMARY_ICON_SIZE_DP = 18
internal const val CITATION_SOURCES_SUMMARY_ICON_GAP_DP = 8
internal const val CITATION_INLINE_PRIMARY_MAX_WIDTH_DP = 84
internal const val CITATION_INLINE_HORIZONTAL_PADDING_DP = 14
internal const val CITATION_INLINE_FONT_SIZE_SP = 11
internal const val CITATION_INLINE_LINE_HEIGHT_SP = 12
internal const val CITATION_INLINE_PLACEHOLDER_HEIGHT_SP = 22
internal const val CITATION_INLINE_SUFFIX_GAP_DP = 4
internal const val CITATION_INLINE_OUTER_SPACER_DP = 2
internal const val CITATION_SOURCE_ROW_SHAPE_PERCENT = 50
internal const val CITATION_SOURCE_BADGE_BACKGROUND_ALPHA = 0.12f
internal const val CITATION_SOURCE_BADGE_FOREGROUND_ALPHA = 0.8f

internal data class CitationInlineMarker(
    val token: Char,
    val inlineId: String,
    val number: Int,
    val label: String,
    val sources: List<CitationRecord>,
) {
    val source: CitationRecord
        get() = sources.first()
    val additionalCount: Int
        get() = (sources.size - 1).coerceAtLeast(0)
    val displayLabel: String
        get() = if (additionalCount > 0) "$label +$additionalCount" else label
}

internal fun citationInlineAppearanceKey(marker: CitationInlineMarker): String =
    "citation-inline:${marker.source.sourceId}"

internal data class CitationInlineToken(
    val inlineId: String,
    val alternateText: String,
)

internal data class CitationMarkdownProjection(
    val markdown: String,
    val markers: List<CitationInlineMarker>,
)

private data class CitationProjectionCandidate(
    val sourceIndex: Int,
    val source: CitationRecord,
    val startIndex: Int,
    val endIndex: Int,
    val replacesPresentation: Boolean,
)

private data class CitationProjectionEdit(
    val startIndex: Int,
    val endIndex: Int,
    val replacement: String,
)

internal val LocalCitationInlineTokens =
    staticCompositionLocalOf<Map<Char, CitationInlineToken>> { emptyMap() }

internal fun citationInlineLabel(source: CitationRecord): String =
    CitationPolicy.safeHttpUrl(source.url)?.let { safeUrl ->
        runCatching { URI(safeUrl).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf(String::isNotBlank)
    } ?: source.fileName?.takeIf(String::isNotBlank) ?: source.title

private fun parenthesizedCitationLinkTargetsSource(
    citedText: String,
    sourceUrl: String?,
): Boolean {
    val safeSourceUrl = CitationPolicy.safeHttpUrl(sourceUrl) ?: return false
    if (!citedText.startsWith("([") || !citedText.endsWith("))")) return false
    val targetStart = citedText.indexOf("](", startIndex = 2)
    if (targetStart <= 2) return false
    val target = citedText.substring(targetStart + 2, citedText.length - 2)
    if (target.isBlank() || target.any(Char::isWhitespace)) return false
    return CitationPolicy.safeHttpUrl(target) == safeSourceUrl
}

internal fun citationSummaryVisible(
    showActions: Boolean,
    informationVisible: Boolean,
    sourceCount: Int,
): Boolean = showActions && informationVisible && sourceCount > 0

internal fun projectCitationMarkdown(
    answerText: String,
    citations: List<CitationRecord>,
): CitationMarkdownProjection {
    val normalized = CitationPolicy.deduplicate(citations, answerText)
    if (answerText.isEmpty()) {
        return CitationMarkdownProjection(answerText, emptyList())
    }
    val unsupported by lazy(LazyThreadSafetyMode.NONE) {
        unsupportedMarkdownRanges(answerText)
    }
    val candidates = normalized.flatMapIndexed { sourceIndex, source ->
        val anchored = source.anchors.mapNotNull { anchor ->
            val exact = anchor.startIndex >= 0 &&
                anchor.endIndex <= answerText.length &&
                anchor.endIndex > anchor.startIndex &&
                answerText.substring(anchor.startIndex, anchor.endIndex) == anchor.citedText
            if (!exact) return@mapNotNull null
            val replacesPresentation = parenthesizedCitationLinkTargetsSource(
                citedText = anchor.citedText,
                sourceUrl = source.url,
            )
            val overlapsUnsupported = !replacesPresentation && unsupported.any { range ->
                anchor.startIndex < range.endExclusive && anchor.endIndex > range.start
            }
            if (overlapsUnsupported) return@mapNotNull null
            CitationProjectionCandidate(
                sourceIndex = sourceIndex,
                source = source,
                startIndex = anchor.startIndex,
                endIndex = anchor.endIndex,
                replacesPresentation = replacesPresentation,
            )
        }
        val providerSourceId = source.providerSourceId ?: return@flatMapIndexed anchored
        anchored + PlainCitationArtifact.findAll(answerText).mapNotNull { artifact ->
            if (PlainCitationSourceId.findAll(artifact.value).none {
                it.value.equals(providerSourceId, ignoreCase = true)
            }) return@mapNotNull null
            CitationProjectionCandidate(
                sourceIndex = sourceIndex,
                source = source,
                startIndex = artifact.range.first,
                endIndex = artifact.range.last + 1,
                replacesPresentation = true,
            )
        }
    }.distinctBy { candidate ->
        listOf(
            candidate.source.sourceId,
            candidate.startIndex,
            candidate.endIndex,
            candidate.replacesPresentation,
        )
    }
    val replacementCandidates = candidates.filter(CitationProjectionCandidate::replacesPresentation)
    val acceptedReplacements = replacementCandidates.filter { candidate ->
        replacementCandidates.none { other ->
            other !== candidate &&
                (other.startIndex != candidate.startIndex || other.endIndex != candidate.endIndex) &&
                candidate.startIndex < other.endIndex &&
                candidate.endIndex > other.startIndex
        }
    }
    val acceptedInsertions = candidates
        .filterNot(CitationProjectionCandidate::replacesPresentation)
        .filter { candidate ->
            acceptedReplacements.none { replacement ->
                candidate.endIndex in replacement.startIndex until replacement.endIndex
            }
        }
    val placements = (acceptedReplacements + acceptedInsertions)
        .sortedWith(
            compareBy(CitationProjectionCandidate::sourceIndex)
                .thenBy(CitationProjectionCandidate::startIndex),
        )
    if (placements.isEmpty()) return CitationMarkdownProjection(answerText, emptyList())

    val usedTokens = answerText.toSet().toMutableSet()
    var nextToken = CitationTokenStart
    fun allocateToken(): Char? {
        while (nextToken <= CitationTokenEnd) {
            val candidate = nextToken++.toChar()
            if (usedTokens.add(candidate)) return candidate
        }
        return null
    }

    val markerBySource = linkedMapOf<String, CitationInlineMarker>()
    placements.forEach { placement ->
        if (placement.source.sourceId in markerBySource) return@forEach
        val token = allocateToken() ?: return@forEach
        markerBySource[placement.source.sourceId] = CitationInlineMarker(
            token = token,
            inlineId = "citation-inline:${placement.source.sourceId}",
            number = placement.sourceIndex + 1,
            label = citationInlineLabel(placement.source),
            sources = listOf(placement.source),
        )
    }
    val usablePlacements = placements.filter { it.source.sourceId in markerBySource }
    if (usablePlacements.isEmpty()) {
        return CitationMarkdownProjection(answerText, emptyList())
    }

    val edits = usablePlacements
        .groupBy { placement ->
            if (placement.replacesPresentation) {
                placement.startIndex to placement.endIndex
            } else {
                placement.endIndex to placement.endIndex
            }
        }
        .map { (range, grouped) ->
            CitationProjectionEdit(
                startIndex = range.first,
                endIndex = range.second,
                replacement = grouped.joinToString(separator = "") { placement ->
                    markerBySource.getValue(placement.source.sourceId).token.toString()
                },
            )
        }
        .sortedWith(
            compareByDescending(CitationProjectionEdit::startIndex)
                .thenByDescending(CitationProjectionEdit::endIndex),
        )
    val projected = StringBuilder(answerText)
    edits.forEach { edit ->
        if (edit.startIndex == edit.endIndex) {
            projected.insert(edit.startIndex, edit.replacement)
        } else {
            projected.replace(edit.startIndex, edit.endIndex, edit.replacement)
        }
    }

    val markerByToken = markerBySource.values.associateBy(CitationInlineMarker::token)
    val visibleMarkerBySources = linkedMapOf<List<String>, CitationInlineMarker>()
    val collapsed = StringBuilder(projected.length)
    var projectedIndex = 0
    while (projectedIndex < projected.length) {
        val firstMarker = markerByToken[projected[projectedIndex]]
        if (firstMarker == null) {
            collapsed.append(projected[projectedIndex])
            projectedIndex += 1
            continue
        }
        val adjacentMarkers = buildList {
            while (projectedIndex < projected.length) {
                val marker = markerByToken[projected[projectedIndex]] ?: break
                add(marker)
                projectedIndex += 1
            }
        }
        val groupedSources = adjacentMarkers
            .flatMap(CitationInlineMarker::sources)
            .distinctBy(CitationRecord::sourceId)
        val sourceIds = groupedSources.map(CitationRecord::sourceId)
        val marker = visibleMarkerBySources.getOrPut(sourceIds) {
            if (groupedSources.size == 1) {
                markerBySource.getValue(groupedSources.single().sourceId)
            } else {
                val primary = adjacentMarkers.first()
                CitationInlineMarker(
                    token = allocateToken() ?: primary.token,
                    inlineId = "citation-inline-group:${sourceIds.joinToString(separator = "|")}",
                    number = primary.number,
                    label = primary.label,
                    sources = groupedSources,
                )
            }
        }
        collapsed.append(marker.token)
    }
    return CitationMarkdownProjection(
        markdown = collapsed.toString(),
        markers = visibleMarkerBySources.values.toList(),
    )
}

private const val StreamingCitationWrapperMaxLength = 4_096
private const val StreamingCitationLabelMaxLength = 256

private fun boundedTrailingCitationWrapperStart(answerText: String): Int {
    val lowerBound = (answerText.length - StreamingCitationWrapperMaxLength).coerceAtLeast(0)
    var index = answerText.length - 2
    while (index >= lowerBound) {
        if (answerText[index] == '(' && answerText[index + 1] == '[') return index
        index -= 1
    }
    return -1
}

internal fun withholdTrailingCitationWrapper(answerText: String): String {
    if (answerText.endsWith('(')) return answerText.dropLast(1)
    val start = boundedTrailingCitationWrapperStart(answerText)
    if (start < 0 || answerText.length - start > StreamingCitationWrapperMaxLength) return answerText
    val suffix = answerText.substring(start)
    if ('\n' in suffix || '\r' in suffix) return answerText

    val targetStart = suffix.indexOf("](", startIndex = 2)
    if (targetStart < 0) {
        val partialLabel = suffix.drop(2)
        return if (
            partialLabel.length <= StreamingCitationLabelMaxLength &&
            partialLabel.none { it == ']' || it == ')' }
        ) {
            answerText.substring(0, start)
        } else {
            answerText
        }
    }
    val label = suffix.substring(2, targetStart)
    if (label.isBlank() || label.length > StreamingCitationLabelMaxLength) return answerText

    val targetAndClose = suffix.substring(targetStart + 2)
    val closeStart = targetAndClose.indexOf("))")
    if (closeStart >= 0) {
        if (closeStart != targetAndClose.length - 2) return answerText
        val target = targetAndClose.substring(0, closeStart)
        return if (
            target.isNotBlank() &&
            target.none(Char::isWhitespace) &&
            CitationPolicy.safeHttpUrl(target) != null
        ) {
            answerText.substring(0, start)
        } else {
            answerText
        }
    }

    if (
        targetAndClose.length > StreamingCitationWrapperMaxLength ||
        targetAndClose.any(Char::isWhitespace)
    ) {
        return answerText
    }
    val lowerTarget = targetAndClose.lowercase()
    val possibleHttpTarget =
        "https://".startsWith(lowerTarget) ||
            "http://".startsWith(lowerTarget) ||
            lowerTarget.startsWith("https://") ||
            lowerTarget.startsWith("http://")
    return if (possibleHttpTarget) answerText.substring(0, start) else answerText
}

internal fun citationMarkdownProjection(
    answerText: String,
    citations: List<CitationRecord>,
    isStreaming: Boolean,
): CitationMarkdownProjection? {
    if (answerText.isEmpty()) return null
    return if (isStreaming) {
        val markdown = withholdTrailingCitationWrapper(answerText)
        val partialStart = TrailingPlainCitationArtifact.find(markdown)?.range?.first
        CitationMarkdownProjection(
            markdown = CitationPolicy.stripPrivateMarkers(partialStart?.let { markdown.substring(0, it) } ?: markdown),
            markers = emptyList(),
        )
    } else {
        val projection = projectCitationMarkdown(answerText, citations)
        projection.copy(markdown = CitationPolicy.stripPrivateMarkers(projection.markdown))
    }
}

internal fun citationRecordsForAnswerSlice(
    citations: List<CitationRecord>,
    sliceStart: Int,
    sliceText: String,
): List<CitationRecord> {
    val sliceEnd = sliceStart + sliceText.length
    return citations.map { source ->
        val anchors = source.anchors.mapNotNull { anchor ->
            if (anchor.startIndex < sliceStart || anchor.endIndex > sliceEnd) return@mapNotNull null
            anchor.copy(
                startIndex = anchor.startIndex - sliceStart,
                endIndex = anchor.endIndex - sliceStart,
            )
        }
        source.copy(anchors = anchors)
    }
}

internal fun citationSourceMatchKeys(
    messageId: String,
    source: CitationRecord,
    titleRanges: List<IntRange>,
): List<String> = titleRanges.map { range ->
    "$messageId:citation:${source.sourceId}:${range.first}:${range.last + 1}"
}

internal fun citationSourcesSheetTitle(
    sourceCount: Int,
    sourcesLabel: String,
): String = "$sourceCount $sourcesLabel"

internal fun citationInlinePlaceholderWidthPx(
    primaryTextWidthPx: Int,
    suffixTextWidthPx: Int?,
    primaryMaxWidthPx: Int,
    horizontalPaddingPx: Int,
    suffixGapPx: Int,
    outerSpacingEachSidePx: Int,
): Int {
    val primaryWidth = primaryTextWidthPx.coerceIn(0, primaryMaxWidthPx.coerceAtLeast(0))
    val baseWidth = primaryWidth + horizontalPaddingPx.coerceAtLeast(0) +
        outerSpacingEachSidePx.coerceAtLeast(0) * 2
    return suffixTextWidthPx?.let { suffixWidth ->
        baseWidth + suffixGapPx.coerceAtLeast(0) + suffixWidth.coerceAtLeast(0)
    } ?: baseWidth
}

@Composable
private fun citationCapsuleBackgroundColor() =
    MaterialTheme.colorScheme.surfaceColorAtElevation(CITATION_CAPSULE_TONAL_ELEVATION_DP.dp)

@Composable
private fun citationCapsuleForegroundColor() =
    MaterialTheme.colorScheme.primary.copy(alpha = CITATION_CAPSULE_FOREGROUND_ALPHA)

@Composable
private fun citationCapsuleFadeModifier(
    animationKey: String,
    visible: Boolean = true,
    sharedDrawAlpha: Animatable<Float, AnimationVector1D>? = null,
): Modifier {
    val ownedDrawAlpha = remember(animationKey) { Animatable(0f) }
    val drawAlpha = sharedDrawAlpha ?: ownedDrawAlpha
    LaunchedEffect(animationKey, visible, drawAlpha) {
        if (visible) {
            drawAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = CITATION_CAPSULE_FADE_DURATION_MS,
                    easing = LinearEasing,
                ),
            )
        } else {
            drawAlpha.snapTo(0f)
        }
    }
    return Modifier.graphicsLayer { alpha = drawAlpha.value }
}

internal fun AnnotatedString.replaceCitationInlineTokens(
    tokens: Map<Char, CitationInlineToken>,
): AnnotatedString {
    if (tokens.isEmpty() || text.none(tokens::containsKey)) return this
    val builder = AnnotatedString.Builder()
    var copiedFrom = 0
    text.forEachIndexed { index, character ->
        val inline = tokens[character] ?: return@forEachIndexed
        if (copiedFrom < index) builder.append(this, copiedFrom, index)
        builder.appendInlineContent(inline.inlineId, inline.alternateText)
        copiedFrom = index + 1
    }
    if (copiedFrom < length) builder.append(this, copiedFrom, length)
    return builder.toAnnotatedString()
}

@Composable
internal fun CitationInlineContentHost(
    projection: CitationMarkdownProjection?,
    onActivate: (List<CitationRecord>) -> Unit,
    content: @Composable () -> Unit,
) {
    val inlineFadeStateByPrimarySource = remember {
        mutableMapOf<String, Animatable<Float, AnimationVector1D>>()
    }
    val markers = projection?.markers.orEmpty()
    val activeFadeKeys = markers
        .map(::citationInlineAppearanceKey)
        .toSet()
    LaunchedEffect(activeFadeKeys) {
        inlineFadeStateByPrimarySource.keys.retainAll(activeFadeKeys)
    }
    val currentOnActivate by rememberUpdatedState(onActivate)
    val existingInlineContent = LocalMarkdownInlineContent.current
    val tokenMap = markers.associate { marker ->
        marker.token to CitationInlineToken(
            inlineId = marker.inlineId,
            alternateText = "[${marker.displayLabel}]",
        )
    }
    val inlineTextStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = CITATION_INLINE_FONT_SIZE_SP.sp,
        lineHeight = CITATION_INLINE_LINE_HEIGHT_SP.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val primaryMaxWidthPx = with(density) {
        CITATION_INLINE_PRIMARY_MAX_WIDTH_DP.dp.roundToPx()
    }
    val horizontalPaddingPx = with(density) {
        CITATION_INLINE_HORIZONTAL_PADDING_DP.dp.roundToPx()
    }
    val suffixGapPx = with(density) {
        CITATION_INLINE_SUFFIX_GAP_DP.dp.roundToPx()
    }
    val outerSpacingEachSidePx = with(density) {
        CITATION_INLINE_OUTER_SPACER_DP.dp.roundToPx()
    }
    val inlineContent = markers.associate { marker ->
        val suffixText = marker.additionalCount
            .takeIf { it > 0 }
            ?.let { "+$it" }
        val primaryTextWidthPx = textMeasurer.measure(
            text = marker.label,
            style = inlineTextStyle,
            maxLines = 1,
        ).size.width
        val suffixTextWidthPx = suffixText?.let { suffix ->
            textMeasurer.measure(
                text = suffix,
                style = inlineTextStyle,
                maxLines = 1,
            ).size.width
        }
        val widthPx = citationInlinePlaceholderWidthPx(
            primaryTextWidthPx = primaryTextWidthPx,
            suffixTextWidthPx = suffixTextWidthPx,
            primaryMaxWidthPx = primaryMaxWidthPx,
            horizontalPaddingPx = horizontalPaddingPx,
            suffixGapPx = suffixGapPx,
            outerSpacingEachSidePx = outerSpacingEachSidePx,
        )
        val width = (widthPx / (density.density * density.fontScale)).sp
        marker.inlineId to InlineTextContent(
            placeholder = Placeholder(
                width = width,
                height = CITATION_INLINE_PLACEHOLDER_HEIGHT_SP.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            val appearanceKey = citationInlineAppearanceKey(marker)
            val sharedDrawAlpha = inlineFadeStateByPrimarySource.getOrPut(appearanceKey) {
                Animatable(0f)
            }
            val accessibilityLabel = if (marker.additionalCount > 0) {
                citationSourcesSheetTitle(
                    sourceCount = marker.sources.size,
                    sourcesLabel = stringResource(R.string.citation_sources),
                )
            } else {
                stringResource(
                    R.string.citation_source_accessibility,
                    marker.number,
                    marker.source.title,
                )
            }
            CitationInlineCapsule(
                primaryText = marker.label,
                additionalCount = marker.additionalCount,
                textStyle = inlineTextStyle,
                animationKey = appearanceKey,
                sharedDrawAlpha = sharedDrawAlpha,
                accessibilityLabel = accessibilityLabel,
                onClick = { currentOnActivate(marker.sources) },
            )
        }
    }
    CompositionLocalProvider(
        LocalCitationInlineTokens provides tokenMap,
        LocalMarkdownInlineContent provides markdownInlineContent(
            existingInlineContent.inlineContent + inlineContent,
        ),
        content = content,
    )
}

@Composable
private fun CitationInlineCapsule(
    primaryText: String,
    additionalCount: Int,
    textStyle: TextStyle,
    animationKey: String,
    sharedDrawAlpha: Animatable<Float, AnimationVector1D>,
    accessibilityLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CITATION_INLINE_OUTER_SPACER_DP.dp)
            .then(
                citationCapsuleFadeModifier(
                    animationKey = animationKey,
                    sharedDrawAlpha = sharedDrawAlpha,
                ),
            )
            .clip(RoundedCornerShape(50))
            .background(citationCapsuleBackgroundColor())
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel }
            .padding(horizontal = (CITATION_INLINE_HORIZONTAL_PADDING_DP / 2).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = primaryText,
            color = citationCapsuleForegroundColor(),
            style = textStyle,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (additionalCount > 0) Modifier.weight(1f, fill = false) else Modifier,
        )
        if (additionalCount > 0) {
            Spacer(Modifier.width(CITATION_INLINE_SUFFIX_GAP_DP.dp))
            Text(
                text = "+$additionalCount",
                color = citationCapsuleForegroundColor(),
                style = textStyle,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun CitationSourcesSummaryCapsule(
    messageId: String,
    citations: List<CitationRecord>,
    searchSpec: SearchHighlightSpec?,
    visible: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (citations.isEmpty()) return
    val label = "${citations.size} ${stringResource(R.string.citation_sources)}"
    val matchKeys = remember(messageId, citations, searchSpec?.query) {
        citations.flatMap { source ->
            citationSourceMatchKeys(
                messageId = messageId,
                source = source,
                titleRanges = caseInsensitiveMatchRanges(
                    source.title,
                    searchSpec?.query.orEmpty(),
                ),
            )
        }
    }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(visible, searchSpec?.query, matchKeys, coordinates) {
        val spec = searchSpec ?: return@LaunchedEffect
        val coords = coordinates?.takeIf { it.isAttached } ?: return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        val centerY = coords.positionInRoot().y + coords.size.height / 2f
        matchKeys.forEach { key ->
            spec.onMatchPosition(key, spec.measurementEpoch, centerY)
        }
    }
    Row(
        modifier = modifier
            .onGloballyPositioned { coordinates = it }
            .heightIn(min = CITATION_SOURCES_SUMMARY_MIN_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(50))
            .background(citationCapsuleBackgroundColor())
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .then(
                if (visible) {
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = label
                    }
                } else {
                    Modifier.clearAndSetSemantics { }
                }
            )
            .padding(
                horizontal = CITATION_SOURCES_SUMMARY_HORIZONTAL_PADDING_DP.dp,
                vertical = CITATION_SOURCES_SUMMARY_VERTICAL_PADDING_DP.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.link_24),
            contentDescription = null,
            modifier = Modifier.size(CITATION_SOURCES_SUMMARY_ICON_SIZE_DP.dp),
            tint = citationCapsuleForegroundColor(),
        )
        Spacer(modifier = Modifier.width(CITATION_SOURCES_SUMMARY_ICON_GAP_DP.dp))
        Text(
            text = label,
            color = citationCapsuleForegroundColor(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun CitationSourcesBottomSheet(
    messageId: String,
    citations: List<CitationRecord>,
    searchSpec: SearchHighlightSpec?,
    onActivate: (CitationRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberSmoothBottomSheetState()
    val listState = rememberLazyListState()
    var pendingActivation by remember { mutableStateOf<CitationRecord?>(null) }
    val sheetSearchSpec = searchSpec?.copy(onMatchPosition = { _, _, _ -> })
    fun collapseThenActivate(source: CitationRecord) {
        pendingActivation = source
        sheetState.requestDismiss()
    }

    SmoothBottomSheet(
        state = sheetState,
        onDismissRequest = {
            val source = pendingActivation
            pendingActivation = null
            onDismiss()
            source?.let(onActivate)
        },
        contentAtTop = {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
        },
        header = {
            Text(
                text = citationSourcesSheetTitle(
                    sourceCount = citations.size,
                    sourcesLabel = stringResource(R.string.citation_sources),
                ),
                style = ChatType.detailTitle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                items = citations,
                key = { _, source -> source.sourceId },
            ) { index, source ->
                CitationSourceRow(
                    messageId = messageId,
                    number = index + 1,
                    source = source,
                    searchSpec = sheetSearchSpec,
                    onActivate = { collapseThenActivate(source) },
                )
            }
        }
    }
}

@Composable
private fun CitationSourceRow(
    messageId: String,
    number: Int,
    source: CitationRecord,
    searchSpec: SearchHighlightSpec?,
    onActivate: () -> Unit,
) {
    val sourceLabel = stringResource(
        R.string.citation_source_accessibility,
        number,
        source.title,
    )
    val titleRanges = caseInsensitiveMatchRanges(
        source.title,
        searchSpec?.query.orEmpty(),
    )
    val titleKeys = citationSourceMatchKeys(messageId, source, titleRanges)
    val activeTitleRange = titleKeys.indexOf(searchSpec?.activeKey)
        .takeIf { it >= 0 }
        ?.let(titleRanges::getOrNull)
    val sourceSearchSpec = searchSpec
        ?.takeIf { titleRanges.isNotEmpty() }
        ?.copy(
            activeRange = activeTitleRange,
            matchKeys = titleKeys,
        )
    val titleColor = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                citationCapsuleFadeModifier(
                    animationKey = "citation-source-row:$messageId:${source.sourceId}",
                ),
            )
            .clip(RoundedCornerShape(CITATION_SOURCE_ROW_SHAPE_PERCENT))
            .clickable(role = Role.Button, onClick = onActivate)
            .semantics(mergeDescendants = true) { contentDescription = sourceLabel }
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CitationBadgeVisual(number)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            SearchHighlightedPlainText(
                text = source.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = TextDecoration.None,
                ),
                color = titleColor,
                spec = sourceSearchSpec,
                modifier = Modifier.fillMaxWidth(),
            )
            val metadata = listOfNotNull(source.fileName, source.location)
                .distinct()
                .joinToString(" - ")
            if (metadata.isNotBlank()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CitationBadgeVisual(number: Int) {
    val width = if (number < 10) 20.dp else 26.dp
    Box(
        modifier = Modifier
            .size(width = width, height = 20.dp)
            .clip(CircleShape)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = CITATION_SOURCE_BADGE_BACKGROUND_ALPHA,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = CITATION_SOURCE_BADGE_FOREGROUND_ALPHA,
            ),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun CitationSourceDetailDialog(
    source: CitationRecord,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(source.title) },
        text = {
            Column {
                source.fileName?.let {
                    Text("${stringResource(R.string.citation_source_file)}: $it")
                }
                source.location?.let {
                    Text("${stringResource(R.string.citation_source_location)}: $it")
                }
                source.excerpt?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        },
    )
}

private data class SourceRange(val start: Int, val endExclusive: Int)

private fun unsupportedMarkdownRanges(content: String): List<SourceRange> = runCatching {
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
    buildList { root.collectUnsupportedCitationRanges(this) }
}.getOrElse {
    listOf(SourceRange(0, content.length))
}

private fun ASTNode.collectUnsupportedCitationRanges(target: MutableList<SourceRange>) {
    val typeName = type.toString().uppercase()
    val unsupported = typeName.contains("CODE") ||
        typeName.contains("LINK") ||
        typeName.contains("IMAGE") ||
        typeName.contains("HTML")
    if (unsupported) {
        if (endOffset > startOffset) target += SourceRange(startOffset, endOffset)
        return
    }
    children.forEach { it.collectUnsupportedCitationRanges(target) }
}
