package com.nyx.ai.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ModelEndpointEntity::class,
        AgentEntity::class,
        WorkflowDefinitionEntity::class,
        ExecutionLogEntity::class,
        ObjectiveEntity::class,
        ObjectiveTaskEntity::class,
        ObjectiveEventEntity::class,
        ObjectiveArtifactEntity::class,
        TerminalCommandEntity::class,
        TaskEvidenceEntity::class,
        ContextRecordEntity::class,
        ExecutionLeaseEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class NyxDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun modelEndpointDao(): ModelEndpointDao
    abstract fun agentDao(): AgentDao
    abstract fun workflowDao(): WorkflowDao
    abstract fun executionLogDao(): ExecutionLogDao
    abstract fun objectiveDao(): ObjectiveDao
    abstract fun terminalCommandDao(): TerminalCommandDao
    abstract fun taskEvidenceDao(): TaskEvidenceDao
    abstract fun contextRecordDao(): ContextRecordDao
    abstract fun executionLeaseDao(): ExecutionLeaseDao

    companion object {
        /** Additive migration that preserves all v1 tables and their stored records. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `objectives` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `goal` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `agentId` TEXT,
                        `modelId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `error` TEXT,
                        PRIMARY KEY(`id`)
                    )""".trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_objectives_status` ON `objectives` (`status`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_objectives_updatedAt` ON `objectives` (`updatedAt`)")

                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `objective_tasks` (
                        `id` TEXT NOT NULL,
                        `objectiveId` TEXT NOT NULL,
                        `ordinal` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `instructions` TEXT NOT NULL,
                        `verification` TEXT,
                        `dependsOnJson` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `maxAttempts` INTEGER NOT NULL,
                        `outcome` TEXT,
                        `error` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        `checkpointJson` TEXT,
                        `verificationStatus` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`objectiveId`) REFERENCES `objectives`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_objective_tasks_objectiveId_ordinal` " +
                        "ON `objective_tasks` (`objectiveId`, `ordinal`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_objective_tasks_objectiveId_status` " +
                        "ON `objective_tasks` (`objectiveId`, `status`)"
                )

                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `objective_events` (
                        `id` TEXT NOT NULL,
                        `objectiveId` TEXT NOT NULL,
                        `taskId` TEXT,
                        `kind` TEXT NOT NULL,
                        `detail` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `toolName` TEXT,
                        `toolInput` TEXT,
                        `toolOutput` TEXT,
                        `verificationStatus` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`objectiveId`) REFERENCES `objectives`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`taskId`) REFERENCES `objective_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_objective_events_objectiveId_createdAt` " +
                        "ON `objective_events` (`objectiveId`, `createdAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_objective_events_taskId_createdAt` " +
                        "ON `objective_events` (`taskId`, `createdAt`)"
                )

                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `objective_artifacts` (
                        `id` TEXT NOT NULL,
                        `objectiveId` TEXT NOT NULL,
                        `taskId` TEXT,
                        `name` TEXT NOT NULL,
                        `contentType` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`objectiveId`) REFERENCES `objectives`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`taskId`) REFERENCES `objective_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_objective_artifacts_objectiveId_createdAt` " +
                        "ON `objective_artifacts` (`objectiveId`, `createdAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_objective_artifacts_taskId` " +
                        "ON `objective_artifacts` (`taskId`)"
                )
            }
        }

        /** Additive migration that preserves all v2 rows while extending objective/task metadata. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `objectives` ADD COLUMN `parallelism` INTEGER NOT NULL DEFAULT 1"
                )
                database.execSQL("ALTER TABLE `objectives` ADD COLUMN `leaseOwner` TEXT")
                database.execSQL("ALTER TABLE `objectives` ADD COLUMN `leaseExpiresAt` INTEGER")

                database.execSQL(
                    "ALTER TABLE `objective_tasks` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `objective_tasks` ADD COLUMN `workerKind` TEXT NOT NULL DEFAULT 'inference'"
                )
                database.execSQL("ALTER TABLE `objective_tasks` ADD COLUMN `idempotencyKey` TEXT")
                database.execSQL("ALTER TABLE `objective_tasks` ADD COLUMN `failureSignature` TEXT")
                database.execSQL("ALTER TABLE `objective_tasks` ADD COLUMN `evidenceJson` TEXT")
                database.execSQL("ALTER TABLE `objective_tasks` ADD COLUMN `startedAt` INTEGER")
                database.execSQL("ALTER TABLE `objective_tasks` ADD COLUMN `finishedAt` INTEGER")

                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `terminal_commands` (
                        `id` TEXT NOT NULL,
                        `objectiveId` TEXT,
                        `taskId` TEXT,
                        `environment` TEXT NOT NULL,
                        `commandPath` TEXT NOT NULL,
                        `argumentsJson` TEXT NOT NULL,
                        `workingDirectory` TEXT,
                        `status` TEXT NOT NULL,
                        `exitCode` INTEGER,
                        `stdout` TEXT NOT NULL,
                        `stderr` TEXT NOT NULL,
                        `timeoutMillis` INTEGER NOT NULL,
                        `approvedByUser` INTEGER NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `finishedAt` INTEGER,
                        `error` TEXT,
                        PRIMARY KEY(`id`)
                    )""".trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_terminal_commands_startedAt` " +
                        "ON `terminal_commands` (`startedAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_terminal_commands_objectiveId_startedAt` " +
                        "ON `terminal_commands` (`objectiveId`, `startedAt`)"
                )

                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `task_evidence` (
                        `id` TEXT NOT NULL,
                        `objectiveId` TEXT NOT NULL,
                        `taskId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `target` TEXT,
                        `expected` TEXT,
                        `actual` TEXT,
                        `passed` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`objectiveId`) REFERENCES `objectives`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`taskId`) REFERENCES `objective_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_evidence_taskId_createdAt` " +
                        "ON `task_evidence` (`taskId`, `createdAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_evidence_objectiveId_createdAt` " +
                        "ON `task_evidence` (`objectiveId`, `createdAt`)"
                )

                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `context_records` (
                        `id` TEXT NOT NULL,
                        `objectiveId` TEXT NOT NULL,
                        `taskId` TEXT,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`objectiveId`) REFERENCES `objectives`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_context_records_objectiveId_createdAt` " +
                        "ON `context_records` (`objectiveId`, `createdAt`)"
                )

                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `execution_leases` (
                        `objectiveId` TEXT NOT NULL,
                        `ownerId` TEXT NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `heartbeatAt` INTEGER NOT NULL,
                        PRIMARY KEY(`objectiveId`)
                    )""".trimIndent()
                )
            }
        }

        @Volatile
        private var instance: NyxDatabase? = null

        /** Returns the process-wide local database, retaining no Activity context. */
        fun getInstance(context: Context): NyxDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NyxDatabase::class.java,
                    DATABASE_NAME
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }

        private const val DATABASE_NAME = "nyx_local.db"
    }
}
