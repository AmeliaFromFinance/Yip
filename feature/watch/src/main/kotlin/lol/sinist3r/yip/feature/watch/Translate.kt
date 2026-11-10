package lol.sinist3r.yip.feature.watch

import android.content.Context
import android.icu.util.ULocale
import android.os.Build
import android.os.CancellationSignal
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationSpec
import android.view.translation.Translator
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

sealed interface Translation {
    data object Working : Translation
    data class Done(val text: String, val fromLanguage: String) : Translation
    /** [fix] opens the system screen to download the language, when that would help. */
    data class Failed(val reason: String, val fix: android.app.PendingIntent? = null) : Translation
}

/** Android's built-in on-device translator (Android 12+). Nothing leaves the phone. */
@Singleton
class ChatTranslator @Inject constructor(@ApplicationContext private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()

    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.getSystemService(TranslationManager::class.java) != null

    /** Into the phone's language. */
    suspend fun translate(text: String, target: Locale = Locale.getDefault()): Translation =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) Translation.Failed("Needs Android 12 or newer")
        else withContext(Dispatchers.IO) { runCatching { translate31(text, target) }.getOrElse { Translation.Failed(it.message ?: "Couldn't translate") } }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun translate31(text: String, target: Locale): Translation {
        val manager = context.getSystemService(TranslationManager::class.java) ?: return Translation.Failed("This phone has no built-in translator")
        val source = detect(text) ?: return Translation.Failed("Couldn't tell which language this is")
        if (source.language == target.language) return Translation.Failed("Already in ${target.displayLanguage}")

        val format = TranslationSpec.DATA_FORMAT_TEXT
        val cap = manager.getOnDeviceTranslationCapabilities(format, format).firstOrNull {
            it.sourceSpec.locale.language == source.language && it.targetSpec.locale.language == target.language
        }
        val name = source.toLocale().displayLanguage
        when (cap?.state) {
            null, TranslationCapability.STATE_NOT_AVAILABLE -> return Translation.Failed("$name isn't supported by this phone's translator")
            TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD -> return Translation.Failed("Download $name in your phone's translation settings first", manager.onDeviceTranslationSettingsActivityIntent)
            TranslationCapability.STATE_DOWNLOADING -> return Translation.Failed("$name is still downloading, try again soon")
        }

        val spec = TranslationContext.Builder(TranslationSpec(cap.sourceSpec.locale, format), TranslationSpec(cap.targetSpec.locale, format)).build()
        val translator = suspendCancellableCoroutine<Translator?> { cont ->
            manager.createOnDeviceTranslator(spec, executor) { t -> if (cont.isActive) cont.resume(t) }
        } ?: return Translation.Failed("The translator isn't available right now")
        try {
            val request = TranslationRequest.Builder().setTranslationRequestValues(listOf(TranslationRequestValue.forText(text))).build()
            val result = suspendCancellableCoroutine<String?> { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                translator.translate(request, cancel, executor) { response ->
                    val values = response.translationResponseValues
                    if (cont.isActive) cont.resume(if (values.size() > 0) values.valueAt(0).text?.toString() else null)
                }
            }
            return result?.takeIf { it.isNotBlank() }?.let { Translation.Done(it, name) } ?: Translation.Failed("No translation came back")
        } finally {
            translator.destroy()
        }
    }

    /** On-device language detection; blocking, so call off the main thread. */
    private fun detect(text: String): ULocale? {
        val classifier = context.getSystemService(TextClassificationManager::class.java)?.textClassifier ?: return null
        val result = classifier.detectLanguage(TextLanguage.Request.Builder(text).build())
        if (result.localeHypothesisCount == 0) return null
        val top = result.getLocale(0)
        return top.takeIf { result.getConfidenceScore(it) >= 0.5f }
    }
}

/**
 * Opens the system screen for downloading translation languages. Android 14+ drops activity starts
 * from a PendingIntent unless the sender opts in, which made the button do nothing.
 */
fun openTranslationSettings(context: Context, intent: android.app.PendingIntent) {
    val options = if (Build.VERSION.SDK_INT >= 34) {
        android.app.ActivityOptions.makeBasic().apply {
            @Suppress("DEPRECATION")
            setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }.toBundle()
    } else null
    val sent = runCatching { intent.send(context, 0, null, null, null, null, options) }.isSuccess
    if (!sent) {
        // Fallback: language settings, where Pixels keep Live Translate.
        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        android.widget.Toast.makeText(context, "Open Live Translate in your language settings to download languages", android.widget.Toast.LENGTH_LONG).show()
    }
}
