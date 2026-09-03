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
