package lol.sinist3r.yip.feature.watch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.captureScreenRoboImage
import lol.sinist3r.yip.core.designsystem.chat.ChatRenderOptions
import lol.sinist3r.yip.core.designsystem.theme.YipTheme
import lol.sinist3r.yip.core.model.AppearanceSettings
import lol.sinist3r.yip.core.model.ChatSettings
import lol.sinist3r.yip.core.model.ThemeMode
import lol.sinist3r.yip.core.model.ThirdPartySettings
import lol.sinist3r.yip.core.model.User
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
