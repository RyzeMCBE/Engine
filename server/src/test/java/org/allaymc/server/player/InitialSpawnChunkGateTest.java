package org.allaymc.server.player;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InitialSpawnChunkGateTest {

    @Test
    void smallViewRadiiCannotStallBelowTheDefaultThirtyChunkThreshold() {
        assertEquals(5, InitialSpawnChunkGate.requiredForRadius(30, 1));
        assertEquals(13, InitialSpawnChunkGate.requiredForRadius(30, 2));
        assertEquals(29, InitialSpawnChunkGate.requiredForRadius(30, 3));
        assertEquals(30, InitialSpawnChunkGate.requiredForRadius(30, 4));

        for (int radius = 1; radius <= 4; radius++) {
            var gate = new InitialSpawnChunkGate(30, radius);
            for (int sent = 1; sent < gate.requiredCount(); sent++) {
                assertFalse(gate.chunkQueued(), "Spawn should not finish before the final chunk");
            }
            assertTrue(gate.chunkQueued(), "Spawn must finish even at the smallest supported radius");
            assertFalse(gate.chunkQueued(), "PLAYER_SPAWN must be sent only once");
        }
    }

    @Test
    void requiredChunkCountMatchesCircularLoaderForDifferentRadiiAndServerSettings() {
        // All protocol implementations share ChunkLoaderHolder's circular radius rule.
        for (int radius = 0; radius <= 24; radius++) {
            int available = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz <= radius * radius) available++;
                }
            }
            for (int target : new int[]{1, 2, 5, 13, 29, 30, 40, 100, 500}) {
                assertEquals(Math.min(target, available), InitialSpawnChunkGate.requiredForRadius(target, radius),
                        "radius=" + radius + ", target=" + target);
            }
        }
    }

    @Test
    void decreasingRadiusAfterChunksAreQueuedCompletesWithoutWaitingForAnotherChunk() {
        var gate = new InitialSpawnChunkGate(30, 8);
        for (int i = 0; i < 13; i++) {
            assertFalse(gate.chunkQueued());
        }
        assertTrue(gate.radiusUpdated(2));
        assertFalse(gate.radiusUpdated(2));
        assertFalse(gate.chunkQueued());
        assertEquals(13, gate.requiredCount());
    }

    @Test
    void increasingRadiusDuringJoinRaisesTheTargetWithoutResettingProgress() {
        var gate = new InitialSpawnChunkGate(30, 2);
        for (int i = 0; i < 12; i++) {
            assertFalse(gate.chunkQueued());
        }
        assertFalse(gate.radiusUpdated(4));
        assertEquals(30, gate.requiredCount());
        for (int i = 13; i < 30; i++) {
            assertFalse(gate.chunkQueued());
        }
        assertTrue(gate.chunkQueued());
    }

    @Test
    void invalidZeroOrNegativeConfigurationStillRequiresOneQueuedChunk() {
        for (int target : new int[]{-5, 0, 1}) {
            var gate = new InitialSpawnChunkGate(target, 0);
            assertEquals(1, gate.requiredCount());
            assertFalse(gate.radiusUpdated(-1));
            assertTrue(gate.chunkQueued());
            assertFalse(gate.radiusUpdated(3));
        }
    }

    @Test
    void concurrentChunkDeliveryAndRadiusChangeSignalsSpawnOnlyOnce() throws Exception {
        var gate = new InitialSpawnChunkGate(30, 4);
        var start = new CountDownLatch(1);
        var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 60; i++) {
                final int index = i;
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return index % 7 == 0 ? gate.radiusUpdated(3) : gate.chunkQueued();
                }));
            }
            start.countDown();
            long completed = 0;
            for (var result : results) {
                if (result.get(5, TimeUnit.SECONDS)) completed++;
            }
            assertEquals(1L, completed);
        }
    }
}
