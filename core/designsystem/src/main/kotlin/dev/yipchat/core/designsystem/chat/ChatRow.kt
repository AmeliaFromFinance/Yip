package dev.yipchat.core.designsystem.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import coil3.compose.AsyncImage
import dev.yipchat.core.designsystem.R
import dev.yipchat.core.designsystem.theme.YipTheme
import dev.yipchat.core.model.Badge
import dev.yipchat.core.model.ChatFont
import dev.yipchat.core.model.ChatItem
import dev.yipchat.core.model.ChatSettings
import dev.yipchat.core.model.ChatToken
import dev.yipchat.core.model.DeletedMessages
import dev.yipchat.core.model.Emote
import dev.yipchat.core.model.HighlightKind
import dev.yipchat.core.model.PaintFunction
import dev.yipchat.core.model.PaintMode
import dev.yipchat.core.model.ThirdPartySettings
import dev.yipchat.core.model.TimestampStyle
import dev.yipchat.core.model.UsernameStyle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.time.toJavaInstant

@Immutable
data class ChatRenderOptions(
    val chat: ChatSettings,
    val thirdParty: ThirdPartySettings,
    val animate: Boolean,
    /** Show cards under messages with Twitch or YouTube links. */
    val linkPreviews: Boolean = false,
)

interface ChatActions {
    fun onUser(login: String, userId: String?) {}
    fun onEmote(emote: Emote) {}
    fun onEmoteLongPress(emote: Emote) {}
    fun onMessageLongPress(message: ChatItem.Message) {}
    fun onLink(url: String) {}
    fun onAutoMod(item: ChatItem.AutoMod, allow: Boolean) {}
    /** Tapped the "replying to" line. */
    fun onThread(message: ChatItem.Message) {}
}

