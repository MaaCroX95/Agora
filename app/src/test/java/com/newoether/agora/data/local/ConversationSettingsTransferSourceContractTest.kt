package com.newoether.agora.data.local

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSettingsTransferSourceContractTest {
    @Test
    fun firstSendCreatesTheOutboxInsideTheRoomGraphTransaction() {
        val dao = sourceFile("app/src/main/java/com/newoether/agora/data/local/ChatDao.kt")
            .replace("\r\n", "\n")
        val transactionStart = dao.indexOf(
            "@Transaction\n    suspend fun createConversationRunWithMessages(",
        )
        assertTrue(transactionStart >= 0)
        val transaction = dao.substring(
            transactionStart,
            dao.indexOf("\n    @Transaction", transactionStart + 1),
        )

        val deleteNewChat = transaction.indexOf("deleteNewChatPersist()")
        val insertConversation = transaction.indexOf("upsertConversation(")
        val insertTransfer = transaction.indexOf("upsertConversationSettingsTransfer(")
        val createRunGraph = transaction.indexOf("return createRunWithMessages(")
        assertTrue(deleteNewChat >= 0)
        assertTrue(insertConversation > deleteNewChat)
        assertTrue(insertTransfer > insertConversation)
        assertTrue(createRunGraph > insertTransfer)
        assertTrue(transaction.contains("settingsJson = conversationSettingsJson"))
    }

    @Test
    fun nativeImportCommitsTheBatchOutboxWithTheGraphAndReconcilesAfterCommit() {
        val graphImporter = sourceFile(
            "app/src/main/java/com/newoether/agora/data/NativeConversationGraphImporter.kt",
        ).replace("\r\n", "\n")
        val importGraph = graphImporter.substringAfter("suspend fun importConversationGraph(")
            .substringBefore("// Internal data classes")
        val transaction = importGraph.indexOf("database.withTransaction {")
        val writeOutbox = importGraph.indexOf(
            "chatDao.upsertConversationSettingsImportTransfer(settingsTransfer)",
        )
        val returnTransfer = importGraph.indexOf("return settingsTransfer.transferId")
        assertTrue(transaction >= 0)
        assertTrue(writeOutbox > transaction)
        assertTrue(returnTransfer > writeOutbox)

        val dataImporter = sourceFile(
            "app/src/main/java/com/newoether/agora/data/DataImporter.kt",
        ).replace("\r\n", "\n")
        val conversations = dataImporter.substringAfter(
            "if (convDecision != null && convDecision != ImportStrategy.SKIP) {",
        ).substringBefore("if (memDecision != null && memDecision != ImportStrategy.SKIP) {")
        val finishPrevious = conversations.indexOf(
            "conversationSettingsTransfers.completePendingImport()",
        )
        val restoreMedia = conversations.indexOf(
            "conversationMediaRestorer.restoreConversationMedia(opened)",
        )
        val importGraphCall = conversations.indexOf(
            "conversationGraphImporter.importConversationGraph(",
        )
        val markCommitted = conversations.indexOf("graphCommitted = true")
        val completeSettings = conversations.indexOf(
            "conversationSettingsTransfers.completeImport(settingsTransferId)",
        )
        assertTrue(finishPrevious >= 0)
        assertTrue(restoreMedia > finishPrevious)
        assertTrue(importGraphCall > restoreMedia)
        assertTrue(markCommitted > importGraphCall)
        assertTrue(completeSettings > markCommitted)
        assertTrue(conversations.contains("if (!graphCommitted)"))
    }

    @Test
    fun cancelledBatchSettingsWriteRestoresPersistedStateBeforeRethrow() {
        val settings = sourceFile(
            "app/src/main/java/com/newoether/agora/data/repository/SettingsRepository.kt",
        ).replace("\r\n", "\n")
        val importWrite = settings.substringAfter(
            "suspend fun applyConversationSettingsImportAndAwait(",
        ).substringBefore("fun updateConversationSettings(")
        val cancellation = importWrite.substringAfter("catch (cancelled: CancellationException)")
            .substringBefore("catch (error: Exception)")

        assertTrue(cancellation.contains("withContext(NonCancellable)"))
        assertTrue(cancellation.contains("settingsManager.conversationSettings.first()"))
        assertTrue(cancellation.contains("conversationSettingsState::acceptPersisted"))
        assertTrue(cancellation.indexOf("acceptPersisted") < cancellation.indexOf("throw cancelled"))
    }

    @Test
    fun processStartupReplaysTheOutboxBeforeRecoveryAndScheduling() {
        val container = sourceFile("app/src/main/java/com/newoether/agora/di/AppContainer.kt")
            .replace("\r\n", "\n")
        val startup = container.substringAfter("suspend fun startProcessServices()")
            .substringBefore("\n    val taskRepository")

        val replay = startup.indexOf("conversationSettingsTransfers.replayPending()")
        val recovery = startup.indexOf("conversationRepository.ensureRunRecovery()")
        val scheduler = startup.indexOf("automationScheduler.start()")
        assertTrue(replay >= 0)
        assertTrue(recovery > replay)
        assertTrue(scheduler > recovery)
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relativePath).takeIf(File::isFile)?.let { return it.readText() }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
