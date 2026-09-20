package com.swipedelete.app.di

import com.swipedelete.core.common.DispatchersProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

/**
 * The one place the app names the platform dispatchers. Injecting
 * [DispatchersProvider] (rather than calling `Dispatchers.IO` at use sites) is what
 * lets the recovery test drive `Application.onCreate` on a test dispatcher.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @Singleton
    fun provideDispatchersProvider(): DispatchersProvider = AndroidDispatchersProvider
}

private object AndroidDispatchersProvider : DispatchersProvider {
    override val io = Dispatchers.IO
    override val default = Dispatchers.Default
    override val main = Dispatchers.Main
}
