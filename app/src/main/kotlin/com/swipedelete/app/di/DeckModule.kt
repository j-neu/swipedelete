package com.swipedelete.app.di

import com.swipedelete.app.recovery.RecoveryCoordinator
import com.swipedelete.core.common.Clock
import com.swipedelete.core.storage.StorageGateway
import com.swipedelete.core.storage.routing.GatewayScanPort
import com.swipedelete.feature.deck.domain.DeckBuilder
import com.swipedelete.feature.deck.session.GatewaySessionMutations
import com.swipedelete.feature.deck.session.MutationGate
import com.swipedelete.feature.deck.session.SessionMutations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the deck engine's non-Android collaborators into the graph (CRITICAL 1,
 * `docs/M1-PLAN.md` D5/D6):
 *
 * - [MutationGate] is bound to the app's real [RecoveryCoordinator.awaitReady], so
 *   every destructive deck action — quarantine, restore and slot MOVE — waits for
 *   startup reconciliation before it touches a byte. `:feature:deck` cannot depend
 *   on `:app`, so this is where the seam meets its implementation.
 * - [SessionMutations] is the journaled gateway behind the session's destructive
 *   operations, including the AC-8 per-item metadata re-check.
 * - [DeckBuilder] gets the router's read-only scan port, so a deck can be built
 *   over a SAF folder or a MediaStore collection through one seam.
 */
@Module
@InstallIn(SingletonComponent::class)
object DeckModule {

    @Provides
    @Singleton
    fun provideMutationGate(recovery: RecoveryCoordinator): MutationGate =
        MutationGate { recovery.awaitReady() }

    @Provides
    @Singleton
    fun provideSessionMutations(gateway: StorageGateway): SessionMutations =
        GatewaySessionMutations(gateway)

    @Provides
    @Singleton
    fun provideDeckBuilder(
        handle: StorageGraphHandle,
        gateway: StorageGateway,
        clock: Clock,
    ): DeckBuilder {
        // handle.scanPort is the routing gateway's read-only scan port in
        // production; the fallback keeps a scan-less concrete build working.
        val scanPort = handle.scanPort ?: GatewayScanPort(gateway)
        return DeckBuilder(scanner = { scanPort }, gateway = gateway, clock = clock)
    }
}
