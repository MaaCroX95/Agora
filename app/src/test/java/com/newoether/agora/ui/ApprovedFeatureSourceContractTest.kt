package com.newoether.agora.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedFeatureSourceContractTest {
    @Test
    fun cacheCountsAreEagerCoalescedAndAggregated() {
        val root = sourceRoot()
        val rag = source(root, "com/newoether/agora/viewmodel/RagManager.kt")
        val settings = source(root, "com/newoether/agora/ui/settings/SettingsSearchPage.kt")
        val dao = source(root, "com/newoether/agora/data/local/ChatDao.kt")
        val entities = source(root, "com/newoether/agora/data/local/ChatEntities.kt")
        val database = source(root, "com/newoether/agora/data/local/ChatDatabase.kt")

        assertTrue(rag.contains("init {\n        loadCacheCounts()"))
        assertTrue(rag.contains("cacheCountRefreshJob?.isActive == true"))
        assertTrue(rag.contains("getEmbeddingCountsByModels(modelIds)"))
        assertFalse(
            rag.substringAfter("private suspend fun refreshCacheCounts")
                .substringBefore("// ── Embedding-model CRUD")
                .contains("getEmbeddingCountByModel"),
        )
        val cacheLoader = rag.substringAfter("fun loadCacheCounts()")
            .substringBefore("@Synchronized\n    private fun clearCacheCountRefreshJob")
        assertTrue(settings.contains("LaunchedEffect(Unit) { viewModel.ragManager.loadCacheCounts() }"))
        assertTrue(cacheLoader.contains("getWorkInfosForUniqueWorkFlow(workName).first"))
        assertTrue(cacheLoader.contains("observedActiveWorker"))
        assertTrue(cacheLoader.contains("cacheJobs[model.id]?.isActive != true"))
        assertTrue(cacheLoader.contains("EmbeddingCacheWorker.KEY_CACHED"))
        assertTrue(cacheLoader.contains("EmbeddingCacheWorker.KEY_TOTAL"))
        assertTrue(cacheLoader.contains("_cachingProgress.update { it - model.id }"))
        assertTrue(cacheLoader.contains("refreshCacheCounts()"))
        val cacheRunner = rag.substringAfter("fun cacheMessagesForModel")
            .substringBefore("/** The cache loop proper")
        assertTrue(cacheRunner.contains("_cachingProgress.value.containsKey(modelId)"))
        assertTrue(
            cacheRunner.indexOf("refreshCacheCounts()") <
                cacheRunner.indexOf("_cachingProgress.update { it - modelId }"),
        )
        val cacheLoop = rag.substringAfter("private suspend fun runCacheLoop")
            .substringBefore("// ── Single-message indexing")
        assertTrue(
            cacheLoop.contains("_cacheCounts.update { it + (modelId to (cached to total)) }"),
        )
        assertFalse(cacheLoop.contains("_cachingProgress.update { it - modelId }"))
        val modelRow = settings.substringAfter("val allCached =")
            .substringBefore("modifier = Modifier.clickable { viewModel.ragManager.setActiveEmbeddingModel")
        assertTrue(modelRow.contains("if (isCaching)"))
        assertFalse(modelRow.contains("if (!isCaching)"))
        assertTrue(modelRow.contains("} else {\n                                                TextButton"))
        assertTrue(modelRow.contains("strokeWidth = 3.dp"))
        assertFalse(modelRow.contains("strokeWidth = 2.dp"))
        assertTrue(settings.contains(
            "CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(start = 16.dp), strokeWidth = 2.dp)",
        ))
        assertTrue(dao.contains("GROUP BY e.modelId"))
        assertTrue(dao.contains("getEmbeddingCountsByModels"))
        assertTrue(entities.contains("Index(value = [\"modelId\"])"))
        assertTrue(database.contains("CURRENT_VERSION = 26"))
        assertTrue(database.contains("MIGRATION_23_24"))
        assertTrue(database.contains("MIGRATION_24_25"))
        assertTrue(database.contains("MIGRATION_25_26"))
    }

    @Test
    fun contextProgressTweensLocallyAndSnapsForReducedMotion() {
        val root = sourceRoot()
        val bottomBar = source(
            root,
            "com/newoether/agora/ui/chat/bottombar/ChatBottomBar.kt",
        )
        val sharedProgress = source(
            root,
            "com/newoether/agora/ui/motion/MotionAwareProgressIndicators.kt",
        )

        assertTrue(bottomBar.contains("val contextProgress by animateFloatAsState("))
        assertTrue(bottomBar.contains("motionPolicy.allowContinuousMotion"))
        assertTrue(bottomBar.contains("tween(durationMillis = 400)"))
        assertTrue(bottomBar.contains("snap()"))
        assertTrue(bottomBar.split("progress = { contextProgress }").size - 1 == 2)
        assertFalse(sharedProgress.contains("animateFloatAsState"))
    }

    @Test
    fun mediaViewerAndClipboardImagesUseTheApprovedBoundaries() {
        val root = sourceRoot()
        val main = source(root, "com/newoether/agora/MainActivity.kt")
        val dialog = source(
            root,
            "com/newoether/agora/ui/chat/FullScreenMediaPreviewDialog.kt",
        )
        val composer = source(
            root,
            "com/newoether/agora/ui/chat/bottombar/ChatBottomBar.kt",
        )
        val composerState = source(
            root,
            "com/newoether/agora/ui/chat/bottombar/ChatComposerState.kt",
        )
        val payload = source(
            root,
            "com/newoether/agora/viewmodel/MessagePayloadBuilder.kt",
        )
        val sendButton = source(
            root,
            "com/newoether/agora/ui/chat/bottombar/ComposerSendButton.kt",
        )
        val imageActions = source(
            root,
            "com/newoether/agora/ui/chat/ImageActions.kt",
        )

        assertTrue(main.contains("FullScreenMediaPreviewDialog("))
        assertTrue(dialog.contains("Dialog("))
        assertTrue(dialog.contains(".background(Color.Black)"))
        assertTrue(dialog.contains("visibilityTransition.AnimatedVisibility("))
        assertTrue(dialog.contains("visibilityTransition.animateFloat("))
        assertTrue(dialog.contains("DialogWindowNoSystemDim()"))
        assertTrue(imageActions.contains("DialogWindowNoSystemDim()"))
        assertTrue(dialog.indexOf("FullScreenMediaViewer(") > dialog.indexOf(".background(Color.Black)"))
        assertTrue(composer.contains(".contentReceiver(clipboardImageReceiver)"))
        assertTrue(composer.contains("transferableContent.consume"))
        assertTrue(composer.contains("hasMediaType(MediaType.Image)"))
        assertTrue(composer.contains("composer.onPickImages(imageUris)"))
        assertTrue(composer.contains("return remaining"))

        val imageIngress = composerState.substringAfter("fun onPickImages")
            .substringBefore("fun onPickVideos")
        val fileIngress = composerState.substringAfter("fun onPickFiles")
            .substringBefore("fun addSlicedVideo")
        val pdfIngress = composerState.substringAfter("fun confirmPendingPdfSelection")
            .substringBefore("fun dismissPendingPdf")
        val videoIngress = composerState.substringAfter("fun addSlicedVideo")
            .substringBefore("\n}")
        val privateImageUri = "uri = Uri.fromFile(java.io.File(copy.path)).toString()"
        assertTrue(
            imageIngress.indexOf("when (val copy = copyToPrivate(uriObj, \"img\"))") <
                imageIngress.indexOf(privateImageUri),
        )
        assertTrue(
            imageIngress.indexOf(privateImageUri) <
                imageIngress.indexOf("selectedAttachments = selectedAttachments + copiedAttachments"),
        )
        assertFalse(imageIngress.contains("uri = uriObj.toString()"))
        assertTrue(
            fileIngress.indexOf("copyToPrivate(uri, ext, attachment.fileSize)") <
                fileIngress.indexOf("selectedAttachments = selectedAttachments + copiedAttachments"),
        )
        assertTrue(
            pdfIngress.indexOf("copyToPrivate(Uri.parse(uri), \"pdf\")") <
                pdfIngress.indexOf("selectedAttachments = selectedAttachments + SelectedAttachment"),
        )
        assertTrue(
            videoIngress.indexOf("copyToPrivate(sourceUri, ext)") <
                videoIngress.indexOf("selectedAttachments = selectedAttachments + attachment"),
        )
        assertTrue(videoIngress.contains("progressKey = vidUri"))
        assertTrue(composerState.contains("localPath = file.absolutePath"))
        assertFalse(payload.contains("vid_original_"))
        assertTrue(payload.contains("val source = att.localPath ?: att.uri"))
        assertTrue(payload.contains("PdfPageRenderer.renderAsImages(app, sourceUri"))
        assertTrue(sendButton.contains(
            "it.localPath == null && (it.type == \"image\" || it.type == \"file\")",
        ))
    }

    @Test
    fun streamingFadeKeysToolSummaryCrossfadeByPresentationState() {
        val root = sourceRoot()
        val fade = source(
            root,
            "com/newoether/agora/ui/chat/message/IncrementalStreamingMarkdown.kt",
        )
        val assets = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageBubbleAssets.kt",
        )
        val timeline = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageItemTimeline.kt",
        )
        val tool = source(
            root,
            "com/newoether/agora/ui/chat/message/ToolResultContent.kt",
        )
        val stableText = source(
            root,
            "com/newoether/agora/ui/chat/message/StableStreamingText.kt",
        )
        val mutedText = source(
            root,
            "com/newoether/agora/ui/chat/message/StreamingMutedText.kt",
        )
        val lifecycle = source(
            root,
            "com/newoether/agora/ui/chat/message/GenerationLifecycleMotion.kt",
        )
        val messageItem = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageItem.kt",
        )
        val assistant = source(
            root,
            "com/newoether/agora/ui/chat/message/AssistantMessageContent.kt",
        )
        val segments = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageItemSegments.kt",
        )

        assertTrue(fade.contains("fun streamingTailAnnotatedString("))
        assertTrue(fade.contains("fun rememberStreamingGlyphFade("))
        assertFalse(fade.contains("fun Modifier.stableStreamingGlyphFade("))
        assertFalse(fade.contains("BlendMode.DstIn"))
        assertTrue(assets.contains("content = base,"))
        assertTrue(assets.contains("rememberStreamingGlyphFade("))
        assertFalse(assets.contains(".stableStreamingGlyphFade("))
        assertFalse(timeline.contains("StableStreamingText("))
        assertEquals(2, Regex("StreamingMutedText\\(").findAll(timeline).count())
        assertFalse(tool.contains("StableStreamingText("))
        assertFalse(timeline.contains("tailFadeEnabled ="))
        assertFalse(tool.contains("tailFadeEnabled ="))
        assertTrue(mutedText.contains("internal fun ToolSummaryText("))
        assertTrue(mutedText.contains("presentation: ToolPresentation"))
        assertTrue(mutedText.contains("streaming: Boolean"))
        assertEquals(2, Regex("ToolSummaryText\\(").findAll(timeline).count())
        assertEquals(1, Regex("ToolSummaryText\\(").findAll(mutedText).count())
        assertTrue(mutedText.contains("targetState = presentation.state"))
        assertFalse(mutedText.contains("targetState = summary"))
        assertTrue(mutedText.contains("text = renderedSummary"))
        assertTrue(mutedText.contains("!transition.isRunning"))
        assertTrue(timeline.contains("targetState = collapsedTitle"))
        assertTrue(timeline.contains("compactSegmentTitle:\$expansionKey"))
        assertTrue(timeline.contains("val containsToolSummary = segs.any { it.type == \"tool\" }"))
        assertTrue(timeline.contains("forceOpaque = containsToolSummary"))
        assertTrue(Regex("forceOpaque = seg.type == \"tool\"").findAll(timeline).count() == 2)
        assertTrue(timeline.contains("containsToolSummary && allowSpatialTransitions ->"))
        assertTrue(timeline.contains("EnterTransition.None"))
        assertTrue(timeline.contains("ExitTransition.None"))
        assertTrue(tool.contains("private fun ToolActiveContent(text: String, output: String?) {\n    Text("))
        assertTrue(lifecycle.contains("alpha = if (forceOpaque) 1f else value"))
        assertTrue(messageItem.contains(
            "forceOpaque = displayMessage.segments.orEmpty().any { it.type == \"tool\" }",
        ))
        assertTrue(assistant.contains("forceOpaque = detailSegments.any { it.type == \"tool\" }"))
        assertTrue(segments.contains("forceOpaque = forceOpaque"))
        assertTrue(stableText.contains("enabled = streaming && tailFadeEnabled"))
        assertTrue(stableText.contains("initialAlpha = tailFadeInitialAlpha"))
        assertTrue(stableText.contains("fadeCodePoints = tailFadeCodePoints"))
        assertTrue(stableText.contains("spatialBands = tailFadeSpatialBands"))
        assertTrue(mutedText.contains("MUTED_STREAM_TAIL_CODE_POINTS = 42"))
        assertTrue(mutedText.contains("MUTED_STREAM_TAIL_ALPHA_BANDS = 6"))
        assertTrue(mutedText.contains("MUTED_STREAM_TAIL_NEWEST_ALPHA = 0.38f"))
        val toolSummary = mutedText.substringAfter("internal fun ToolSummaryText(")
            .substringBefore("private fun thoughtPreviewTail(")
        assertTrue(toolSummary.contains("Crossfade("))
        assertFalse(toolSummary.contains("StableStreamingText("))
        assertFalse(fade.contains("TOOL_SUMMARY_"))
        assertFalse(fade.contains("toolSummaryTailAnnotatedString"))
        assertFalse(fade.contains("rememberToolSummaryGlyphFade"))
        // Document-level birth-time tracking survives node restructures, block promotion, and
        // subtree re-keying. Births begin only when a snapshot is first published, and the tracker
        // retains only the active not-yet-solid suffix with no fixed character-count cap.
        assertTrue(fade.contains("fadeSample: StreamingTailFadeSample?"))
        assertTrue(fade.contains("fun computeBlockFadeSpecs("))
        assertTrue(fade.contains("internal fun StreamingGlyphFadeSpec?.nodeFade("))
        assertTrue(fade.contains("fadeTracker.update("))
        assertTrue(fade.contains("text = preparedSource,"))
        assertTrue(fade.contains("nowMs = nowMs,"))
        assertTrue(fade.contains("isStreaming || !textDeltas.isNullOrEmpty()"))
        assertTrue(fade.contains("textDeltas = published.textDeltas,"))
        assertTrue(fade.contains("textDeltas = pending.textDeltas,"))
        assertTrue(fade.contains("publishedDeltaSequences"))
        assertFalse(fade.contains("positionDelaysMs"))
        assertFalse(fade.contains("STREAM_DELTA_POSITION_WINDOW_MS"))
        assertTrue(fade.contains("startAlpha + (1f - startAlpha) * progress"))
        assertTrue(fade.contains("spatialAlpha + ageAlpha"))
        assertFalse(fade.contains("STREAM_TAIL_FADE_CODE_POINTS"))
        assertFalse(fade.contains("ArrivalRecord"))
        assertFalse(fade.contains("distributeArrivalBirths"))
        assertFalse(fade.contains("lastVisibleSourceOffset"))
        assertTrue(assets.contains("fade = nodeFade,"))
        assertFalse(assets.contains("enabled = fadeThisNode"))
    }

    @Test
    fun toolResultImageContextRowKeepsANonProtocolIdPrefix() {
        val root = sourceRoot()
        val toolMessages = source(root, "com/newoether/agora/api/util/ToolMessages.kt")

        // The API-only image-context row must never start with a protocol prefix: provider
        // serializers branch on tool_/result_ and would silently drop the row (view_image
        // results would display in the UI but never reach the model).
        assertTrue(toolMessages.contains("id = \"image_context_\$digest\""))
        assertFalse(toolMessages.contains("tool_image_context_"))
    }

    @Test
    fun providerCollectorsBindStableDiagnosticRequestKinds() {
        val root = sourceRoot()
        val title = source(root, "com/newoether/agora/viewmodel/ConversationTitleGenerator.kt")
        val transcription = source(root, "com/newoether/agora/viewmodel/TranscriptionManager.kt")
        val generation = source(root, "com/newoether/agora/viewmodel/GenerationManager.kt")
        val providerPass = source(
            root,
            "com/newoether/agora/viewmodel/ProviderPassEffectExecutor.kt",
        )

        assertTrue(title.contains("requestKind = \"title\""))
        assertTrue(title.contains("HttpClient.withStreamScope(scope = null, requestTrace = requestTrace)"))
        assertTrue(title.contains("requestTrace.recordParsedEvent(event)"))
        assertEquals(
            2,
            Regex("requestKind = \"transcription\"").findAll(transcription).count(),
        )
        assertEquals(
            2,
            Regex("requestTrace\\.recordParsedEvent\\(event\\)")
                .findAll(transcription).count(),
        )
        assertTrue(generation.contains("requestKind = \"tool_continuation\""))
        assertTrue(providerPass.contains("request.requestTrace?.recordParsedEvent(event)"))
    }

    @Test
    fun toolResultImageTranscriptionFollowsTheGenericDeclaredRule() {
        val root = sourceRoot()
        val toolProvider = source(root, "com/newoether/agora/tool/ToolProvider.kt")
        val shell = source(root, "com/newoether/agora/tool/ShellToolProvider.kt")
        val executor = source(
            root,
            "com/newoether/agora/viewmodel/GenerationToolBatchEffectExecutor.kt",
        )
        val manager = source(root, "com/newoether/agora/viewmodel/GenerationManager.kt")
        val transcription = source(root, "com/newoether/agora/viewmodel/TranscriptionManager.kt")
        val contracts = source(root, "com/newoether/agora/viewmodel/GenerationContracts.kt")

        // The tool declares intent via the result flag; the executor implements one generic
        // rule with no tool-name routing; the transcriber travels the per-generation call
        // chain; GenerationContext stays free of function fields.
        assertTrue(toolProvider.contains("val transcribeImages: Boolean = false"))
        assertTrue(shell.contains("transcribeImages = true"))
        assertTrue(executor.contains("result.transcribeImages && toolImage != null && transcriber != null"))
        assertFalse(executor.contains("\"view_image\""))
        assertFalse(executor.contains("[Image description]"))
        assertTrue(executor.contains("appendTranscriptionSegment("))
        assertTrue(executor.contains("toolImageTranscriber = request.toolImageTranscriber"))
        assertTrue(manager.contains("toolImageTranscriber ="))
        assertTrue(manager.contains("transcriptionManager.describeImageWithProgress("))
        assertTrue(transcription.contains("suspend fun describeImageWithProgress("))
        assertFalse(contracts.contains("toolImageTranscriber"))
        val toolMessages = source(root, "com/newoether/agora/api/util/ToolMessages.kt")
        assertTrue(toolMessages.contains("--- Image Transcription: view_image ---"))
        assertTrue(toolMessages.contains("transcriptionDescriptionsForBatch("))
        // Defect pins (owner device reports): transcription-enabled models never receive raw
        // images; the compact group title stays the transcription label while TOOL_CALLING;
        // the thinking block always announces the transcribing state.
        val pathBuilder = source(root, "com/newoether/agora/viewmodel/GenerationApiPathBuilder.kt")
        val titles = source(
            root,
            "com/newoether/agora/ui/chat/message/ThinkingSegmentPresentation.kt",
        )
        assertTrue(pathBuilder.contains("includeImages = !request.context.imageTranscriptionEnabled"))
        assertTrue(titles.contains("segs.any { it.type == \"transcription\" }"))
        assertTrue(transcription.contains("onProgress(context.getString(R.string.transcription_ellipsis_single))"))
    }

    @Test
    fun backgroundShellJobDoesNotOccupyTheGroupLoadingIndicator() {
        val root = sourceRoot()
        val presentation = source(
            root,
            "com/newoether/agora/ui/chat/message/ToolPresentation.kt",
        )
        val labels = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageItemToolLabels.kt",
        )

        // isActive drives the group loading bar; a detached background job must not occupy it.
        assertTrue(presentation.contains(
            "state == ToolPresentationState.CALLING ||\n            state == ToolPresentationState.RUNNING"
        ))
        assertFalse(presentation.contains(
            "state == ToolPresentationState.BACKGROUND_RUNNING\n"
        ))
        // The card still shows the background status (matched before isActive).
        assertTrue(labels.contains(
            "presentation.state == ToolPresentationState.BACKGROUND_RUNNING ->"
        ))
    }

    @Test
    fun ratingPaddingBelongsOnlyToDialogHost() {
        val root = sourceRoot()
        val mainActivity = source(root, "com/newoether/agora/MainActivity.kt")
        val rating = source(root, "com/newoether/agora/ui/settings/RatingForm.kt")
        val settings = source(root, "com/newoether/agora/ui/settings/SettingsAboutPage.kt")

        assertTrue(rating.contains("Modifier.clearFocusOnTap()"))
        assertFalse(rating.contains(".padding(horizontal = 24.dp, vertical = 20.dp)"))
        assertTrue(mainActivity.contains(
            "modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)"
        ))
        assertTrue(settings.contains(
            "modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)"
        ))
    }

    @Test
    fun expandedTimelineSegmentsKeepSpacingWithoutVisibleDividers() {
        val root = sourceRoot()
        val timeline = source(
            root,
            "com/newoether/agora/ui/chat/message/MessageItemTimeline.kt",
        )

        assertTrue(timeline.contains("if (idx < segs.lastIndex)"))
        assertTrue(timeline.contains("modifier = Modifier.padding(vertical = 2.dp)"))
        assertTrue(timeline.contains("color = Color.Transparent"))
        assertFalse(timeline.contains("outlineVariant.copy(alpha = 0.2f)"))
    }

    @Test
    fun toolCallCreationPublishesTheCompleteBatchBeforeExecution() {
        val manager = source(
            sourceRoot(),
            "com/newoether/agora/viewmodel/GenerationManager.kt",
        )
        val updateBranch = manager
            .substringAfter("is StreamEvent.ToolCallUpdate -> {")
            .substringBefore("is StreamEvent.ToolCallRequest -> {")
        val batchBranch = manager
            .substringAfter("is StreamEvent.ToolCallsRequest -> {")
            .substringBefore("\n                }\n\n                val now")

        assertTrue(updateBranch.contains("val created = upsertStreamingToolSegment("))
        assertTrue(updateBranch.contains("publishStreamUpdate(forceCheckpoint = created)"))
        val upsertIndex = batchBranch.indexOf("event.calls.forEach")
        val publishIndex = batchBranch.indexOf("publishStreamUpdate(forceCheckpoint = true)")
        assertTrue(upsertIndex >= 0)
        assertTrue(batchBranch.contains("upsertStreamingToolSegment("))
        assertTrue(publishIndex > upsertIndex)
        assertEquals(1, Regex("publishStreamUpdate\\(").findAll(batchBranch).count())
    }

    @Test
    fun developerCapturePageKeepsApprovedUiAndCanonicalOwners() {
        val capture = source(
            sourceRoot(),
            "com/newoether/agora/ui/settings/SettingsDeveloperCapturePage.kt",
        )
        val modes = capture
            .substringAfter("private enum class CaptureViewMode {")
            .substringBefore("}")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        val toolbar = capture
            .substringAfter("private fun CaptureToolbar(")
            .substringBefore("private fun CaptureIconAction(")

        assertEquals(listOf("SUMMARY,", "RAW,"), modes)
        listOf("Start", "Pause", "Clear", "Export").forEach { label ->
            assertEquals(1, Regex("label = \"$label\"").findAll(toolbar).count())
        }
        assertEquals(4, Regex("CaptureIconAction\\(").findAll(toolbar).count())
        assertEquals(3, Regex("DropdownMenuItem\\(").findAll(toolbar).count())
        assertTrue(toolbar.contains("DiagnosticExportFormat.RAW_JSON"))
        assertTrue(toolbar.contains("DiagnosticExportFormat.REDACTED_JSON"))
        assertTrue(toolbar.contains("DiagnosticExportFormat.SUMMARY_TEXT"))
        assertTrue(capture.contains("items(snapshot.events, key = DiagnosticEvent::sequence)"))
        assertFalse(capture.contains("snapshot.events.reversed"))
        assertFalse(capture.contains("snapshot.events.asReversed"))
        assertTrue(capture.contains("collectIsDraggedAsState()"))
        assertTrue(capture.contains("isDragged && listState.canScrollForward -> followLatest = false"))
        assertTrue(capture.contains("if (!followLatest && snapshot.events.isNotEmpty())"))
        assertTrue(capture.contains("val targetIndex = eventCount + 1"))
        assertFalse(capture.contains("val targetIndex = eventCount + 2"))
        assertEquals(2, Regex("scrollToLatestCaptureEvent\\(").findAll(capture).count())
        assertTrue(capture.contains("val eventDetails = remember(event, viewMode)"))
        assertTrue(capture.contains("val rowDetails = remember(event, viewMode)"))
        assertTrue(capture.contains("text = eventDetails"))
        assertTrue(capture.contains("text = checkNotNull(rowDetails)"))
        assertTrue(capture.contains("CaptureViewMode.SUMMARY -> buildString"))
        assertTrue(capture.contains("CaptureViewMode.RAW -> rawDetails()"))
        assertTrue(capture.contains("captureEventJson.encodeToString(DiagnosticEvent.serializer(), this)"))
        assertTrue(capture.contains("DeveloperDiagnostics.snapshots.collectAsState()"))
        assertTrue(capture.contains("DeveloperDiagnostics.startCapture()"))
        assertTrue(capture.contains("DeveloperDiagnostics.pauseCapture()"))
        assertTrue(capture.contains("DeveloperDiagnostics.clear()"))
        assertTrue(capture.contains("DeveloperDiagnostics.flush()"))
        assertFalse(capture.contains("DiagnosticCaptureStore"))
        assertFalse(capture.contains("DiagnosticEventBuffer"))
        assertFalse(capture.contains("noBackupFilesDir"))
    }

    @Test
    fun developerPageContainsOnlyApprovedHierarchyAndLocalCaptureRoute() {
        val page = source(
            sourceRoot(),
            "com/newoether/agora/ui/settings/SettingsDeveloperPage.kt",
        )
        val developerIndex = page.indexOf("R.string.settings_developer")
        val captureIndex = page.indexOf("R.string.developer_options_capture")
        val debugIndex = page.indexOf("Text(\"Debug Model\")")

        assertTrue(developerIndex >= 0)
        assertTrue(captureIndex > developerIndex)
        assertTrue(debugIndex > captureIndex)
        assertEquals(3, Regex("\\bSettingsItem\\(").findAll(page).count())
        assertEquals(2, Regex("\\bSwitch\\(").findAll(page).count())
        assertEquals(1, Regex("\\bSettingsGroup\\(").findAll(page).count())
        assertTrue(page.contains("var showCapturePage by rememberSaveable"))
        assertTrue(page.contains("BackHandler(enabled = showCapturePage)"))
        assertTrue(page.contains("SettingsDeveloperCapturePage("))
        assertTrue(page.contains("onBack = { showCapturePage = false }"))
        assertTrue(page.contains("viewModel.settings.debugModelEnabled.collectAsState()"))
        assertTrue(page.contains("viewModel.settings::setDebugModelEnabled"))

        val disableBody = page
            .substringAfter("DeveloperDiagnostics.disableAndClear()")
            .substringBefore("onDisabled()")
        assertTrue(disableBody.contains("setDeveloperOptionsEnabled(false)"))
        assertTrue(disableBody.contains(".join()"))

        listOf(
            "DeveloperConversationInspector",
            "DeveloperTestLab",
            "DiagnosticBundleExporter",
            "FileProvider",
            "DiagnosticTimelineItem",
            "shareDiagnosticBundle",
            "developer_options_timeline_group",
            "developer_options_inspector",
            "developer_options_test_lab",
            "DeveloperDiagnostics.startCapture()",
            "DeveloperDiagnostics.pauseCapture()",
            "DeveloperDiagnostics.clear()",
        ).forEach { obsolete ->
            assertFalse("Developer page still contains $obsolete", page.contains(obsolete))
        }
    }

    @Test
    fun skillsAreSavedCatalogToolsWithRequestResolvedPromptAndNoActiveSkill() {
        val root = sourceRoot()
        val manager = source(root, "com/newoether/agora/data/SkillManager.kt")
        val provider = source(root, "com/newoether/agora/tool/SkillToolProvider.kt")
        val builder = source(
            root,
            "com/newoether/agora/viewmodel/GenerationRequestBuilder.kt",
        )
        val exporter = source(root, "com/newoether/agora/data/DataExporter.kt")
        val importer = source(root, "com/newoether/agora/data/DataImporter.kt")
        val settings = source(
            root,
            "com/newoether/agora/ui/settings/SettingsSkillsPage.kt",
        )

        assertTrue(manager.contains("File(context.filesDir, \"skill_db\")"))
        assertTrue(manager.contains("fun catalog(): String"))
        assertFalse(manager.contains("active_skill"))
        assertTrue(provider.contains("list_skill_files"))
        assertTrue(provider.contains("read_skill_file"))
        assertTrue(provider.contains("create_skill_file"))
        assertTrue(provider.contains("edit_skill_file"))
        assertTrue(provider.contains("delete_skill_file"))
        assertFalse(provider.contains("update_active_skill"))
        assertTrue(builder.contains("skillCatalog = if (skillReadAccess) skillManager.catalog()"))
        assertTrue(builder.contains("if (includeSkillCatalog) skillManager.catalog() else \"\""))
        assertTrue(builder.contains("PredefinedVariables.SKILL_CATALOG to skillCatalog"))
        assertTrue(builder.contains("skillCatalog = skillCatalogDeferred.await()"))
        assertFalse(builder.contains("effectiveSystemPromptWithSkills"))
        assertTrue(exporter.contains("memories/skill_db/"))
        assertTrue(importer.contains("memories/skill_db/"))
        assertTrue(settings.contains("settings.accessSkills.collectAsState()"))
        assertFalse(settings.contains("Active Skill"))
    }

    private fun source(root: File, path: String): String =
        File(root, path).readText().replace("\r\n", "\n")

    private fun sourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate source root")
    }
}
