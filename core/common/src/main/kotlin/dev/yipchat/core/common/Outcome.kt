package dev.yipchat.core.common

import kotlinx.coroutines.CancellationException

/** Like runCatching, but never swallows coroutine cancellation. */
inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

class AppException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NETWORK, AUTH, NOT_FOUND, RATE_LIMITED, PLAYBACK_BLOCKED, CONFIG, UNKNOWN }
}
