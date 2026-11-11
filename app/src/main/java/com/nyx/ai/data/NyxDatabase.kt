package com.nyx.ai.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ModelEndpointEntity::class,
        AgentEntity::class,
        WorkflowDefinitionEntity::class,
        ExecutionLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class NyxDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun modelEndpointDao(): ModelEndpointDao
    abstract fun agentDao(): AgentDao
    abstract fun workflowDao(): WorkflowDao
    abstract fun executionLogDao(): ExecutionLogDao

    companion object {
        @Volatile
        private var instance: NyxDatabase? = null

        /** Returns the process-wide local database, retaining no Activity context. */
        fun getInstance(context: Context): NyxDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NyxDatabase::class.java,
                    DATABASE_NAME
                ).build().also { instance = it }
            }

        private const val DATABASE_NAME = "nyx_local.db"
    }
}
