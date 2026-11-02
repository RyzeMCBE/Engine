package org.allaymc.server.player;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends the initial spawn signal after enough chunks have been queued for the
 * negotiated view radius. A small radius can contain fewer chunks than the
 * configured initial-spawn threshold.
 *
 * <p>Chunk delivery can run on an async sender while chunk radius requests
 * are handled on the world thread, so both completion paths must be safe to
 * race and signal completion only once.</p>
 */
final class InitialSpawnChunkGate {
    private final int configuredTarget;
    private final AtomicInteger queuedChunks = new AtomicInteger();
    private final AtomicBoolean completed = new AtomicBoolean();
    private volatile int requiredChunks;

    InitialSpawnChunkGate(int configuredTarget, int initialRadius) {
        this.configuredTarget = Math.max(1, configuredTarget);
        this.requiredChunks = requiredForRadius(this.configuredTarget, initialRadius);
    }

    boolean chunkQueued() {
        queuedChunks.incrementAndGet();
        return completeIfReady();
    }

    boolean radiusUpdated(int radius) {
        requiredChunks = requiredForRadius(configuredTarget, radius);
        return completeIfReady();
    }

    int queuedCount() {
        return queuedChunks.get();
    }

    int requiredCount() {
        return requiredChunks;
    }

    private boolean completeIfReady() {
        return queuedChunks.get() >= requiredChunks && completed.compareAndSet(false, true);
    }

    /**
     * Match the circular chunk selection used by ChunkLoaderHolder.
     * Stop as soon as the configured target is reached to avoid scanning large radii.
     */
    static int requiredForRadius(int configuredTarget, int radius) {
        int target = Math.max(1, configuredTarget);
        int safeRadius = Math.max(0, radius);
        // The center row alone can satisfy the target at large radii.
        if (2L * safeRadius + 1 >= target) {
            return target;
        }

        long squaredRadius = (long) safeRadius * safeRadius;
        int count = 0;
        for (int dx = -safeRadius; dx <= safeRadius; dx++) {
            for (int dz = -safeRadius; dz <= safeRadius; dz++) {
                if ((long) dx * dx + (long) dz * dz <= squaredRadius && ++count >= target) {
                    return target;
                }
            }
        }
        return Math.max(1, count);
    }
}
