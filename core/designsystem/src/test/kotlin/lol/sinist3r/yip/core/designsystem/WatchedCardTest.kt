package lol.sinist3r.yip.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.time.Clock
import lol.sinist3r.yip.core.designsystem.component.ClipCard
import lol.sinist3r.yip.core.designsystem.component.VideoCard
import lol.sinist3r.yip.core.designsystem.theme.YipTheme
import lol.sinist3r.yip.core.model.AppearanceSettings
import lol.sinist3r.yip.core.model.Clip
import lol.sinist3r.yip.core.model.Video
import lol.sinist3r.yip.core.model.VideoType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xxhdpi")
class WatchedCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun watchedBadgeShows() {
        val v = Video("1", "1", "a", "A", "Past broadcast", Clock.System.now(), 3600, 10, "", VideoType.ARCHIVE)
        val c = Clip("slug", "", "1", "A", "B", "A clip", 5, Clock.System.now(), "", 30.0, null, null)
        compose.setContent {
            YipTheme(AppearanceSettings()) {
                Column(Modifier.width(300.dp)) {
                    VideoCard(v, 1f, onClick = {}, watched = true)
                    ClipCard(c, onClick = {}, watched = true)
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/watched_cards.png")
        compose.onAllNodesWithText(" Watched", useUnmergedTree = true).assertCountEquals(2)
    }
}
