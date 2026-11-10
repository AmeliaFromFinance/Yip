package dev.yipchat.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.github.takahirom.roborazzi.captureRoboImage
import dev.yipchat.core.designsystem.chat.ChatActions
import dev.yipchat.core.designsystem.chat.ChatRenderOptions
import dev.yipchat.core.designsystem.chat.ChatRow
import dev.yipchat.core.designsystem.chat.withContrast
import dev.yipchat.core.designsystem.theme.YipTheme
import dev.yipchat.core.model.AppearanceSettings
import dev.yipchat.core.model.ChatItem
import dev.yipchat.core.model.ChatSettings
import dev.yipchat.core.model.ChatToken
import dev.yipchat.core.model.ChatUser
import dev.yipchat.core.model.HighlightKind
import dev.yipchat.core.model.NoticeKind
import dev.yipchat.core.model.Paint
import dev.yipchat.core.model.PaintFunction
import dev.yipchat.core.model.PaintShadow
import dev.yipchat.core.model.PaintStop
import dev.yipchat.core.model.ThemeMode
import dev.yipchat.core.model.ThirdPartySettings
import dev.yipchat.core.model.TimestampStyle
import dev.yipchat.core.model.UserNote
import kotlin.time.Clock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xxhdpi")
class ChatRenderTest {
    @get:Rule val compose = createComposeRule()

    private val now = Clock.System.now()
    private val paint = Paint(
        "p1", "Aurora", PaintFunction.LINEAR_GRADIENT, angle = 90f,
        stops = listOf(PaintStop(0f, 0xFF4CC9F0), PaintStop(0.55f, 0xFF7B61FF), PaintStop(1f, 0xFFFF6FD8)),
        shadows = listOf(PaintShadow(0f, 0f, 4f, 0x997B61FF)),
    )

    private fun msg(id: String, name: String, color: Long?, tokens: List<ChatToken>, paint: Paint? = null, highlight: HighlightKind = HighlightKind.NONE, first: Boolean = false, deleted: Boolean = false) =
        ChatItem.Message(id, now, "1", ChatUser(id, name.lowercase(), name, color), tokens.joinToString(""), tokens, emptyList(), paint, highlight = highlight, isFirstMessage = first, isDeleted = deleted)

    private val items = listOf(
        msg("1", "rosa_lune", 0xFFFF8A65, listOf(ChatToken.Text("!uptime"))),
        msg("2", "Kael", null, listOf(ChatToken.Text("new 7tv set just dropped")), paint = paint),
        ChatItem.Notice("n", now, NoticeKind.GIFT, "timmy_tunes gifted 5 Tier 1 subs to the community."),
        msg("3", "pxl_anna", 0xFF9CCC65, listOf(ChatToken.Mention("karasu", "karasu"), ChatToken.Text(" yeah the frog one")), highlight = HighlightKind.MENTION),
        msg("4", "quietfern", 0xFF0000AA, listOf(ChatToken.Text("first time here, the engine stuff is so cool")), first = true),
        msg("5", "spammer", 0xFF00AA00, listOf(ChatToken.Text("this got deleted")), deleted = true),
        msg("6", "glyphbyte", null, listOf(ChatToken.Text("link: "), ChatToken.Link("https://yipchat.dev"))),
        msg("8", "visitor", 0xFF29B6F6, listOf(ChatToken.Text("hi from the other stream"))).copy(sharedFrom = "pixelforge"),
        msg("7", "eepyAmelia", 0xFFF28C28, listOf(ChatToken.Text("pronouns sit before the name")), paint = paint).copy(pronouns = "She/They"),
        ChatItem.AutoMod("automod-1", now, "rude_guy", "rude_guy", "some held message", "aggression"),
        ChatItem.AutoMod("automod-2", now, "other", "other", "an allowed one", "swearing", status = "approved"),
        ChatItem.System("s", now, "Joined #pixelforge"),
    )

    private fun render(theme: ThemeMode, name: String) {
        val opts = ChatRenderOptions(ChatSettings(timestamps = TimestampStyle.H24, userNotes = mapOf("glyphbyte" to UserNote(nickname = "Glyph"))), ThirdPartySettings(), animate = false)
        compose.setContent {
            YipTheme(AppearanceSettings(theme = theme)) {
                Column(Modifier.width(400.dp).background(MaterialTheme.colorScheme.background)) {
                    items.forEachIndexed { i, it -> ChatRow(it, opts, object : ChatActions {}, alternate = i % 2 == 1) }
                }
            }
        }
        compose.onNodeWithText("Joined #pixelforge").assertExists()
        compose.onNodeWithText("Allow").assertExists()
        compose.onNodeWithText("Glyph (glyphbyte)", substring = true).assertExists()
        compose.onNodeWithText("⇄ pixelforge", substring = true).assertExists()
        compose.onNodeWithText("Allowed").assertExists()
        compose.onRoot().captureRoboImage("build/screenshots/chat_$name.png")
    }

    @Test fun chatDark() = render(ThemeMode.DARK, "dark")
    @Test fun chatLight() = render(ThemeMode.LIGHT, "light")
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class ContrastTest {
    private fun ratio(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) =
        ColorUtils.calculateContrast(a.toArgbOpaque(), b.toArgbOpaque())
    private fun androidx.compose.ui.graphics.Color.toArgbOpaque() = android.graphics.Color.argb(255, (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

    @org.junit.Test fun darkBlueOnDarkMeetsAA() {
        val bg = androidx.compose.ui.graphics.Color(0xFF121110)
        val out = withContrast(androidx.compose.ui.graphics.Color(0xFF0000AA), bg)
        org.junit.Assert.assertTrue(ratio(out, bg) >= 4.5)
    }

    @org.junit.Test fun yellowOnLightMeetsAA() {
        val bg = androidx.compose.ui.graphics.Color(0xFFFFFBF8)
        val out = withContrast(androidx.compose.ui.graphics.Color(0xFFFFEB3B), bg)
        org.junit.Assert.assertTrue(ratio(out, bg) >= 4.5)
    }
}
