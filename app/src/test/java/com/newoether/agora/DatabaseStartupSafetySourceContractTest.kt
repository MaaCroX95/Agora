package com.newoether.agora

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseStartupSafetySourceContractTest {
    @Test
    fun `production Room builder has no destructive fallback and validates before publish`() {
        val root = locateMainSourceRoot()
        val databaseSource = File(
            root,
            "com/newoether/agora/data/local/ChatDatabase.kt",
        ).readText()

        assertFalse(databaseSource.contains("fallbackToDestructiveMigration"))
        assertTrue(databaseSource.contains("check(compatibility.canOpen)"))
        assertTrue(databaseSource.contains("database.openHelper.writableDatabase"))

        val directBuilders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("ChatDatabase.build(") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()
        assertEquals(
            setOf("com/newoether/agora/AgoraApplication.kt"),
            directBuilders,
        )
    }

    @Test
    fun `quit is non destructive and clean delegates to the process gate`() {
        val root = locateMainSourceRoot()
        val activity = File(root, "com/newoether/agora/MainActivity.kt").readText()
        val application = File(root, "com/newoether/agora/AgoraApplication.kt").readText()

        assertTrue(activity.contains("TextButton(onClick = { activity?.finish() })"))
        assertTrue(activity.contains("agoraApplication.clearIncompatibleDatabase()"))
        assertFalse(activity.contains("deleteDatabase("))
        assertEquals(1, Regex("""deleteDatabase\(ChatDatabase\.DB_NAME\)""")
            .findAll(application).count())
    }

    @Test
    fun `background entry points wait for the same database gate`() {
        val root = locateMainSourceRoot()
        listOf(
            "com/newoether/agora/service/AutoBackupWorker.kt",
            "com/newoether/agora/service/BootReceiver.kt",
            "com/newoether/agora/service/EmbeddingCacheWorker.kt",
            "com/newoether/agora/service/LoopWorker.kt",
            "com/newoether/agora/service/TaskWorker.kt",
        ).forEach { path ->
            val source = File(root, path).readText()
            assertTrue("$path must await the process database gate", source.contains(".awaitContainer()"))
            assertFalse("$path must not access an ungated container", source.contains(".container"))
        }
    }

    @Test
    fun `diagnostic capture restores before the database startup gate`() {
        val application = File(
            locateMainSourceRoot(),
            "com/newoether/agora/AgoraApplication.kt",
        ).readText()
        val diagnosticsInitialize = application.indexOf("DeveloperDiagnostics.initialize(")
        val databaseInitialize = application.indexOf("startupGate.initialize()")

        assertTrue(diagnosticsInitialize >= 0)
        assertTrue(databaseInitialize > diagnosticsInitialize)
        assertTrue(application.contains("catch (cancelled: CancellationException)"))
        assertTrue(application.contains("Diagnostic capture initialization failed closed"))
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
