package com.newoether.agora.data.local.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration29To30Test {
    @Test
    fun migrationPreservesPayloadsAndInitializesReconcileGeneration() {
        val database = mockk<SupportSQLiteDatabase>()
        val statements = mutableListOf<String>()
        every { database.execSQL(capture(statements)) } just Runs

        MIGRATION_29_30.migrate(database)

        assertEquals(29, MIGRATION_29_30.startVersion)
        assertEquals(30, MIGRATION_29_30.endVersion)
        assertEquals(3, statements.size)
        val normalized = statements.joinToString(" ") { it.replace(Regex("\\s+"), " ") }
        assertTrue(normalized.contains("ADD COLUMN reconcileRevision INTEGER NOT NULL DEFAULT 0"))
        assertTrue(normalized.contains("SET reconcileRevision = sourceRevision"))
        assertTrue(normalized.contains("WHERE state = 'NEEDS_RECONCILE'"))
        assertTrue(normalized.contains("embeddings ADD COLUMN sourceFingerprint TEXT"))
    }

    @Test
    fun generatedRoomSchemaContainsReconcileAndFingerprintColumns() {
        val root = Json.parseToJsonElement(locateSchema().readText()).jsonObject
        val database = root.getValue("database").jsonObject
        assertEquals(30, database.getValue("version").jsonPrimitive.content.toInt())
        val entities = database.getValue("entities").jsonArray.associateBy {
            it.jsonObject.getValue("tableName").jsonPrimitive.content
        }

        assertTrue("reconcileRevision" in fields(entities.getValue("semantic_index_ledger").jsonObject))
        assertTrue("sourceFingerprint" in fields(entities.getValue("embeddings").jsonObject))
    }

    private fun fields(entity: kotlinx.serialization.json.JsonObject): Set<String> =
        entity.getValue("fields").jsonArray.mapTo(mutableSetOf()) {
            it.jsonObject.getValue("columnName").jsonPrimitive.content
        }

    private fun locateSchema(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/schemas/com.newoether.agora.data.local.ChatDatabase/30.json"),
                File(directory, "schemas/com.newoether.agora.data.local.ChatDatabase/30.json"),
            ).firstOrNull(File::isFile)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate Room schema 30")
    }
}
