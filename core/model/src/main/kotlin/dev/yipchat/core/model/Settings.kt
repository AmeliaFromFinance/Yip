package dev.yipchat.core.model

import java.time.LocalTime
import kotlinx.serialization.Serializable

/**
 * Every user-facing preference. Stored as JSON in DataStore; new fields need a default so
 * older files keep decoding.
 */
@Serializable
data class AppSettings(
    val schema: Int = 1,
    val general: GeneralSettings = GeneralSettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    val player: PlayerSettings = PlayerSettings(),
    val chat: ChatSettings = ChatSettings(),
    val thirdParty: ThirdPartySettings = ThirdPartySettings(),
    val filters: FilterSettings = FilterSettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val adBlock: AdBlockSettings = AdBlockSettings(),
    val experimental: ExperimentalSettings = ExperimentalSettings(),
    val data: DataSettings = DataSettings(),
    val updates: UpdateSettings = UpdateSettings(),
    val onboardingDone: Boolean = false,
)

@Serializable
data class UpdateSettings(
    val autoCheck: Boolean = true,
    val prereleases: Boolean = false,
    /** Version the user chose to skip; only a newer one prompts again. */
    val skippedVersion: String? = null,
)

// region General

enum class StartScreen { FOLLOWING, BROWSE, SEARCH }
enum class LinkOpening { IN_APP, BROWSER, ASK }

@Serializable
data class GeneralSettings(
    val bedtime: BedtimeSettings = BedtimeSettings(),
    val browseFilter: BrowseFilter = BrowseFilter(),
    /** Shown as chips at the top of Browse. */
    val pinnedCategories: List<PinnedCategory> = emptyList(),
    /** Nothing goes into watch history or continue watching. */
    val incognito: Boolean = false,
    val startScreen: StartScreen = StartScreen.FOLLOWING,
    val openLinks: LinkOpening = LinkOpening.IN_APP,
    val confirmBeforeExit: Boolean = false,
    val streamLanguages: List<String> = emptyList(),
    val hideMatureStreams: Boolean = false,
    val showOfflineFollowed: Boolean = true,
    val followingSort: FollowingSort = FollowingSort.VIEWERS,
    val continueWatching: Boolean = true,
    /** Logins hidden from Browse and category pages. */
    val hiddenChannels: List<String> = emptyList(),
    /** Category names (lowercase) hidden from Browse. */
    val hiddenCategories: List<String> = emptyList(),
)

enum class FollowingSort { VIEWERS, RECENTLY_STARTED, NAME, CATEGORY }

// endregion

// region Appearance

enum class ThemeMode { SYSTEM, LIGHT, DARK, BLACK }
enum class AccentColor { FOX, CYAN, ROSE, VIOLET, MINT, SKY, CRIMSON, DYNAMIC }
enum class CardLayout { GRID, LIST, COMPACT }

@Serializable
data class AppearanceSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentColor = AccentColor.FOX,
    val customAccentArgb: Long? = null,
    val cardLayout: CardLayout = CardLayout.GRID,
    val gridColumns: Int = 0,
    val showViewerCount: Boolean = true,
    val showUptime: Boolean = true,
    val showTags: Boolean = true,
    val showCategory: Boolean = true,
    val roundedCorners: Int = 12,
    val reduceMotion: Boolean = false,
    val bottomBarLabels: Boolean = true,
)

// endregion

// region Player

enum class QualityPreference(val height: Int?) {
    AUTO(null), SOURCE(Int.MAX_VALUE), P1440(1440), P1080(1080), P720(720), P480(480), P360(360), P160(160), AUDIO_ONLY(0)
}

enum class BufferProfile { LOW_LATENCY, BALANCED, STABLE }
enum class LandscapeChat { OVERLAY, SIDE, HIDDEN }
enum class PlayerTypeOption(val wire: String) { SITE("site"), EMBED("embed"), POPOUT("popout"), AUTOPLAY("autoplay"), FRONTPAGE("frontpage"), PICTURE_BY_PICTURE("picture-by-picture") }
enum class VideoCodec { H264, H265, AV1 }

