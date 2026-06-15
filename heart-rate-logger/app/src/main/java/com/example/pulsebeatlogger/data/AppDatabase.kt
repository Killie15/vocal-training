package com.example.pulsebeatlogger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SkillSession::class, LearningItem::class, SensorEvent::class, UserGoal::class, SystemMessage::class, SystemMemoryChunk::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun skillSessionDao(): SkillSessionDao
    abstract fun learningItemDao(): LearningItemDao
    abstract fun sensorEventDao(): SensorEventDao
    abstract fun userGoalDao(): UserGoalDao
    abstract fun systemMessageDao(): SystemMessageDao
    abstract fun systemMemoryChunkDao(): SystemMemoryChunkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1 → v2: added sensor_events table */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sensor_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        sensorType TEXT NOT NULL,
                        skillName TEXT NOT NULL,
                        sessionId TEXT NOT NULL,
                        valueJson TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sensor_events_timestamp ON sensor_events(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sensor_events_sensorType ON sensor_events(sensorType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sensor_events_sessionId ON sensor_events(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sensor_events_skillName ON sensor_events(skillName)")
            }
        }

        /** v2 → v3: added consecutiveFails and regenerationCount to learning_items */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE learning_items ADD COLUMN consecutiveFails INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE learning_items ADD COLUMN regenerationCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 → v4: The System — persistent goals + chat log */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_goals (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        status TEXT NOT NULL,
                        priority TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        targetDateMs INTEGER NOT NULL,
                        reminderHour INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS system_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        role TEXT NOT NULL,
                        text TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /** v4 → v5: chunked memory bank for The System */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS system_memory_chunks (
                        id TEXT PRIMARY KEY NOT NULL,
                        content TEXT NOT NULL,
                        category TEXT NOT NULL,
                        keywords TEXT NOT NULL,
                        userSnippet TEXT NOT NULL,
                        systemSnippet TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pulsebeat_learner.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
