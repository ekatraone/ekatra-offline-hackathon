package org.ekatra.alfred.lessons

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.ekatra.alfred.data.AppSettings
import org.ekatra.alfred.data.sync.SyncWorker
import java.io.File
import javax.inject.Inject

data class LocatedModel(val id: String, val file: File)

/** Finds the on-device model the chat already downloaded. Null when none is on disk. */
interface ModelLocator {
    fun locate(): LocatedModel?
}

class AppModelLocator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings
) : ModelLocator {
    override fun locate(): LocatedModel? {
        val selected = settings.getSelectedModel()
        val direct = File(context.filesDir, "models/${selected.filename}")
        if (direct.exists()) return LocatedModel(selected.id, direct)
        val recovered = settings.recoverDownloadedModel(context) ?: return null
        return LocatedModel(recovered.id, File(context.filesDir, "models/${recovered.filename}"))
    }
}

/** Durable "sync when there is a network" trigger, behind an interface so the ViewModel is testable. */
interface SyncTrigger {
    fun enqueue()
}

class WorkManagerSyncTrigger @Inject constructor(
    @ApplicationContext private val context: Context
) : SyncTrigger {
    override fun enqueue() = SyncWorker.enqueueLearnerSync(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LessonsSupportModule {
    @Binds abstract fun bindModelLocator(impl: AppModelLocator): ModelLocator
    @Binds abstract fun bindSyncTrigger(impl: WorkManagerSyncTrigger): SyncTrigger
}
