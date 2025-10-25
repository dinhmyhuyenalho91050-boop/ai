package com.example.htmlapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.htmlapp.data.model.ChatMessageEntity
import com.example.htmlapp.data.model.ChatSessionEntity

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class HtmlAppDatabase : RoomDatabase() {
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var INSTANCE: HtmlAppDatabase? = null

        fun getInstance(context: Context): HtmlAppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): HtmlAppDatabase {
            return Room.databaseBuilder(
                context,
                HtmlAppDatabase::class.java,
                "htmlapp.db",
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
