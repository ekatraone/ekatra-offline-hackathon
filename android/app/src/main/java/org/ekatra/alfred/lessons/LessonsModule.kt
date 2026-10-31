package org.ekatra.alfred.lessons

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.ekatra.alfred.BuildConfig
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LessonsBindingsModule {
    @Binds abstract fun bindApi(client: LearnerSyncClient): LearnerSyncApi
    @Binds abstract fun bindConnectivity(impl: AndroidConnectivity): Connectivity
}

@Module
@InstallIn(SingletonComponent::class)
object LessonsModule {

    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): TokenStore =
        PrefsTokenStore(context.getSharedPreferences(PrefsTokenStore.FILE, Context.MODE_PRIVATE))

    /**
     * Base URL and build-time token are blank in release builds. With a base URL but no token, the learner
     * signs in; with no base URL Lessons say they are not set up on this build.
     */
    @Provides
    @Singleton
    fun provideSyncConfig(store: TokenStore): SyncConfig =
        SyncConfig(BuildConfig.SYNC_BASE_URL, BuildConfig.DEMO_LEARNER_TOKEN, store)

    @Provides
    @Singleton
    fun provideLessonStore(@ApplicationContext context: Context): LessonStore =
        LessonStore(File(context.filesDir, "lessons"))

    @Provides
    @Singleton
    fun provideLessonProgressStore(@ApplicationContext context: Context): LessonProgressStore =
        LessonProgressStore(File(context.filesDir, "lesson_progress.json"))
}
