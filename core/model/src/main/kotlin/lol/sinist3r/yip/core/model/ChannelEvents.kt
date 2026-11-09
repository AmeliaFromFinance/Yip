package lol.sinist3r.yip.core.model

data class PollChoice(val title: String, val votes: Int)
data class ChannelPoll(val id: String, val title: String, val choices: List<PollChoice>, val endsAtMillis: Long?, val active: Boolean) {
    val totalVotes get() = choices.sumOf { it.votes }
}

data class PredictionOutcome(val title: String, val color: String, val points: Long, val users: Int)
enum class PredictionStatus { ACTIVE, LOCKED, RESOLVE_PENDING, RESOLVED, CANCELED, OTHER }
data class Prediction(val id: String, val title: String, val status: PredictionStatus, val locksAtMillis: Long?, val outcomes: List<PredictionOutcome>) {
    val totalPoints get() = outcomes.sumOf { it.points }
}

data class HypeTrain(val id: String, val level: Int, val progress: Int, val goal: Int, val endsAtMillis: Long?)

/** Polls, predictions and Hype Train on a live channel; null when there isn't one. */
data class ChannelEvents(val poll: ChannelPoll? = null, val prediction: Prediction? = null, val hypeTrain: HypeTrain? = null) {
    val isEmpty get() = poll == null && prediction == null && hypeTrain == null
}
