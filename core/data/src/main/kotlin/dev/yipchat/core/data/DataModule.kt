package dev.yipchat.core.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.yipchat.core.data.account.AccountRepository
import dev.yipchat.core.data.playback.PlaybackRepository
import dev.yipchat.core.data.playback.PlaylistRewriter
import dev.yipchat.core.network.TokenProvider
import dev.yipchat.core.network.WebTokenProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun tokenProvider(impl: AccountRepository): TokenProvider
    @Binds abstract fun webTokenProvider(impl: AccountRepository): WebTokenProvider
    @Binds abstract fun playlistRewriter(impl: PlaybackRepository): PlaylistRewriter
}
