package org.allaymc.server.network.processor.ingame;

import org.allaymc.api.entity.EntityState;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.allaymc.server.network.processor.ingame.RespawnPacketProcessor.Readiness.*;

class RespawnReadinessTest {

    @Test
    void earlyClientReadyWaitsForDeathAndDespawnToFinish() {
        assertEquals(WAIT, RespawnPacketProcessor.readiness(EntityState.ALIVE, 0));
        assertEquals(WAIT, RespawnPacketProcessor.readiness(EntityState.DEAD, 0));
        assertEquals(WAIT, RespawnPacketProcessor.readiness(EntityState.DESPAWNED_LATER, 0));
        assertEquals(READY, RespawnPacketProcessor.readiness(EntityState.DESPAWNED, 0));
    }

    @Test
    void duplicateOrUnsolicitedReadyPacketsCannotRespawnLivingPlayers() {
        assertEquals(IGNORE, RespawnPacketProcessor.readiness(EntityState.ALIVE, 20));
        assertEquals(IGNORE, RespawnPacketProcessor.readiness(EntityState.SPAWNED_LATER, 0));
        assertEquals(IGNORE, RespawnPacketProcessor.readiness(EntityState.DESPAWNED, 20));
    }

    @Test
    void everyCallbackPhaseRemainsProtectedByTheSameTimeout() {
        var progress = new RespawnPacketProcessor.RespawnProgress(mock(EntityPlayer.class));
        for (var phase : RespawnPacketProcessor.Phase.values()) {
            progress.advance(phase);
            progress.elapsedTicks = 39;
            assertFalse(progress.shouldWarn(), "Premature slow-respawn warning for " + phase);
            assertFalse(progress.timedOut(), "Premature timeout for " + phase);
            progress.elapsedTicks = 40;
            assertTrue(progress.shouldWarn(), "Missing slow-respawn warning for " + phase);
            progress.elapsedTicks = 199;
            assertFalse(progress.timedOut(), "Premature timeout for " + phase);
            progress.elapsedTicks = 200;
            assertTrue(progress.timedOut(), "Missing timeout for " + phase);
        }
    }

}
