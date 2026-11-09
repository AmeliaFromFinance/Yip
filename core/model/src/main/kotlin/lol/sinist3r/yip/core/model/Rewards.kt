package lol.sinist3r.yip.core.model

data class CustomReward(
    val id: String,
    val title: String,
    val cost: Int,
    val prompt: String?,
    val needsInput: Boolean,
    val available: Boolean,
    val subOnly: Boolean,
    val color: String?,
    val imageUrl: String?,
    val cooldownEndsAtMillis: Long?,
)

/** [balance] is null when the twitch.tv connection isn't set up. */
data class ChannelRewards(val channelId: String, val pointsName: String, val enabled: Boolean, val balance: Int?, val rewards: List<CustomReward>)
