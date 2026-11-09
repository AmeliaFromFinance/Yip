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
import androidx.compose.foundation.background
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.fillMaxSize
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

    @Test fun emotePicker() {
        val twitch = listOf(
            Emote("1", "eepyHi", EmoteProvider.TWITCH, listOf("https://example.invalid/a"), isChannel = true),
            Emote("2", "Kappa", EmoteProvider.TWITCH, listOf("https://example.invalid/b")),
        )
        val cos = lol.sinist3r.yip.core.data.cosmetics.ChannelCosmetics("1", emotes = mapOf(
            "eepyT2" to Emote("3", "eepyT2", EmoteProvider.TWITCH, listOf("https://example.invalid/c"), isChannel = true, group = "Tier 2"),
            "eepyBits" to Emote("4", "eepyBits", EmoteProvider.TWITCH, listOf("https://example.invalid/d"), isChannel = true, group = "Bits"),
        ))
        themed { EmotePickerSheet(cos, twitch, emptyList(), false, onPick = {}, onDismiss = {}, channelName = "eepyAmelia") }
        compose.onNodeWithText("Tier 2 · locked").assertIsDisplayed()
        compose.onNodeWithText("This channel").assertIsDisplayed()
        compose.onNodeWithText("3rd Party").assertIsDisplayed()
        captureScreenRoboImage("build/screenshots/emote_picker.png")
    }

    @Test fun eventBanners() {
        val now = System.currentTimeMillis()
        val events = lol.sinist3r.yip.core.model.ChannelEvents(
            poll = lol.sinist3r.yip.core.model.ChannelPoll("p", "Which game next?", listOf(lol.sinist3r.yip.core.model.PollChoice("Celeste", 30), lol.sinist3r.yip.core.model.PollChoice("Hades", 10)), now + 65_000, true),
            prediction = lol.sinist3r.yip.core.model.Prediction("x", "Holt Rohat den Win?", lol.sinist3r.yip.core.model.PredictionStatus.ACTIVE, now + 90_000,
                listOf(lol.sinist3r.yip.core.model.PredictionOutcome("Ja", "BLUE", 1_295_708, 60), lol.sinist3r.yip.core.model.PredictionOutcome("Ne", "PINK", 4_500_445, 111))),
            hypeTrain = lol.sinist3r.yip.core.model.HypeTrain("h", 9, 2450, 7200, now + 200_000),
        )
        themed { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().androidx_bg()) { EventBanners(events) } }
        compose.onNodeWithText("Hype Train · level 9").assertIsDisplayed()
        compose.onNodeWithText("Which game next?").performClick()
        compose.onNodeWithText("Celeste").assertIsDisplayed()
        captureScreenRoboImage("build/screenshots/event_banners.png")
    }
}

@androidx.compose.runtime.Composable
private fun androidx.compose.ui.Modifier.androidx_bg() = this.then(androidx.compose.ui.Modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.background))
