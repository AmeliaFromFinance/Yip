package lol.sinist3r.yip.core.designsystem.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.time.toJavaInstant
import lol.sinist3r.yip.core.designsystem.theme.YipTheme
import lol.sinist3r.yip.core.model.Badge
import lol.sinist3r.yip.core.model.ChatItem
import lol.sinist3r.yip.core.model.ChatSettings
import lol.sinist3r.yip.core.model.ChatToken
import lol.sinist3r.yip.core.model.DeletedMessages
import lol.sinist3r.yip.core.model.Emote
import lol.sinist3r.yip.core.model.HighlightKind
import lol.sinist3r.yip.core.model.PaintMode
import lol.sinist3r.yip.core.model.ThirdPartySettings
import lol.sinist3r.yip.core.model.TimestampStyle
import lol.sinist3r.yip.core.model.UsernameStyle

@Immutable
data class ChatRenderOptions(
    val chat: ChatSettings,
    val thirdParty: ThirdPartySettings,
    val animate: Boolean,
)

interface ChatActions {
    fun onUser(login: String, userId: String?) {}
    fun onEmote(emote: Emote) {}
    fun onMessageLongPress(message: ChatItem.Message) {}
    fun onLink(url: String) {}
}

private val time24 = DateTimeFormatter.ofPattern("HH:mm")
private val time24s = DateTimeFormatter.ofPattern("HH:mm:ss")
private val time12 = DateTimeFormatter.ofPattern("h:mm")
private val time12s = DateTimeFormatter.ofPattern("h:mm:ss")

private fun ChatItem.timeLabel(style: TimestampStyle, seconds: Boolean): String? {
    val t = timestamp.toJavaInstant().atZone(ZoneId.systemDefault())
    return when (style) {
        TimestampStyle.OFF -> null
        TimestampStyle.H24 -> (if (seconds) time24s else time24).format(t)
        TimestampStyle.H12 -> (if (seconds) time12s else time12).format(t)
        TimestampStyle.RELATIVE -> {
            val s = (Clock.System.now() - timestamp).inWholeSeconds
            when {
                s < 60 -> "now"
                s < 3600 -> "${s / 60}m"
                else -> "${s / 3600}h"
            }
        }
    }
}

