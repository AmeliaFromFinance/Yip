package dev.yipchat.core.datastore

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.yipchat.core.model.AccountSecrets
import dev.yipchat.core.model.AccountsState
import dev.yipchat.core.model.AppSettings
import dev.yipchat.core.model.RecentSearches
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Singleton
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import timber.log.Timber

internal val storeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

/** Plain JSON serializer for non-secret stores. */
internal class JsonSerializer<T>(private val serializer: KSerializer<T>, override val defaultValue: T) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T = try {
        storeJson.decodeFromString(serializer, input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Bad store file", e)
    }

    override suspend fun writeTo(t: T, output: OutputStream) =
        output.write(storeJson.encodeToString(serializer, t).encodeToByteArray())
}

@Serializable
data class SecretsFile(val byUserId: Map<String, AccountSecrets> = emptyMap())

/** Secrets are encrypted at rest with a Keystore key; a failed decrypt resets to empty. */
internal class EncryptedSerializer(private val cipher: KeystoreCipher) : Serializer<SecretsFile> {
    override val defaultValue = SecretsFile()

    override suspend fun readFrom(input: InputStream): SecretsFile {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        return try {
            storeJson.decodeFromString(SecretsFile.serializer(), cipher.decrypt(bytes).decodeToString())
        } catch (e: Exception) {
            Timber.w(e, "Secrets unreadable; signing out")
            defaultValue
        }
    }

    override suspend fun writeTo(t: SecretsFile, output: OutputStream) =
        output.write(cipher.encrypt(storeJson.encodeToString(SecretsFile.serializer(), t).encodeToByteArray()))
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides @Singleton
    fun settings(@ApplicationContext context: Context): DataStore<AppSettings> = DataStoreFactory.create(
        serializer = JsonSerializer(AppSettings.serializer(), AppSettings()),
        corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
        produceFile = { File(context.filesDir, "datastore/settings.json") },
    )

    @Provides @Singleton
    fun accounts(@ApplicationContext context: Context): DataStore<AccountsState> = DataStoreFactory.create(
        serializer = JsonSerializer(AccountsState.serializer(), AccountsState()),
        corruptionHandler = ReplaceFileCorruptionHandler { AccountsState() },
        produceFile = { File(context.filesDir, "datastore/accounts.json") },
    )

    @Provides @Singleton
    fun recentSearches(@ApplicationContext context: Context): DataStore<RecentSearches> = DataStoreFactory.create(
        serializer = JsonSerializer(RecentSearches.serializer(), RecentSearches()),
        corruptionHandler = ReplaceFileCorruptionHandler { RecentSearches() },
        produceFile = { File(context.filesDir, "datastore/recent_searches.json") },
    )

    @Provides @Singleton
    fun secrets(@ApplicationContext context: Context): DataStore<SecretsFile> = DataStoreFactory.create(
        serializer = EncryptedSerializer(KeystoreCipher("yip_secrets_v1")),
        produceFile = { File(context.noBackupFilesDir, "secrets.bin") },
    )
}
