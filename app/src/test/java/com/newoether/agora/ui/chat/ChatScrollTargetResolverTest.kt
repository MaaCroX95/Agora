package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import android.os.Trace
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.ui.common.NoOpAgoraHaptics
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.AnimatedScrollDestination
import com.newoether.agora.viewmodel.AnimatedScrollRequest
import com.newoether.agora.viewmodel.ConversationContextProjection
import com.newoether.agora.viewmodel.SwitchingRequestKind
import com.newoether.agora.viewmodel.SwitchingScrollRequest
import io.mockk.every
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ChatScrollTargetResolverTest {
    @Test
    fun readyRequestBeforeDestinationPublicationUsesTheDestinationHydration() =
        verifyDestinationHydration("old")

    @Test
    fun destinationPublishedBeforeReadyRequestSettlesNormally() =
        verifyDestinationHydration("new")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun verifyDestinationHydration(initialDestination: String) = runTest {
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } just Runs
        every { Trace.endSection() } just Runs
        val publishedId = mutableStateOf(initialDestination)
        val rows = mutableStateOf(listOf(message("user", Participant.USER)))
        val request = MutableStateFlow<SwitchingScrollRequest?>(
            SwitchingScrollRequest(1, "new", null, true, SwitchingRequestKind.CONVERSATION, true, false),
        )
        val viewModel = mockk<ChatViewModel>(relaxed = true)
        every { viewModel.switchingScrollRequest } returns request
        every { viewModel.conversationContextProjection } returns MutableStateFlow(
            ConversationContextProjection(conversationId = "new", completed = true),
        )
        val sentinel = mockk<LazyListItemInfo> {
            every { index } returns 1
            every { key } returns AbsoluteBottomSentinelKey
            every { offset } returns 700
            every { size } returns 1
        }
        val layout = mockk<LazyListLayoutInfo>(relaxed = true) {
            every { totalItemsCount } returns 2
            every { visibleItemsInfo } returns listOf(sentinel)
            every { viewportEndOffset } returns 800
        }
        val list = mockk<LazyListState>(relaxed = true) {
            every { layoutInfo } returns layout
            every { canScrollForward } returns false
        }
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(backgroundScope.coroutineContext + clock)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        try {
            composition.setContent {
                val id = publishedId.value
                val coordinator = remember(id) {
                    coordinator(list, id)
                }
                coordinator.BindTransitionEffects(
                    currentConversationId = id,
                    currentConversation = ChatConversation(id, id),
                    loadedMessagesConversationId = id,
                    messages = rows,
                    density = Density(1f),
                    motionPolicy = AgoraMotionPolicy.Default,
                    bottomBarHeight = 0.dp,
                    shareSelectionBarSpace = 0.dp,
                    imeBottomPx = 0,
                    viewModel = viewModel,
                    haptics = NoOpAgoraHaptics,
                )
            }
            runCurrent()
            publishedId.value = "new"
            Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(16_000_000)
            runCurrent()
            advanceTimeBy(256)
            runCurrent()

            verify(exactly = 1) { viewModel.completeSwitchingScroll(1) }
            verify(exactly = 0) { viewModel.failSwitchingScroll(any(), any()) }
        } finally {
            composition.dispose()
            recomposer.close()
            unmockkStatic(Trace::class)
        }
    }

    @Test
    fun `assistant target resolves to its parent user`() {
        val user = message("user", Participant.USER)
        val assistant = message("assistant", Participant.MODEL, parentId = user.id)

        assertEquals(user, resolveScrollTargetMessage(listOf(user, assistant), assistant.id))
    }

    @Test
    fun `compact target resolves to the compact itself`() {
        val user = message("user", Participant.USER)
        val compact = message(
            "${Constants.COMPACT_MSG_PREFIX}summary",
            Participant.MODEL,
            parentId = user.id,
        )

        assertEquals(compact, resolveScrollTargetMessage(listOf(user, compact), compact.id))
    }

    @Test
    fun `implicit target resolves to the latest user`() {
        val first = message("first", Participant.USER)
        val assistant = message("assistant", Participant.MODEL, parentId = first.id)
        val latest = message("latest", Participant.USER, parentId = assistant.id)

        assertEquals(latest, resolveScrollTargetMessage(listOf(first, assistant, latest), null))
    }

    @Test
    fun `missing explicit target remains unresolved`() {
        assertNull(resolveScrollTargetMessage(listOf(message("user", Participant.USER)), "missing"))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun sendDoesNotConsumeItsScrollWhileTheTargetIndexStillBelongsToTheOldSentinel() = runTest {
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } just Runs
        every { Trace.endSection() } just Runs
        val measuredCount = mutableIntStateOf(2)
        val layout = mockk<LazyListLayoutInfo>(relaxed = true) {
            every { totalItemsCount } answers { measuredCount.intValue }
        }
        val list = mockk<LazyListState>(relaxed = true) {
            every { layoutInfo } returns layout
        }
        val request = mutableStateOf<AnimatedScrollRequest?>(
            AnimatedScrollRequest(1, "new", "sent", AnimatedScrollDestination.ABSOLUTE_BOTTOM),
        )
        val rows = mutableStateOf(listOf(
            message("user", Participant.USER), message("sent", Participant.USER),
        ))
        val viewModel = mockk<ChatViewModel>(relaxed = true)
        every { viewModel.completeAnimatedScroll(1) } answers { request.value = null }
        val coordinator = coordinator(list, "new")
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(backgroundScope.coroutineContext + clock)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        try {
            composition.setContent {
                coordinator.BindRequestEffects(
                    currentConversationId = "new", isNewChatMode = false,
                    isLoading = true, isStopping = false, isSwitching = false,
                    conversationSearchActive = false, shareSelectionActive = false,
                    regenerationTransition = null, animatedScrollRequest = request.value,
                    messages = rows, density = Density(1f), motionPolicy = AgoraMotionPolicy.Default,
                    bottomBarHeight = 0.dp, shareSelectionBarSpace = 0.dp, viewModel = viewModel,
                )
            }
            runCurrent()
            verify(exactly = 0) { viewModel.completeAnimatedScroll(any()) }

            measuredCount.intValue = 3
            Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(16_000_000)
            runCurrent()
            verify(exactly = 1) { viewModel.completeAnimatedScroll(1) }
            coVerify(exactly = 1) { list.scroll(any(), any()) }
        } finally {
            composition.dispose()
            recomposer.close()
            unmockkStatic(Trace::class)
        }
    }

    private fun coordinator(list: LazyListState, id: String) = ChatScrollCoordinator(
        listState = list,
        absoluteBottomScrollPhaseState = mutableStateOf(AbsoluteBottomScrollPhase.IDLE),
        absoluteBottomRequestTokenState = mutableLongStateOf(0),
        absoluteBottomRequestFeedbackSpecState = mutableStateOf(DefaultFeedbackScrollSpec),
        isNearAbsoluteBottomState = mutableStateOf(true),
        isWithinAbsoluteBottomAttachThresholdState = mutableStateOf(true),
        composerInputFocusedState = mutableStateOf(false),
        imeBottomAnchorStateHolder = mutableStateOf(ImeBottomAnchorState(0, false)),
        viewportHeightState = mutableIntStateOf(800),
        messageHeights = mutableStateMapOf("user" to 80),
        hydrationRegistry = ConversationHydrationRegistry(id, mutableMapOf()).also {
            if (id == "new") it.record(id, "user")
        },
        messageLifecycleAppearanceRegistry = MessageLifecycleAppearanceRegistry(),
        streamingTailController = StreamingTailController(),
    )

    private fun message(
        id: String,
        participant: Participant,
        parentId: String? = null,
    ): ChatMessage = ChatMessage(
        id = id,
        participant = participant,
        text = id,
        parentId = parentId,
    )
}
