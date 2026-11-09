package lol.sinist3r.yip.feature.watch

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Clock
import lol.sinist3r.yip.core.designsystem.chat.ChatActions
import lol.sinist3r.yip.core.designsystem.chat.ChatRenderOptions
import lol.sinist3r.yip.core.designsystem.theme.YipTheme
import lol.sinist3r.yip.core.model.AppearanceSettings
import lol.sinist3r.yip.core.model.ChatItem
import lol.sinist3r.yip.core.model.ChatSettings
import lol.sinist3r.yip.core.model.ChatToken
import lol.sinist3r.yip.core.model.ChatUser
import lol.sinist3r.yip.core.model.ThirdPartySettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xxhdpi")
class ChatListScrollTest {
    @get:Rule val compose = createComposeRule()

    private val now = Clock.System.now()
    private fun msg(n: Int) = ChatItem.Message("m$n", now, "1", ChatUser("u$n", "user$n", "user$n", null), "message $n", listOf(ChatToken.Text("message $n")), emptyList())
    private val items = mutableStateOf<ImmutableList<ChatItem>>((1..40).map(::msg).toImmutableList())
    private fun add(count: Int) {
        val last = items.value.size
        items.value = (items.value + (last + 1..last + count).map(::msg)).toImmutableList()
        compose.waitForIdle()
    }

    private fun setUp() = compose.setContent {
        YipTheme(AppearanceSettings()) {
            ChatList(items.value, ChatRenderOptions(ChatSettings(), ThirdPartySettings(), animate = false), object : ChatActions {}, Modifier.width(400.dp).height(300.dp))
        }
    }

    @Test fun followsNewMessages() {
        setUp()
        repeat(10) { add(3) }
        compose.onNodeWithText("message 70", substring = true).assertIsDisplayed()
    }

    @Test fun dragPausesThenPillResumes() {
        setUp()
        compose.onRoot().performTouchInput { swipeDown() } // reverse layout: down = older
        add(5)
        compose.onNodeWithText("5 new messages", substring = true).assertIsDisplayed()
        compose.onNodeWithText("message 45", substring = true).assertDoesNotExist()
        compose.onNodeWithText("5 new messages", substring = true).performClick()
        compose.waitForIdle()
        add(2)
        compose.onNodeWithText("message 47", substring = true).assertIsDisplayed()
    }
}
