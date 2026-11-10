package dev.yipchat.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

enum class HistoryKind { LIVE, VOD, CLIP }

@Entity(tableName = "history", indices = [Index("updatedAt")])
data class HistoryEntity(
    @PrimaryKey val key: String,
    val kind: HistoryKind,
    val contentId: String,
    val channelId: String,
    val channelLogin: String,
    val channelName: String,
    val title: String,
    val thumbnailUrl: String?,
    val positionSeconds: Long,
    val durationSeconds: Long,
    val updatedAt: Long,
)

@Entity(tableName = "emote_cache")
data class EmoteCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAt: Long,
)

@Entity(tableName = "live_notified")
data class LiveNotifiedEntity(
    @PrimaryKey val channelId: String,
    val streamId: String,
    val categoryName: String?,
    val notifiedAt: Long,
)

@Entity(tableName = "channel_prefs")
data class ChannelPrefsEntity(
    @PrimaryKey val channelId: String,
    val qualityId: String?,
    val chatHidden: Boolean = false,
    val muted: Boolean = false,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY updatedAt DESC LIMIT :limit")
    fun recent(limit: Int = 30): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE `key` = :key")
    suspend fun get(key: String): HistoryEntity?

    @Upsert suspend fun upsert(entity: HistoryEntity)

    @Query("DELETE FROM history WHERE `key` = :key") suspend fun delete(key: String)
    @Query("DELETE FROM history WHERE updatedAt < :before") suspend fun prune(before: Long)
    @Query("DELETE FROM history") suspend fun clear()
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM emote_cache WHERE `key` = :key") suspend fun get(key: String): EmoteCacheEntity?
    @Upsert suspend fun put(entity: EmoteCacheEntity)
    @Query("DELETE FROM emote_cache") suspend fun clear()
}

@Dao
interface NotifiedDao {
    @Query("SELECT * FROM live_notified") suspend fun all(): List<LiveNotifiedEntity>
    @Upsert suspend fun upsert(entity: LiveNotifiedEntity)
    @Query("DELETE FROM live_notified WHERE channelId NOT IN (:live)") suspend fun keepOnly(live: List<String>)
}

@Dao
interface ChannelPrefsDao {
    @Query("SELECT * FROM channel_prefs WHERE channelId = :id") suspend fun get(id: String): ChannelPrefsEntity?
    @Upsert suspend fun upsert(entity: ChannelPrefsEntity)
}

@Database(
    entities = [HistoryEntity::class, EmoteCacheEntity::class, LiveNotifiedEntity::class, ChannelPrefsEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class YipDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun cache(): CacheDao
    abstract fun notified(): NotifiedDao
    abstract fun channelPrefs(): ChannelPrefsDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun db(@ApplicationContext context: Context): YipDatabase =
        Room.databaseBuilder(context, YipDatabase::class.java, "yip.db").build()

    @Provides fun history(db: YipDatabase) = db.history()
    @Provides fun cache(db: YipDatabase) = db.cache()
    @Provides fun notified(db: YipDatabase) = db.notified()
    @Provides fun channelPrefs(db: YipDatabase) = db.channelPrefs()
}
