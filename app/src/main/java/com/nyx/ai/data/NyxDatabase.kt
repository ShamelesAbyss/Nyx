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
        ObjectiveArtifactEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class NyxDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun modelEndpointDao(): ModelEndpointDao
    abstract fun agentDao(): AgentDao
    abstract fun workflowDao(): WorkflowDao
    abstract fun executionLogDao(): ExecutionLogDao
    abstract fun objectiveDao(): ObjectiveDao

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

        @Volatile
        private var instance: NyxDatabase? = null

        /** Returns the process-wide local database, retaining no Activity context. */
        fun getInstance(context: Context): NyxDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NyxDatabase::class.java,
                    DATABASE_NAME
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }

        private const val DATABASE_NAME = "nyx_local.db"
    }
}
