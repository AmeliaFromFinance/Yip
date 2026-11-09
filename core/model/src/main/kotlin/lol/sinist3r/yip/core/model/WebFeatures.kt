package lol.sinist3r.yip.core.model

data class FollowedCategory(val id: String, val name: String, val boxArtUrl: String?)

data class TimedDrop(
    val id: String,
    val name: String,
    val requiredMinutes: Int,
    val watchedMinutes: Int,
    val claimed: Boolean,
    /** Set once the drop can be claimed. */
    val claimId: String?,
    val rewardName: String?,
    val imageUrl: String?,
) {
    val progress get() = if (requiredMinutes > 0) (watchedMinutes.toFloat() / requiredMinutes).coerceIn(0f, 1f) else 0f
    val claimable get() = !claimed && claimId != null && watchedMinutes >= requiredMinutes
}

data class DropCampaign(val id: String, val name: String, val gameName: String?, val boxArtUrl: String?, val endsAtMillis: Long?, val drops: List<TimedDrop>)
