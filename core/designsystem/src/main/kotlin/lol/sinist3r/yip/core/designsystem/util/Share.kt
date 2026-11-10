package lol.sinist3r.yip.core.designsystem.util

import android.content.Context
import android.content.Intent
import lol.sinist3r.yip.core.model.Clip

/** Opens the system share sheet with a link. */
fun Context.shareLink(url: String, title: String? = null) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    title?.let { send.putExtra(Intent.EXTRA_SUBJECT, it).putExtra(Intent.EXTRA_TITLE, it) }
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun clipLink(slug: String) = "https://clips.twitch.tv/$slug"

val Clip.shareUrl: String get() = url.ifBlank { clipLink(id) }
