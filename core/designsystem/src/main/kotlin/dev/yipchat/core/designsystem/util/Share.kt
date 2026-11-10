package dev.yipchat.core.designsystem.util

import android.content.Context
import android.content.Intent
import dev.yipchat.core.model.Clip

fun Context.shareLink(url: String, title: String? = null) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    title?.let { send.putExtra(Intent.EXTRA_SUBJECT, it).putExtra(Intent.EXTRA_TITLE, it) }
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun clipLink(slug: String) = "https://clips.twitch.tv/$slug"

val Clip.shareUrl: String get() = url.ifBlank { clipLink(id) }
