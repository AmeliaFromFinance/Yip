package dev.yipchat.feature.watch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.yipchat.core.designsystem.chat.ChatRenderOptions
import dev.yipchat.core.designsystem.theme.YipTheme
import dev.yipchat.core.model.AppearanceSettings
import dev.yipchat.core.model.ChatSettings
import dev.yipchat.core.model.ThemeMode
import dev.yipchat.core.model.ThirdPartySettings
import dev.yipchat.core.model.User
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Narrow phone width: every moderation button must stay fully visible. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class UserCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun moderationButtonsFit() {
        compose.setContent {
            YipTheme(AppearanceSettings(theme = ThemeMode.DARK)) {
                UserCardSheet(
                    card = UserCard("someone", User("1", "someone", "someone"), emptyList(), loading = false),
                    options = ChatRenderOptions(ChatSettings(), ThirdPartySettings(), animate = false),
                    isModerator = true,
                    onMention = {}, onOpenChannel = {}, onBlock = {}, onMod = { _, _ -> }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Unban").assertIsDisplayed()
        compose.onNodeWithText("Block", substring = true).assertIsDisplayed()
        captureScreenRoboImage("build/screenshots/user_card.png")
    }
}
