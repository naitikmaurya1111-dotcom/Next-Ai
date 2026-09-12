package com.agychat.app.di

import android.content.Context
import androidx.room.Room
import com.agychat.app.data.drive.GoogleDriveManager
import com.agychat.app.data.local.AppDatabase
import com.agychat.app.data.local.ChatDao
import com.agychat.app.data.network.AgyWebSocketClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)    // 0 = no timeout (WebSocket streaming)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)       // Keep WebSocket alive
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideAgyWebSocketClient(okHttpClient: OkHttpClient): AgyWebSocketClient {
        return AgyWebSocketClient(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "next_ai_db"
        )
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5
            )
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides
    @Singleton
    fun provideChatDao(database: AppDatabase): ChatDao {
        return database.chatDao()
    }

    @Provides
    @Singleton
    fun provideMemoryDao(database: AppDatabase): com.agychat.app.data.local.MemoryDao {
        return database.memoryDao()
    }

    @Provides
    @Singleton
    fun provideGoogleDriveManager(
        @ApplicationContext context: Context,
        chatDao: ChatDao,
        memoryDao: com.agychat.app.data.local.MemoryDao
    ): GoogleDriveManager {
        return GoogleDriveManager(context, chatDao, memoryDao)
    }
}
