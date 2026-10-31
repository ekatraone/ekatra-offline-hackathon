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

    /** Blank in release builds, so Lessons shows "not configured" there. */
    @Provides
    @Singleton
    fun provideSyncConfig(): SyncConfig = SyncConfig(BuildConfig.SYNC_BASE_URL, BuildConfig.DEMO_LEARNER_TOKEN)

    @Provides
    @Singleton
    fun provideLessonStore(@ApplicationContext context: Context): LessonStore =
        LessonStore(File(context.filesDir, "lessons"))

    @Provides
    @Singleton
    fun provideLessonProgressStore(@ApplicationContext context: Context): LessonProgressStore =
        LessonProgressStore(File(context.filesDir, "lesson_progress.json"))
}
