package com.nyx.ai.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NyxDatabaseMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun migrationFromV1PreservesExistingRowsAndCreatesObjectiveTables() = runBlocking {
        val databaseName = "nyx_migration_test_${System.nanoTime()}.db"
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        createVersionOneDatabase(databaseFile)

        val roomDatabase = Room.databaseBuilder(context, NyxDatabase::class.java, databaseName)
            .addMigrations(NyxDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val conversations = roomDatabase.conversationDao()
            val endpoints = roomDatabase.modelEndpointDao()
            val agents = roomDatabase.agentDao()
            val workflows = roomDatabase.workflowDao()
            val executionLogs = roomDatabase.executionLogDao()

            assertEquals("Legacy conversation", conversations.getConversation("conversation-v1")?.title)
            val preservedMessage = conversations.getMessages("conversation-v1").single()
            assertEquals("message-v1", preservedMessage.id)
            assertEquals("Legacy chat history", preservedMessage.content)

            val preservedEndpoint = endpoints.getById("endpoint-v1")
            assertNotNull(preservedEndpoint)
            assertEquals("opaque-credential-reference", preservedEndpoint?.credentialReference)
            assertEquals("legacy-model", preservedEndpoint?.modelId)
            val preservedAgent = agents.getById("agent-v1")
            assertEquals("Preserve me", preservedAgent?.instructions)
            assertEquals("endpoint-v1", preservedAgent?.endpointId)
            val preservedWorkflow = workflows.getById("workflow-v1")
            assertEquals("Legacy workflow", preservedWorkflow?.name)
            assertEquals("{}", preservedWorkflow?.definitionJson)
            val preservedExecution = executionLogs.getById("execution-v1")
            assertEquals("completed", preservedExecution?.status)
            assertEquals("kept output", preservedExecution?.outputText)

            val objectiveDao = roomDatabase.objectiveDao()
            val objective = ObjectiveEntity(
                id = "objective-v2",
                title = "Persisted objective",
                goal = "Exercise the migrated tables",
                status = "running",
                createdAt = 1_000L,
                updatedAt = 1_100L
            )
            objectiveDao.upsertObjective(objective)
            val task = ObjectiveTaskEntity(
                id = "task-v2",
                objectiveId = objective.id,
                ordinal = 0,
                title = "Run tool",
                instructions = "Call the test tool and preserve a checkpoint",
                verification = "Check that the output exists",
                dependsOnJson = "[]",
                status = "running",
                attemptCount = 1,
                maxAttempts = 3,
                checkpointJson = "{\"cursor\":1}",
                verificationStatus = "pending",
                updatedAt = 1_200L
            )
            objectiveDao.upsertTask(task)
            objectiveDao.appendEvent(
                ObjectiveEventEntity(
                    id = "event-v2",
                    objectiveId = objective.id,
                    taskId = task.id,
                    kind = "tool_observation",
                    detail = "Tool returned a successful observation",
                    createdAt = 1_300L,
                    toolName = "test-tool",
                    toolInput = "{\"query\":\"hello\"}",
                    toolOutput = "world",
                    verificationStatus = "passed"
                )
            )
            objectiveDao.upsertArtifact(
                ObjectiveArtifactEntity(
                    id = "artifact-v2",
                    objectiveId = objective.id,
                    taskId = task.id,
                    name = "result.txt",
                    contentType = "text/plain",
                    content = "Persisted artifact",
                    createdAt = 1_400L
                )
            )

            assertEquals(objective, objectiveDao.getObjective(objective.id))
            assertEquals(task, objectiveDao.getTasks(objective.id).single())
            assertEquals("task-v2", objectiveDao.observeTasks(objective.id).first().single().id)
            val observedEvent = objectiveDao.observeEvents(objective.id).first().single()
            assertEquals("test-tool", observedEvent.toolName)
            assertEquals("world", observedEvent.toolOutput)
            assertEquals("passed", observedEvent.verificationStatus)
            assertEquals("Persisted artifact", objectiveDao.getArtifacts(objective.id).single().content)
            assertEquals("artifact-v2", objectiveDao.observeArtifacts(objective.id).first().single().id)
            assertEquals(objective, objectiveDao.observeAll().first().single())
        } finally {
            roomDatabase.close()
            context.deleteDatabase(databaseName)
            databaseFile.delete()
            File(databaseFile.path + "-wal").delete()
            File(databaseFile.path + "-shm").delete()
        }
    }

    /** Creates the exact tables, columns, foreign key, and indices declared by the v1 entities. */
    private fun createVersionOneDatabase(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `conversations` (
                    `id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `messages` (
                    `id` TEXT NOT NULL,
                    `conversationId` TEXT NOT NULL,
                    `role` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `modelId` TEXT,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_conversationId_createdAt` " +
                    "ON `messages` (`conversationId`, `createdAt`)"
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `model_endpoints` (
                    `id` TEXT NOT NULL,
                    `displayName` TEXT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `baseUrl` TEXT NOT NULL,
                    `modelId` TEXT NOT NULL,
                    `credentialReference` TEXT,
                    `configurationJson` TEXT,
                    `isEnabled` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `agents` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `description` TEXT,
                    `instructions` TEXT NOT NULL,
                    `endpointId` TEXT,
                    `configurationJson` TEXT,
                    `isEnabled` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agents_endpointId` ON `agents` (`endpointId`)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `workflow_definitions` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `description` TEXT,
                    `definitionJson` TEXT NOT NULL,
                    `isEnabled` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `execution_logs` (
                    `id` TEXT NOT NULL,
                    `workflowId` TEXT,
                    `taskId` TEXT,
                    `status` TEXT NOT NULL,
                    `inputJson` TEXT,
                    `outputText` TEXT,
                    `errorMessage` TEXT,
                    `startedAt` INTEGER,
                    `finishedAt` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_execution_logs_workflowId_createdAt` " +
                    "ON `execution_logs` (`workflowId`, `createdAt`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_execution_logs_taskId_createdAt` " +
                    "ON `execution_logs` (`taskId`, `createdAt`)"
            )

            db.execSQL(
                "INSERT INTO `conversations` (`id`, `title`, `createdAt`, `updatedAt`) " +
                    "VALUES ('conversation-v1', 'Legacy conversation', 100, 200)"
            )
            db.execSQL(
                "INSERT INTO `messages` (`id`, `conversationId`, `role`, `content`, `createdAt`, `modelId`) " +
                    "VALUES ('message-v1', 'conversation-v1', 'user', 'Legacy chat history', 150, 'legacy-model')"
            )
            db.execSQL(
                "INSERT INTO `model_endpoints` " +
                    "(`id`, `displayName`, `provider`, `baseUrl`, `modelId`, `credentialReference`, `configurationJson`, `isEnabled`, `createdAt`, `updatedAt`) " +
                    "VALUES ('endpoint-v1', 'Legacy endpoint', 'test-provider', 'https://example.invalid', 'legacy-model', 'opaque-credential-reference', NULL, 1, 100, 200)"
            )
            db.execSQL(
                "INSERT INTO `agents` " +
                    "(`id`, `name`, `description`, `instructions`, `endpointId`, `configurationJson`, `isEnabled`, `createdAt`, `updatedAt`) " +
                    "VALUES ('agent-v1', 'Legacy agent', NULL, 'Preserve me', 'endpoint-v1', NULL, 1, 100, 200)"
            )
            db.execSQL(
                "INSERT INTO `workflow_definitions` " +
                    "(`id`, `name`, `description`, `definitionJson`, `isEnabled`, `createdAt`, `updatedAt`) " +
                    "VALUES ('workflow-v1', 'Legacy workflow', NULL, '{}', 1, 100, 200)"
            )
            db.execSQL(
                "INSERT INTO `execution_logs` " +
                    "(`id`, `workflowId`, `taskId`, `status`, `inputJson`, `outputText`, `errorMessage`, `startedAt`, `finishedAt`, `createdAt`) " +
                    "VALUES ('execution-v1', 'workflow-v1', NULL, 'completed', '{}', 'kept output', NULL, 110, 190, 200)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `room_master_table` " +
                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            db.execSQL(
                "INSERT OR REPLACE INTO `room_master_table` (id, identity_hash) " +
                    "VALUES (42, '194687fca39f550129ac9623ab392a7b')"
            )
            db.version = 1
        } finally {
            db.close()
        }
    }
}