@Serializable
data class PlayerSettings(
    val qualityWifi: QualityPreference = QualityPreference.AUTO,
    val qualityMobile: QualityPreference = QualityPreference.P720,
    val rememberQualityPerChannel: Boolean = false,
    val bufferProfile: BufferProfile = BufferProfile.BALANCED,
    /** Jump back to live when the delay grows well past the target. */
    val autoCatchUp: Boolean = true,
    val audioOnlyOnMobile: Boolean = false,
    /** Remember volume, mute and audio-only for each channel. */
    val perChannelMemory: Boolean = false,
    val skipMutedParts: Boolean = false,
    val volumeLevelling: Boolean = false,
    val backgroundAudio: Boolean = true,
    val autoPip: Boolean = true,
    val pauseOnHeadphonesUnplugged: Boolean = true,
    val pauseOnCall: Boolean = true,
    val keepScreenOn: Boolean = true,
    val startMuted: Boolean = false,
    val showStatsOverlay: Boolean = false,
    val showLatency: Boolean = true,
    val doubleTapSeekSeconds: Int = 10,
    val swipeBrightness: Boolean = true,
    val swipeVolume: Boolean = true,
    val pinchToZoom: Boolean = true,
    val resumeVods: Boolean = true,
    val vodSpeeds: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f),
    val landscapeChat: LandscapeChat = LandscapeChat.OVERLAY,
    val overlayChatOpacity: Float = 0.55f,
    val overlayChatWidthFraction: Float = 0.36f,
    val playerType: PlayerTypeOption = PlayerTypeOption.SITE,
    val codecs: List<VideoCodec> = listOf(VideoCodec.H264),
    val sleepTimerMinutes: Int = 0,
    val miniPlayer: Boolean = true,
)

// endregion

// region Chat

enum class AnimateEmotes { ALWAYS, WIFI_ONLY, NEVER }
enum class TimestampStyle { OFF, H24, H12, RELATIVE }
enum class DeletedMessages { HIDE, STRIKE, PLACEHOLDER }
enum class PaintMode { ANIMATED, STATIC, OFF }
enum class UsernameStyle { DISPLAY, LOGIN, BOTH }

/** Private label for a chatter. Only on this device. */
@Serializable
data class UserNote(val nickname: String = "", val note: String = "")

@Serializable
data class ChatSettings(
    /** "PROVIDER:id", see [Emote.favouriteKey]. */
    val favouriteEmotes: List<String> = emptyList(),
    /** Keyed by lowercase login. */
    val userNotes: Map<String, UserNote> = emptyMap(),
    /** Show channel emotes you haven't unlocked (greyed out) in the picker. */
    val showLockedEmotes: Boolean = true,
    /** Saved messages, one tap from an empty chat box. */
    val quickReplies: List<String> = emptyList(),
    /** Identical messages in a row show once with a count. */
    val collapseRepeats: Boolean = true,
    val linkPreviews: LinkPreviewMode = LinkPreviewMode.WIFI,
    val highlightBroadcaster: Boolean = true,
    /** Short buzz when the streamer types in the chat you're watching. */
    val broadcasterVibrate: Boolean = false,
    val font: ChatFont = ChatFont.DEFAULT,
    /** Extra letter spacing in sp. */
    val letterSpacing: Float = 0f,
    val fontSizeSp: Float = 13.5f,
    val lineSpacing: Float = 1.35f,
    val emoteScale: Float = 1f,
    val animateEmotes: AnimateEmotes = AnimateEmotes.ALWAYS,
    val timestamps: TimestampStyle = TimestampStyle.OFF,
    val timestampSeconds: Boolean = false,
    val showBadges: Boolean = true,
    val showPronouns: Boolean = true,
    val showNameColors: Boolean = true,
    val readableColors: Boolean = true,
    /** Names meet 4.5:1 contrast; timestamps and other secondary text at full strength. */
    val highContrast: Boolean = false,
    /** Hold live messages until the stream reaches them. */
    val syncWithStream: Boolean = true,
    val usernameStyle: UsernameStyle = UsernameStyle.DISPLAY,
    val alternateRows: Boolean = false,
    val separators: Boolean = false,
    val messageLimit: Int = 500,
    val deletedMessages: DeletedMessages = DeletedMessages.HIDE,
    val showModActions: Boolean = false,
    val modReasons: List<String> = listOf("Spam", "Being rude", "Spoilers", "Self-promotion", "Off-topic"),
    /** One-tap actions on user cards, e.g. "10m Spam" or "ban Bot". See [ModMacro]. */
    val modMacros: List<String> = listOf("10m Spam", "1h Being rude", "ban Bot account"),
    val showFirstMessageTag: Boolean = true,
    val showReplies: Boolean = true,
    val showSubNotices: Boolean = true,
    val showRedemptions: Boolean = true,
    val showRaids: Boolean = true,
    val showJoinMessage: Boolean = true,
    val loadRecentHistory: Boolean = true,
    val clickableLinks: Boolean = true,
    val sendOnEnter: Boolean = true,
    val autocompleteEmotes: Boolean = true,
    val autocompleteUsers: Boolean = true,
    val tabCompletion: Boolean = true,
    val showRecentEmotes: Boolean = true,
    val keepComposerOnSend: Boolean = false,
    val tapNameToMention: Boolean = false,
    val highlightMentions: Boolean = true,
    val mentionVibrate: Boolean = false,
    val highlightColorArgb: Long = 0x33F28C28,
)

