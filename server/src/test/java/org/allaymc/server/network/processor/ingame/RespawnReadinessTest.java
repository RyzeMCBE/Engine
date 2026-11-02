package org.allaymc.server.network.processor.ingame;

import org.allaymc.api.entity.EntityState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
