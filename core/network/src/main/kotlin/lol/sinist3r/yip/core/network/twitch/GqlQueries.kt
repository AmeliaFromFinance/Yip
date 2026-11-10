package lol.sinist3r.yip.core.network.twitch

/** Raw GraphQL documents. Raw queries don't depend on persisted-query hashes. */
internal object GqlQueries {
    private const val STREAM_NODE = """
        broadcaster { broadcastSettings { title } displayName id login profileImageURL(width: 300) }
        createdAt freeformTags { name } game { displayName id slug } id previewImageURL viewersCount type
    """

    val TOP_STREAMS = """
        query TopStreams(${'$'}languages: [Language!], ${'$'}first: Int, ${'$'}after: Cursor) {
          streams(first: ${'$'}first, after: ${'$'}after, options: { sort: VIEWER_COUNT, broadcasterLanguages: ${'$'}languages }) {
            edges { cursor node { $STREAM_NODE } }
            pageInfo { hasNextPage }
          }
        }
    """.trimIndent()

    val GAME_STREAMS = """
        query GameStreams(${'$'}id: ID, ${'$'}languages: [Language!], ${'$'}first: Int, ${'$'}after: Cursor) {
          game(id: ${'$'}id) {
            streams(first: ${'$'}first, after: ${'$'}after, options: { sort: VIEWER_COUNT, broadcasterLanguages: ${'$'}languages }) {
              edges { cursor node { $STREAM_NODE } }
              pageInfo { hasNextPage }
            }
          }
        }
    """.trimIndent()

    val TOP_GAMES = """
        query TopGames(${'$'}first: Int, ${'$'}after: Cursor) {
          games(first: ${'$'}first, after: ${'$'}after) {
            edges { cursor node { boxArtURL displayName id viewersCount } }
            pageInfo { hasNextPage }
          }
        }
    """.trimIndent()

    val SEARCH_CHANNELS = """
        query SearchChannels(${'$'}query: String!, ${'$'}first: Int, ${'$'}after: Cursor) {
          searchUsers(userQuery: ${'$'}query, first: ${'$'}first, after: ${'$'}after) {
            edges { cursor node { displayName id login profileImageURL(width: 300) stream { viewersCount game { displayName } broadcaster { broadcastSettings { title } } } } }
            pageInfo { hasNextPage }
          }
        }
    """.trimIndent()

    val SEARCH_GAMES = """
        query SearchGames(${'$'}query: String!, ${'$'}first: Int, ${'$'}after: Cursor) {
          searchCategories(query: ${'$'}query, first: ${'$'}first, after: ${'$'}after) {
            edges { cursor node { boxArtURL displayName id viewersCount } }
            pageInfo { hasNextPage }
          }
        }
    """.trimIndent()

    val USERS_STREAM = """
        query UsersStream(${'$'}ids: [ID!], ${'$'}logins: [String!]) {
          users(ids: ${'$'}ids, logins: ${'$'}logins) {
            displayName id login profileImageURL(width: 300) description createdAt roles { isPartner isAffiliate }
            stream { $STREAM_NODE }
          }
        }
    """.trimIndent()

    val USER_VIDEOS = """
        query UserVideos(${'$'}id: ID, ${'$'}login: String, ${'$'}types: [BroadcastType!], ${'$'}first: Int, ${'$'}after: Cursor) {
          user(id: ${'$'}id, login: ${'$'}login, lookupType: ALL) {
            id login displayName
            videos(first: ${'$'}first, after: ${'$'}after, types: ${'$'}types, sort: TIME) {
              edges { cursor node { broadcastType createdAt id lengthSeconds previewThumbnailURL title viewCount } }
              pageInfo { hasNextPage }
            }
          }
        }
    """.trimIndent()

    val USER_CLIPS = """
        query UserClips(${'$'}id: ID, ${'$'}login: String, ${'$'}period: ClipsPeriod, ${'$'}first: Int, ${'$'}after: Cursor) {
          user(id: ${'$'}id, login: ${'$'}login, lookupType: ALL) {
            id login displayName
            clips(first: ${'$'}first, after: ${'$'}after, criteria: { period: ${'$'}period }) {
              edges { cursor node { createdAt durationSeconds slug thumbnailURL title url viewCount curator { displayName } video { id } videoOffsetSeconds } }
              pageInfo { hasNextPage }
            }
          }
        }
    """.trimIndent()

    val GLOBAL_BADGES = """
        query GlobalBadges { badges { setID version title imageURL(size: QUADRUPLE) } }
    """.trimIndent()

    /** Field names checked against gql.twitch.tv; anonymous, no integrity token needed. */
    val CHANNEL_EVENTS = """
        query ChannelEvents(${'$'}login: String!) {
          user(login: ${'$'}login) {
            viewablePoll { id title status remainingDurationMilliseconds settings { communityPointsVotes { isEnabled cost } } choices { id title votes { total } } }
            channel {
              activePredictionEvents { id title status createdAt predictionWindowSeconds outcomes { id title color totalPoints totalUsers } self { prediction { points outcome { id } } } }
              hypeTrain { execution { id isActive expiresAt progress { goal total progression level { value } } } }
            }
          }
        }
    """.trimIndent()