// endregion

// region Third-party emotes and cosmetics

enum class ProviderSlot { SEVENTV, BTTV, FFZ }

@Serializable
data class ThirdPartySettings(
    val seventvEmotes: Boolean = true,
    val seventvPaints: PaintMode = PaintMode.ANIMATED,
    val seventvPaintShadows: Boolean = true,
    val seventvBadges: Boolean = true,
    val seventvPersonalEmotes: Boolean = true,
    val seventvLiveUpdates: Boolean = true,
    val bttvEmotes: Boolean = true,
    val bttvBadges: Boolean = true,
    val ffzEmotes: Boolean = true,
    val ffzBadges: Boolean = true,
    val ffzModBadges: Boolean = true,
    val chatterinoBadges: Boolean = true,
    val ffzApBadges: Boolean = true,
    val homiesBadges: Boolean = true,
    val homiesCustomBadges: Boolean = true,
    val moltorinoBadges: Boolean = true,
    val priority: List<ProviderSlot> = listOf(ProviderSlot.SEVENTV, ProviderSlot.BTTV, ProviderSlot.FFZ),
    val zeroWidthEmotes: Boolean = true,
)

// endregion

// region Filters and highlights

@Serializable
data class FilterSettings(
    val hideBots: Boolean = true,
    val botNames: List<String> = DEFAULT_BOTS,
    val hideCommands: Boolean = false,
    val commandPrefixes: List<String> = listOf("!"),
    val blockedTerms: List<String> = emptyList(),
    val blockedTermsAreRegex: Boolean = false,
    val highlightKeywords: List<String> = emptyList(),
    val highlightUsers: List<String> = emptyList(),
    val hiddenUsers: List<String> = emptyList(),
    val hideEmoteOnly: Boolean = false,
    val hideFirstTimeChatters: Boolean = false,
) {
    companion object {
        val DEFAULT_BOTS = listOf(
            "nightbot", "streamelements", "moobot", "fossabot", "streamlabs", "wizebot",
            "sery_bot", "soundalerts", "botrixoficial", "kofistreambot", "pokemoncommunitygame",
        )
    }
}

// endregion

// region Notifications

@Serializable
data class NotificationSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 15,
    val quietHoursEnabled: Boolean = false,
    val quietStartMinutes: Int = 23 * 60,
    val quietEndMinutes: Int = 8 * 60,
    val onlyFavourites: Boolean = false,
    val favourites: List<String> = emptyList(),
    val muted: List<String> = emptyList(),
    val groupNotifications: Boolean = true,
    val notifyCategoryChange: Boolean = false,
    val pingMentions: Boolean = true,
    val pingWhispers: Boolean = true,
    /** Lowercase category names: alert when someone you follow streams one. */
    val categoryAlerts: List<String> = emptyList(),
    val pingKeywords: Boolean = false,
    val pingHighlightUsers: Boolean = false,
)

// endregion

// region Ad handling (opt-in)