/** Names and mentions are tappable but shouldn't look like web links. */
private val plainLink = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.None))

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
        item is ChatItem.Message && item.highlight == HighlightKind.BROADCASTER -> Color(0xFF9146FF).copy(alpha = 0.18f)
        item is ChatItem.Message && item.highlight == HighlightKind.REDEMPTION -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        item is ChatItem.Message && item.highlight == HighlightKind.FIRST_MESSAGE -> Color(0xFF43A047).copy(alpha = 0.16f)
        item is ChatItem.Notice -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        item is ChatItem.AutoMod && item.status == null -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        alternate && chat.alternateRows -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
        else -> Color.Transparent
    }
    val vertical = (chat.fontSizeSp * (chat.lineSpacing - 1f) / 2f).coerceAtLeast(1f).dp
    Box(
        modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(RoundedCornerShape(6.dp)).background(bg)
            .padding(horizontal = 6.dp, vertical = vertical),
    ) {
        when (item) {
            is ChatItem.Message -> if (options.linkPreviews && !item.isDeleted) {
                val link = item.tokens.firstNotNullOfOrNull { it as? ChatToken.Link }
                if (link != null) Column { MessageText(item, options, actions); LinkPreviewCard(link.url, actions::onLink) }
                else MessageText(item, options, actions)
            } else MessageText(item, options, actions)
            is ChatItem.Notice -> Column {
                Text(item.text, style = MaterialTheme.typography.bodySmall.copy(fontSize = (chat.fontSizeSp - 0.5f).sp), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                item.message?.let { MessageText(it, options, actions) }
            }
            is ChatItem.AutoMod -> AutoModRow(item, chat.fontSizeSp, actions)
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
    val muted = if (chat.highContrast) onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 0) // result is already cached by the remember below
    val fontSize = chat.fontSizeSp.sp
    val emoteHeight = (chat.fontSizeSp * 2f * chat.emoteScale)
    val badgeSize = chat.fontSizeSp * 1.3f
    val baseStyle = TextStyle(
        fontSize = fontSize, lineHeight = (chat.fontSizeSp * chat.lineSpacing).sp, color = onSurface,
        fontFamily = chatFontFamily(chat.font), letterSpacing = chat.letterSpacing.sp,
    )
    val nameColor = if (chat.showNameColors) {
        val c = if (chat.readableColors || chat.highContrast) readableColor(m.user.colorArgb, dark, primary) else m.user.colorArgb?.let { Color(it) } ?: primary
        if (chat.highContrast) withContrast(c, MaterialTheme.colorScheme.background) else c
    } else onSurface
    val deleted = m.isDeleted && chat.deletedMessages != DeletedMessages.HIDE
    if (m.isDeleted && chat.deletedMessages == DeletedMessages.HIDE) return

    val nickname = chat.userNotes[m.user.login.lowercase()]?.nickname?.takeIf { it.isNotBlank() }
    val name = if (nickname != null) "$nickname (${m.user.displayName})" else when (chat.usernameStyle) {
        UsernameStyle.DISPLAY -> m.user.displayName
        UsernameStyle.LOGIN -> m.user.login
        UsernameStyle.BOTH -> if (m.user.displayName.equals(m.user.login, true)) m.user.displayName else "${m.user.displayName} (${m.user.login})"
    }
    val time = m.timeLabel(chat.timestamps, chat.timestampSeconds)

    val imagePaint = m.paint?.takeIf { it.function == PaintFunction.URL && tp.seventvPaints != PaintMode.OFF }
    // Animated only in ANIMATED mode and when emotes may animate (covers reduce motion and data saver).
    val animated = rememberAnimatedPaint(imagePaint?.animatedImageUrl?.takeIf { tp.seventvPaints == PaintMode.ANIMATED && options.animate })
    val staticImage = rememberPaintImage(imagePaint?.imageUrl?.takeIf { animated == null })
    val paintImage = animated?.image ?: staticImage
    val (text, inline) = remember(m, options, nameColor, dark, paintImage) {
        val inline = HashMap<String, InlineTextContent>()
        val paint = m.paint?.takeIf { tp.seventvPaints != PaintMode.OFF }
        // Position the paint gradient over the name only.
        val pronouns = m.pronouns?.takeIf { chat.showPronouns }
        val smallStyle = baseStyle.copy(fontSize = (chat.fontSizeSp - 2).sp)
        val prefixPx = with(density) {
            val t = time?.let { measurer.measure(AnnotatedString("$it "), smallStyle).size.width.toFloat() } ?: 0f
            val p = pronouns?.let { measurer.measure(AnnotatedString("$it "), smallStyle).size.width.toFloat() } ?: 0f
            val shared = m.sharedFrom?.let { measurer.measure(AnnotatedString("⇄ $it "), smallStyle.copy(fontWeight = FontWeight.Medium)).size.width.toFloat() } ?: 0f
            t + shared + m.badges.size * (badgeSize + 3).sp.toPx() + p
        }
        val nameStyle = baseStyle.copy(fontWeight = FontWeight.Bold)
        val nameWidth = paint?.let { measurer.measure(AnnotatedString(name), nameStyle).size.width.toFloat() } ?: 0f
        val lineH = with(density) { (chat.fontSizeSp * chat.lineSpacing).sp.toPx() }

        val annotated = buildAnnotatedString {
            if (m.replyParentLogin != null && chat.showReplies) {
                withLink(LinkAnnotation.Clickable("thread:${m.id}", plainLink) { actions.onThread(m) }) {
                    withStyle(SpanStyle(color = muted, fontSize = (chat.fontSizeSp - 2).sp)) {
                        append("↪ @${m.replyParentLogin}: ${m.replyParentText?.take(60).orEmpty()}\n")
                    }
                }
            }
            m.rewardTitle?.let { t ->
                withStyle(SpanStyle(color = muted, fontSize = (chat.fontSizeSp - 2).sp, fontWeight = FontWeight.Medium)) { append("✦ Redeemed $t\n") }
            }
            if (m.isFirstMessage && chat.showFirstMessageTag) {
                withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold, fontSize = (chat.fontSizeSp - 3).sp, letterSpacing = 0.05.em)) { append("FIRST MESSAGE  ") }
            }
            time?.let { withStyle(SpanStyle(color = muted, fontSize = (chat.fontSizeSp - 2).sp)) { append("$it ") } }
            m.sharedFrom?.let { withStyle(SpanStyle(color = primary, fontSize = (chat.fontSizeSp - 2).sp, fontWeight = FontWeight.Medium)) { append("⇄ $it ") } }
            m.badges.forEachIndexed { i, b ->
                val key = "badge$i"
                inline[key] = InlineTextContent(Placeholder((badgeSize + 3).sp, badgeSize.sp, PlaceholderVerticalAlign.TextCenter)) { BadgeImage(b, badgeSize) }
                // Screen readers speak the alternate text; the default is a replacement character.
                appendInlineContentCompat(key, "${b.title}, ")
            }
            pronouns?.let { withStyle(SpanStyle(color = muted, fontSize = (chat.fontSizeSp - 2).sp)) { append("$it ") } }
            val nameSpan = if (paint != null) {
                SpanStyle(
                    brush = paint.toBrush(prefixPx, 0f, nameWidth.coerceAtLeast(1f), lineH, paintImage),
                    shadow = if (tp.seventvPaintShadows && tp.seventvPaints == PaintMode.ANIMATED) paint.primaryShadow(1f) else null,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)
            }
            withLink(LinkAnnotation.Clickable("user:${m.user.login}", plainLink) { actions.onUser(m.user.login, m.user.id) }) {
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
                                EmoteImage(t.emote, t.overlays, options.animate, Modifier.fillMaxSize().combinedClickable(onClick = { actions.onEmote(t.emote) }, onLongClick = { actions.onEmoteLongPress(t.emote) }))
                            }
                            appendInlineContentCompat(key, t.emote.code)
                        }
                        is ChatToken.Mention -> withLink(LinkAnnotation.Clickable("mention:${t.login}", plainLink) { actions.onUser(t.login, null) }) {
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
            if (m.repeats > 1) withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold, fontSize = (chat.fontSizeSp - 1).sp)) { append("  ×${m.repeats}") }
        }
        annotated to inline
    }
    Text(
        text = text,
        inlineContent = inline,
        style = baseStyle,
        modifier = Modifier.fillMaxWidth()
            .then(if (animated != null) Modifier.drawWithContent { animated.frame.intValue; drawContent() } else Modifier)
            .combinedClickable(onClick = {}, onLongClick = { actions.onMessageLongPress(m) }),
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
            badge.backgroundColor?.let { c -> runCatching { Color(c.toColorInt()) }.getOrNull() }
                ?.let { m.clip(RoundedCornerShape(3.dp)).background(it) } ?: m
        },
    )
}