@Composable
fun ChatRow(item: ChatItem, options: ChatRenderOptions, actions: ChatActions, alternate: Boolean, modifier: Modifier = Modifier) {
    val chat = options.chat
    val extras = YipTheme.extras
    val bg = when {
        item is ChatItem.Message && item.highlight == HighlightKind.MENTION -> Color(chat.highlightColorArgb).copy(alpha = 0.22f)
        item is ChatItem.Message && item.highlight == HighlightKind.KEYWORD -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
        item is ChatItem.Message && item.highlight == HighlightKind.USER -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
        item is ChatItem.Message && item.highlight == HighlightKind.REDEMPTION -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        item is ChatItem.Notice -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        alternate && chat.alternateRows -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
        else -> Color.Transparent
    }
    val vertical = (chat.fontSizeSp * (chat.lineSpacing - 1f) / 2f).coerceAtLeast(1f).dp
    Box(
        modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(RoundedCornerShape(6.dp)).background(bg)
            .padding(horizontal = 6.dp, vertical = vertical),
    ) {
        when (item) {
            is ChatItem.Message -> MessageText(item, options, actions)
            is ChatItem.Notice -> Column {
                Text(item.text, style = MaterialTheme.typography.bodySmall.copy(fontSize = (chat.fontSizeSp - 0.5f).sp), fontWeight = FontWeight.Medium)
                item.message?.let { MessageText(it, options, actions) }
            }
            is ChatItem.System -> Text(
                item.text,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = (chat.fontSizeSp - 1f).sp, fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (chat.separators) Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).background(extras.divider).padding(top = 0.5.dp))
}

@Composable
private fun MessageText(m: ChatItem.Message, options: ChatRenderOptions, actions: ChatActions) {
    val chat = options.chat
    val tp = options.thirdParty
    val dark = YipTheme.extras.isDark
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val fontSize = chat.fontSizeSp.sp
    val emoteHeight = (chat.fontSizeSp * 2f * chat.emoteScale)
    val badgeSize = chat.fontSizeSp * 1.3f
    val baseStyle = TextStyle(fontSize = fontSize, lineHeight = (chat.fontSizeSp * chat.lineSpacing).sp, color = onSurface)
    val nameColor = if (chat.showNameColors) {
        if (chat.readableColors) readableColor(m.user.colorArgb, dark, primary) else m.user.colorArgb?.let { Color(it) } ?: primary
    } else onSurface
    val deleted = m.isDeleted && chat.deletedMessages != DeletedMessages.HIDE
    if (m.isDeleted && chat.deletedMessages == DeletedMessages.HIDE) return

    val name = when (chat.usernameStyle) {
        UsernameStyle.DISPLAY -> m.user.displayName
        UsernameStyle.LOGIN -> m.user.login
        UsernameStyle.BOTH -> if (m.user.displayName.equals(m.user.login, true)) m.user.displayName else "${m.user.displayName} (${m.user.login})"
    }
    val time = m.timeLabel(chat.timestamps, chat.timestampSeconds)

    val (text, inline) = remember(m, options, nameColor, dark) {
        val inline = HashMap<String, InlineTextContent>()
        val paint = m.paint?.takeIf { tp.seventvPaints != PaintMode.OFF }
        // Position the paint gradient over the name only.
        val prefixPx = with(density) {
            val t = time?.let { measurer.measure(AnnotatedString("$it "), baseStyle.copy(fontSize = (chat.fontSizeSp - 2).sp)).size.width.toFloat() } ?: 0f
            t + m.badges.size * (badgeSize + 3).sp.toPx()
        }
        val nameStyle = baseStyle.copy(fontWeight = FontWeight.Bold)
        val nameWidth = paint?.let { measurer.measure(AnnotatedString(name), nameStyle).size.width.toFloat() } ?: 0f
        val lineH = with(density) { (chat.fontSizeSp * chat.lineSpacing).sp.toPx() }

        val annotated = buildAnnotatedString {
            if (m.replyParentLogin != null && chat.showReplies) {
                withStyle(SpanStyle(color = muted, fontSize = (chat.fontSizeSp - 2).sp)) {
                    append("↪ @${m.replyParentLogin}: ${m.replyParentText?.take(60).orEmpty()}\n")
                }
            }
            if (m.isFirstMessage && chat.showFirstMessageTag) {
                withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold, fontSize = (chat.fontSizeSp - 3).sp, letterSpacing = 0.05.em)) { append("FIRST MESSAGE  ") }
            }
            time?.let { withStyle(SpanStyle(color = muted, fontSize = (chat.fontSizeSp - 2).sp)) { append("$it ") } }
            m.badges.forEachIndexed { i, b ->
                val key = "badge$i"
                inline[key] = InlineTextContent(Placeholder((badgeSize + 3).sp, badgeSize.sp, PlaceholderVerticalAlign.TextCenter)) { BadgeImage(b, badgeSize) }
                appendInlineContentCompat(key)
            }
            val nameSpan = if (paint != null) {
                SpanStyle(
                    brush = paint.toBrush(prefixPx, 0f, nameWidth.coerceAtLeast(1f), lineH),
                    shadow = if (tp.seventvPaintShadows && tp.seventvPaints == PaintMode.ANIMATED) paint.primaryShadow(1f) else null,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)
            }
            withLink(LinkAnnotation.Clickable("user:${m.user.login}") { actions.onUser(m.user.login, m.user.id) }) {
                withStyle(nameSpan) { append(name) }
            }
            withStyle(SpanStyle(color = if (m.isAction) nameColor else muted)) { append(if (m.isAction) " " else ": ") }

            val bodyStyle = SpanStyle(
                color = if (m.isAction) nameColor else onSurface,
                textDecoration = if (deleted && chat.deletedMessages == DeletedMessages.STRIKE) TextDecoration.LineThrough else null,
                fontStyle = if (m.isAction) FontStyle.Italic else null,
            )
            if (deleted && chat.deletedMessages == DeletedMessages.PLACEHOLDER) {
                withStyle(SpanStyle(color = muted, fontStyle = FontStyle.Italic)) { append("<message deleted>") }
                return@buildAnnotatedString
            }
            withStyle(bodyStyle) {
                m.tokens.forEachIndexed { i, t ->
                    when (t) {
                        is ChatToken.Text -> append(t.text)
                        is ChatToken.EmoteToken -> {
                            val key = "e$i"
                            val ratio = t.emote.width?.let { w -> t.emote.height?.let { h -> w.toFloat() / h } } ?: 1f
                            val w = emoteHeight * ratio.coerceIn(0.5f, 4f)
                            inline[key] = InlineTextContent(Placeholder(w.sp, emoteHeight.sp, PlaceholderVerticalAlign.Center)) {
                                EmoteImage(t.emote, t.overlays, options.animate, Modifier.fillMaxSize().combinedClickable(onClick = { actions.onEmote(t.emote) }))
                            }
                            appendInlineContentCompat(key, t.emote.code)
                        }
                        is ChatToken.Mention -> withLink(LinkAnnotation.Clickable("mention:${t.login}") { actions.onUser(t.login, null) }) {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = primary)) { append("@${t.display}") }
                        }
                        is ChatToken.Link -> withLink(
                            LinkAnnotation.Clickable("link:${t.url}", TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline))) {
                                actions.onLink(t.url)
                            },
                        ) { append(t.url.removePrefix("https://")) }
                        is ChatToken.Cheer -> withStyle(SpanStyle(color = Color(0xFFB57BFF), fontWeight = FontWeight.Bold)) { append("${t.prefix}${t.bits}") }
                    }
                }
            }
        }
        annotated to inline
    }
    Text(
        text = text,
        inlineContent = inline,
        style = baseStyle,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { actions.onMessageLongPress(m) }),
    )
}

private fun AnnotatedString.Builder.appendInlineContentCompat(id: String, alternate: String = "\uFFFD") = appendInlineContent(id, alternate)

@Composable
fun BadgeImage(badge: Badge, sizeSp: Float) {
    val px = with(LocalDensity.current) { sizeSp.sp.toDp() }
    AsyncImage(
        model = badge.urls.getOrNull(1) ?: badge.urls.firstOrNull(),
        contentDescription = badge.title,
        modifier = Modifier.width(px).fillMaxSize().let { m ->
            badge.backgroundColor?.let { c -> runCatching { Color(android.graphics.Color.parseColor(c)) }.getOrNull() }
                ?.let { m.clip(RoundedCornerShape(3.dp)).background(it) } ?: m
        },
    )
}
