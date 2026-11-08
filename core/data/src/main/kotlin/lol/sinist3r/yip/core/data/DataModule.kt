package lol.sinist3r.yip.core.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import lol.sinist3r.yip.core.data.account.AccountRepository
import lol.sinist3r.yip.core.data.playback.PlaybackRepository
import lol.sinist3r.yip.core.data.playback.PlaylistRewriter
import lol.sinist3r.yip.core.network.TokenProvider
import lol.sinist3r.yip.core.network.WebTokenProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun tokenProvider(impl: AccountRepository): TokenProvider
    @Binds abstract fun webTokenProvider(impl: AccountRepository): WebTokenProvider
    @Binds abstract fun playlistRewriter(impl: PlaybackRepository): PlaylistRewriter
}