@Composable
private fun AutoModRow(item: ChatItem.AutoMod, fontSizeSp: Float, actions: ChatActions) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Text("AutoMod held a message from ${item.userName} · ${item.reason}", style = MaterialTheme.typography.labelMedium, color = muted)
        Text(
            item.text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSizeSp.sp),
            color = if (item.status == null) MaterialTheme.colorScheme.onSurface else muted,
            textDecoration = if (item.status == "denied") TextDecoration.LineThrough else null,
        )
        when (val status = item.status) {
            null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { actions.onAutoMod(item, true) }) { Text("Allow") }
                OutlinedButton(onClick = { actions.onAutoMod(item, false) }) { Text("Deny") }
            }
            else -> Text(
                when (status) { "approved" -> "Allowed"; "denied" -> "Denied"; "expired" -> "Expired"; else -> status.replaceFirstChar(Char::uppercase) },
                style = MaterialTheme.typography.labelMedium, color = muted,
            )
        }
    }
}

private val hyperlegible = FontFamily(
    Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
    Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
)
private val dyslexic = FontFamily(
    Font(R.font.opendyslexic_regular, FontWeight.Normal),
    Font(R.font.opendyslexic_bold, FontWeight.Bold),
)

fun chatFontFamily(font: ChatFont): FontFamily? = when (font) {
    ChatFont.DEFAULT -> null
    ChatFont.HYPERLEGIBLE -> hyperlegible
    ChatFont.DYSLEXIC -> dyslexic
    ChatFont.SERIF -> FontFamily.Serif
    ChatFont.MONO -> FontFamily.Monospace
}
