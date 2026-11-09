package lol.sinist3r.yip.feature.watch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.captureScreenRoboImage
import lol.sinist3r.yip.core.designsystem.theme.YipTheme
import lol.sinist3r.yip.core.model.AppearanceSettings
import lol.sinist3r.yip.core.model.Emote
import lol.sinist3r.yip.core.model.EmoteProvider
import lol.sinist3r.yip.core.model.FilterSettings
import lol.sinist3r.yip.core.model.StreamQuality
import lol.sinist3r.yip.core.model.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class NewSheetsTest {
    @get:Rule val compose = createComposeRule()

    private fun themed(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { YipTheme(AppearanceSettings(theme = ThemeMode.DARK)) { content() } }

    @Test fun emoteCard() {
        themed {
            EmoteCardSheet(
                Emote("01F6", "catJAM", EmoteProvider.SEVENTV, listOf("https://example.invalid/1x.webp"), animated = true, ownerName = "Kanaa", isChannel = true),
                animate = false, favourite = true, onToggleFavourite = {}, onInsert = {}, onDismiss = {},
            )
        }
        compose.onNodeWithText("By Kanaa").assertIsDisplayed()
        compose.onNodeWithText("Favourited", substring = true).assertIsDisplayed()
        captureScreenRoboImage("build/screenshots/emote_card.png")
    }

    @Test fun chatFilters() {
        themed { ChatFiltersSheet(FilterSettings(blockedTerms = listOf("spoiler", "/bit\\.ly/"), hiddenUsers = listOf("rude_guy")), onChange = {}, onDismiss = {}) }
        compose.onNodeWithText("Hide bots").assertIsDisplayed()
        captureScreenRoboImage("build/screenshots/chat_filters.png")
    }

    @Test fun qualitySheet() {
        themed {
            QualitySheet(
                qualities = listOf(StreamQuality(id = "1080p60", label = "1080p60", height = 1080, fps = 60, bandwidth = 6_000_000, url = "u", isSource = true), StreamQuality(id = "audio", label = "Audio only", height = null, fps = null, bandwidth = 160_000, url = "u", isAudioOnly = true)),
                selected = null, auto = true, showAuto = true, speeds = emptyList(), speed = 1f, showSpeed = false, sleepAtMillis = null,
                onQuality = {}, onSpeed = {}, onSleep = {}, onDismiss = {}, lowLatency = false, onLowLatency = {}, audioOnly = false, onAudioOnly = {},
            )
        }
        compose.onNodeWithText("Audio only").assertIsDisplayed()
        captureScreenRoboImage("build/screenshots/quality_sheet.png")
    }
}
