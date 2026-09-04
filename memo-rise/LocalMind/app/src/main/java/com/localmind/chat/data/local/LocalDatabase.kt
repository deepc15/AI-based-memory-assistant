package com.localmind.chat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, MemoryEntity::class],
    version = 1,
    exportSchema = true
)
abstract class LocalDatabase : RoomDatabase() {

    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun memories(): MemoryDao

    companion object {
        private const val NAME = "localmind.db"

        @Volatile
        private var instance: LocalDatabase? = null

        fun get(context: Context): LocalDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): LocalDatabase {
            // Required by net.zetetic:sqlcipher-android before any database is opened.
            System.loadLibrary("sqlcipher")

            val factory = SupportOpenHelperFactory(DatabaseKeys.passphrase(context))

            return Room.databaseBuilder(context, LocalDatabase::class.java, NAME)
                .openHelperFactory(factory)
                // No .fallbackToDestructiveMigration(): losing a user's local-only
                // history to a schema bump would be unrecoverable. Write real migrations.
                .build()
        }
    }
}
