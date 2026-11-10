package dev.yipchat.core.network

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import timber.log.Timber

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SnakeJson

const val USER_AGENT = "Yip/0.1 (Android; +https://yipchat.dev)"

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Provides @Singleton @SnakeJson
    fun snakeJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    @Provides @Singleton
    fun okHttp(@ApplicationContext context: Context): OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "http"), 32L * 1024 * 1024))
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followSslRedirects(true)
        .build()

    @Provides @Singleton
    fun httpClient(okHttp: OkHttpClient, json: Json): HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
        }
        install(HttpRequestRetry) {
            retryOnExceptionOrServerErrors(maxRetries = 2)
            exponentialDelay()
        }
        install(Logging) {
            level = LogLevel.INFO
            logger = object : Logger {
                override fun log(message: String) = Timber.tag("http").v(message)
            }
            // Never log auth headers or tokens.
            sanitizeHeader { it == HttpHeaders.Authorization || it.equals("Client-Integrity", true) }
        }
        defaultRequest { headers.append(HttpHeaders.UserAgent, USER_AGENT) }
    }
}