enum class AdMethod { BACKUP_PLAYER_TYPE, PROXY, BOTH }

@Serializable
data class AdBlockSettings(
    val enabled: Boolean = false,
    val acknowledgedRisks: Boolean = false,
    val method: AdMethod = AdMethod.BACKUP_PLAYER_TYPE,
    val backupPlayerTypes: List<PlayerTypeOption> = listOf(PlayerTypeOption.EMBED, PlayerTypeOption.POPOUT, PlayerTypeOption.AUTOPLAY),
    val backupLowerQualityOk: Boolean = true,
    val stripAdSegmentsAsLastResort: Boolean = false,
    val proxyTemplates: List<String> = emptyList(),
    val showAdBreakBanner: Boolean = true,
    val muteDuringAds: Boolean = false,
)

// endregion

// region Experimental

@Serializable
data class ExperimentalSettings(
    val autoClaimPoints: Boolean = false,
    val showPointsBalance: Boolean = true,
    val multiViewMaxStreams: Int = 4,
)

// endregion

// region Data and advanced

@Serializable
data class DataSettings(
    val imageCacheMb: Int = 256,
    val keepWatchHistory: Boolean = true,
    val keepRecentSearches: Boolean = true,
    val historyDays: Int = 30,
    val remoteConfigUrl: String = DEFAULT_CONFIG_URL,
    val verboseLogging: Boolean = false,
    val dataSaver: Boolean = false,
    /** When the phone's battery saver is on: lower quality, still emotes, no long-press preview. */
    val batterySaver: Boolean = true,
) {
    companion object {
        const val DEFAULT_CONFIG_URL = "https://config.yipchat.dev/yip/playback.json"
    }
}

// endregion

@Serializable
enum class LinkPreviewMode { OFF, WIFI, ALWAYS }
enum class ChatFont { DEFAULT, HYPERLEGIBLE, DYSLEXIC, SERIF, MONO }

enum class ViewerRange(val min: Int, val max: Int) { ANY(0, Int.MAX_VALUE), UNDER_50(0, 49), SMALL(50, 499), MEDIUM(500, 4_999), LARGE(5_000, Int.MAX_VALUE) }
enum class UptimeRange(val minMinutes: Int, val maxMinutes: Int) { ANY(0, Int.MAX_VALUE), JUST_STARTED(0, 59), MID(60, 239), LONG(240, Int.MAX_VALUE) }

@Serializable
data class PinnedCategory(val id: String, val name: String, val boxArtUrl: String? = null)

/** Browse and category filters, applied to what's loaded. */
@Serializable
data class BrowseFilter(
    val viewers: ViewerRange = ViewerRange.ANY,
    val uptime: UptimeRange = UptimeRange.ANY,
    val hideReruns: Boolean = false,
) {
    val active: Boolean get() = viewers != ViewerRange.ANY || uptime != UptimeRange.ANY || hideReruns

    fun matches(s: LiveStream, nowMillis: Long): Boolean {
        if (s.viewerCount !in viewers.min..viewers.max) return false
        val minutes = ((nowMillis - s.startedAt.toEpochMilliseconds()) / 60_000).toInt().coerceAtLeast(0)
        if (minutes !in uptime.minMinutes..uptime.maxMinutes) return false
        return !(hideReruns && s.isRerun)
    }
}

/** Night-time: OLED theme, dimmer player, automatic sleep timer. Times are minutes after midnight. */
@Serializable
data class BedtimeSettings(
    val enabled: Boolean = false,
    val startMinutes: Int = 23 * 60,
    val endMinutes: Int = 7 * 60,
    val oledTheme: Boolean = true,
    val dimPlayer: Boolean = true,
    val sleepTimerMinutes: Int = 30,
) {
    /** Handles windows that cross midnight. */
    fun activeAt(minutesOfDay: Int): Boolean {
        if (!enabled || startMinutes == endMinutes) return false
        return if (startMinutes < endMinutes) minutesOfDay in startMinutes until endMinutes
        else minutesOfDay >= startMinutes || minutesOfDay < endMinutes
    }

    fun activeNow(): Boolean = LocalTime.now().let { activeAt(it.hour * 60 + it.minute) }
}