    /** Field names checked against gql.twitch.tv. Works signed out; "self" needs the twitch.tv connection. */
    val CHANNEL_REWARDS = """
        query ChannelRewards(${'$'}login: String!) {
          channel(name: ${'$'}login) {
            id
            self { communityPoints { balance } }
            communityPointsSettings {
              isEnabled name
              customRewards { id title cost prompt isUserInputRequired isEnabled isPaused isInStock isSubOnly backgroundColor image { url } defaultImage { url } cooldownExpiresAt }
            }
          }
        }
    """.trimIndent()

    val FOLLOWED_GAMES = """
        query FollowedGames { currentUser { followedGames(first: 100, type: ALL) { nodes { id name displayName boxArtURL } } } }
    """.trimIndent()

    val FOLLOW_GAME = """
        mutation FollowGame(${'$'}id: ID!) { followGame(input: { gameID: ${'$'}id }) { __typename } }
    """.trimIndent()

    val UNFOLLOW_GAME = """
        mutation UnfollowGame(${'$'}id: ID!) { unfollowGame(input: { gameID: ${'$'}id }) { __typename } }
    """.trimIndent()

    val VOTE_POLL = """
        mutation Vote(${'$'}input: VoteInPollInput!) { voteInPoll(input: ${'$'}input) { error { code } } }
    """.trimIndent()

    val MAKE_PREDICTION = """
        mutation Predict(${'$'}input: MakePredictionInput!) { makePrediction(input: ${'$'}input) { error { code } prediction { id } } }
    """.trimIndent()

    val REDEEM_REWARD = """
        mutation Redeem(${'$'}input: RedeemCommunityPointsCustomRewardInput!) {
          redeemCommunityPointsCustomReward(input: ${'$'}input) { error { code } redemption { id } }
        }
    """.trimIndent()

    val CHANNEL_BADGES = """
        query ChannelBadges(${'$'}id: ID) { user(id: ${'$'}id) { broadcastBadges { setID version title imageURL(size: QUADRUPLE) } } }
    """.trimIndent()

    val VIDEO_INFO = """
        query VideoInfo(${'$'}id: ID) {
          video(id: ${'$'}id) { id title createdAt lengthSeconds viewCount previewThumbnailURL broadcastType owner { id login displayName } }
        }
    """.trimIndent()

    val VIDEO_EXTRAS = """
        query VideoExtras(${'$'}id: ID) {
          video(id: ${'$'}id) { seekPreviewsURL muteInfo { mutedSegmentConnection { nodes { duration offset } } } }
        }
    """.trimIndent()

    val PINNED_MESSAGE = """
        query Pinned(${'$'}login: String) {
          channel(name: ${'$'}login) {
            pinnedChatMessages(first: 1) {
              edges { node { id startsAt endsAt pinnedBy { login displayName } pinnedMessage { id content { text } sender { login displayName chatColor } } } }
            }
          }
        }
    """.trimIndent()

    val PIN_MESSAGE = """
        mutation Pin(${'$'}input: PinChatMessageInput!) { pinChatMessage(input: ${'$'}input) { pinnedChatMessage { id } error { code } } }
    """.trimIndent()

    val UNPIN_MESSAGE = """
        mutation Unpin(${'$'}input: UnpinChatMessageInput!) { unpinChatMessage(input: ${'$'}input) { error { code } } }
    """.trimIndent()

    val FOLLOWS_CLIPS = """
        query FollowsClips(${'$'}ids: [ID!], ${'$'}first: Int) {
          users(ids: ${'$'}ids) {
            id login displayName profileImageURL(width: 70)
            clips(first: ${'$'}first, criteria: { period: LAST_WEEK, sort: VIEWS_DESC }) {
              edges { node { createdAt durationSeconds slug thumbnailURL title url viewCount curator { displayName } video { id } videoOffsetSeconds } }
            }
          }
        }
    """.trimIndent()

    val CLIP_PREVIEW = """
        query ClipPreview(${'$'}slug: ID!) {
          clip(slug: ${'$'}slug) { title thumbnailURL viewCount durationSeconds broadcaster { displayName } }
        }
    """.trimIndent()

    val VIDEO_PREVIEW = """
        query VideoPreview(${'$'}id: ID) {
          video(id: ${'$'}id) { title previewThumbnailURL(width: 320, height: 180) lengthSeconds owner { displayName } }
        }
    """.trimIndent()

    val USER_PREVIEW = """
        query UserPreview(${'$'}login: String) {
          user(login: ${'$'}login) { displayName profileImageURL(width: 150) description stream { viewersCount game { displayName } } }
        }
    """.trimIndent()

    val CREATE_CLIP = """
        mutation CreateClip(${'$'}input: CreateClipInput!) { createClip(input: ${'$'}input) { clip { slug } error { __typename } } }
    """.trimIndent()

    val CLIP_DRAFT = """
        query ClipDraft(${'$'}slug: ID!) { clip(slug: ${'$'}slug) { slug isPublished durationSeconds } }
    """.trimIndent()

    val PUBLISH_CLIP = """
        mutation PublishClip(${'$'}input: PublishClipInput!) { publishClip(input: ${'$'}input) { clip { slug title } error { __typename } } }
    """.trimIndent()
}
