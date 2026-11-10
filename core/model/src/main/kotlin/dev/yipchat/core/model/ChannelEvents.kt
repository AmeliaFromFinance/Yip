package dev.yipchat.core.model

data class PollChoice(val title: String, val votes: Int, val id: String = "")
/** [pointsVoteCost]: extra votes for channel points, when the streamer allows it. */
data class ChannelPoll(val id: String, val title: String, val choices: List<PollChoice>, val endsAtMillis: Long?, val active: Boolean, val pointsVoteCost: Int? = null) {
    val totalVotes get() = choices.sumOf { it.votes }
}

data class PredictionOutcome(val title: String, val color: String, val points: Long, val users: Int, val id: String = "")
enum class PredictionStatus { ACTIVE, LOCKED, RESOLVE_PENDING, RESOLVED, CANCELED, OTHER }
/** [myOutcomeId]/[myPoints]: your pick, when the twitch.tv connection is set up. */
data class Prediction(val id: String, val title: String, val status: PredictionStatus, val locksAtMillis: Long?, val outcomes: List<PredictionOutcome>, val myOutcomeId: String? = null, val myPoints: Long? = null) {
    val totalPoints get() = outcomes.sumOf { it.points }
}

data class HypeTrain(val id: String, val level: Int, val progress: Int, val goal: Int, val endsAtMillis: Long?)

/** Polls, predictions and Hype Train on a live channel; null when there isn't one. */
data class ChannelEvents(val poll: ChannelPoll? = null, val prediction: Prediction? = null, val hypeTrain: HypeTrain? = null) {
    val isEmpty get() = poll == null && prediction == null && hypeTrain == null
}
