package dev.yipchat.live

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.yipchat.core.common.attempt
import dev.yipchat.core.data.history.HistoryRepository
import dev.yipchat.core.data.twitch.TwitchRepository

/** Fetches follows that are live; [TwitchRepository.followedLive] saves the snapshot. */
@HiltWorker
class LiveSnapshotWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val twitch: TwitchRepository,
    private val history: HistoryRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        attempt { twitch.followedLive() }
        attempt { history.pruneDeletedVods(twitch::vodExists) }
        refreshLiveSurfaces(applicationContext)
        return Result.success()
    }
}
