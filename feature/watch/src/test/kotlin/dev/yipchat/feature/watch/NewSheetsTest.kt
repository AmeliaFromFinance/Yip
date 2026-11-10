package dev.yipchat.feature.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.yipchat.core.data.cosmetics.ChannelCosmetics
import dev.yipchat.core.designsystem.theme.YipTheme
import dev.yipchat.core.model.AppearanceSettings
import dev.yipchat.core.model.ChannelEvents
import dev.yipchat.core.model.ChannelPoll
import dev.yipchat.core.model.ChannelRewards
import dev.yipchat.core.model.CustomReward
import dev.yipchat.core.model.Emote
import dev.yipchat.core.model.EmoteProvider
import dev.yipchat.core.model.FilterSettings
import dev.yipchat.core.model.HypeTrain
import dev.yipchat.core.model.PollChoice
import dev.yipchat.core.model.Prediction
import dev.yipchat.core.model.PredictionOutcome
import dev.yipchat.core.model.PredictionStatus
import dev.yipchat.core.model.StreamQuality
import dev.yipchat.core.model.ThemeMode
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
        val cos = ChannelCosmetics("1", emotes = mapOf(
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
        val events = ChannelEvents(
            poll = ChannelPoll("p", "Which game next?", listOf(PollChoice("Celeste", 30, "c1"), PollChoice("Hades", 10, "c2")), now + 65_000, true),
            prediction = Prediction("x", "Holt Rohat den Win?", PredictionStatus.ACTIVE, now + 90_000,
                listOf(PredictionOutcome("Ja", "BLUE", 1_295_708, 60, "o1"), PredictionOutcome("Ne", "PINK", 4_500_445, 111, "o2"))),
            hypeTrain = HypeTrain("h", 9, 2450, 7200, now + 200_000),
        )
        val actions = EventActions(emptySet(), 12_000, { _, _, _ -> }, { _, _, _ -> })
        themed { Column(Modifier.fillMaxSize().androidx_bg()) { EventBanners(events, actions = actions) } }
        compose.onNodeWithText("Hype Train · level 9").assertIsDisplayed()
        compose.onNodeWithText("Which game next?").performClick()
        compose.onNodeWithText("tap a choice to vote", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Holt Rohat den Win?").performClick()
        compose.onNodeWithText("Predict Ja").assertIsDisplayed()
        captureScreenRoboImage("build/screenshots/event_banners.png")
    }

    @Test fun rewardsSheet() {
        fun r(id: String, title: String, cost: Int, input: Boolean = false, ok: Boolean = true, sub: Boolean = false, color: String = "#9147FF") =
            CustomReward(id, title, cost, if (input) "Tell me what to play" else null, input, ok, sub, color, null, null)
        val data = ChannelRewards("1", "Eepy points", true, 12_450, listOf(
            r("a", "Hydrate!", 500), r("b", "Song request", 2_000, input = true), r("c", "Pick the next game", 25_000),
            r("d", "VIP for a week", 100_000, sub = true, color = "#F28C28"), r("e", "Run an ad", 300, ok = false),
        ))
        themed { RewardsSheet(RewardsState.Ready(data, message = "Redeemed Hydrate!"), onRedeem = { _, _ -> }, onConnect = {}, onDismiss = {}) }
        compose.onNodeWithText("Eepy points").assertIsDisplayed()
        captureScreenRoboImage("build/screenshots/rewards.png")
    }
}

@androidx.compose.runtime.Composable
private fun Modifier.androidx_bg() = this.then(Modifier.background(MaterialTheme.colorScheme.background))