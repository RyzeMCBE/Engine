package org.allaymc.server.world.manager;

import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongComparator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.annotation.NotThreadSafe;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.math.MathUtils;
import org.allaymc.api.player.Player;
import org.allaymc.api.utils.hash.HashUtils;
import org.allaymc.api.world.chunk.Chunk;
import org.allaymc.api.world.chunk.ChunkLoader;
import org.allaymc.api.world.manager.ChunkManager;
import org.allaymc.server.AllayServer;
import org.allaymc.server.player.AllayPlayer;
import org.joml.Vector3i;

import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.allaymc.server.ServerSettings.WorldSettings.ChunkSendingStrategy.ASYNC;

/**
 * ChunkLoaderHolder is a class that holds a chunk loader and manages the sending of chunks. It
 * will also load the chunk which should be sent to the chunk loader if the chunk is not loaded.
 *
 * @author daoge_cmd
 */
@NotThreadSafe
@Slf4j
public final class ChunkLoaderHolder {

    /**
     * The chunk service that owns this chunk loader holder.
     */
    private final ChunkManager chunkManager;
    /**
     * The chunk loader that this holder is holding.
     */
    @Getter
    private final ChunkLoader chunkLoader;
    /**
     * The actual player if the chunk loader is an actual player. Can be {@code null}.
     */
    private final Player player;
    /**
     * A comparator that will decode chunk pos hash and compare chunk by its distance.
     */
    private final LongComparator chunkDistanceComparatorHashed;
    /**
     * A comparator that will compare chunk by its distance.
     */
    private final Comparator<Chunk> chunkDistanceComparator;
    /**
     * Stores all chunks that is in radius and have been sent.
     */
    private final LongOpenHashSet sentChunks;
    /** Async sends are acknowledged on the owning tick thread only. */
    private final LongOpenHashSet pendingAsyncChunks = new LongOpenHashSet();
    private final Queue<ChunkSendResult> asyncResults = new ConcurrentLinkedQueue<>();
    private boolean warnedAboutRejectedChunks;

    private record ChunkSendResult(Chunk chunk, boolean queued) {}
    /**
     * Stores all chunks that is in radius.
     */
    private final LongOpenHashSet inRadiusChunks;
    /**
     * Stores all chunks that is waiting to be sent. The order is determined by their distance from the loader.
     */
    private final LongArrayFIFOQueue chunkSendingQueue;
    /**
     * The async chunk sender. Can be {@code null} if async chunk sending is not enabled.
     */
    private AsyncChunkSender asyncChunkSender;
    /**
     * A long value which is calculated based on the x and z coordinates of the loader's chunk pos using
     * method {@link HashUtils#hashXZ(int, int)}.
     */
    private long lastLoaderChunkPosHashed;
    private int lastChunkLoadingRadius = Integer.MIN_VALUE;

    ChunkLoaderHolder(ChunkManager chunkManager, ChunkLoader chunkLoader) {
        this.chunkManager = chunkManager;
        this.chunkLoader = chunkLoader;
        this.player = chunkLoader instanceof EntityPlayer p && p.isActualPlayer() ? p.getController() : null;
        this.chunkDistanceComparatorHashed = new HashedChunkDistanceComparator();
        this.chunkDistanceComparator = new ChunkDistanceComparator();
        this.sentChunks = new LongOpenHashSet();
        this.inRadiusChunks = new LongOpenHashSet();
        this.chunkSendingQueue = new LongArrayFIFOQueue((chunkLoader.getChunkLoadingRadius() * 2 + 1) * (chunkLoader.getChunkLoadingRadius() * 2 + 1));
        if (isActualPlayer() && AllayServer.getSettings().worldSettings().chunkSendingStrategy() == ASYNC) {
            this.asyncChunkSender = new AsyncChunkSender();
        }
        this.lastLoaderChunkPosHashed = Long.MAX_VALUE;
    }

    public void onRemoved() {
        if (asyncChunkSender != null) {
            asyncChunkSender.stop();
        }
        asyncResults.clear();
        pendingAsyncChunks.clear();
        removeChunkLoaderInChunks(sentChunks);
        if (isActualPlayer()) {
            sentChunks.forEach(player::removeChunk);
        }
    }

    public void tick() {
        long currentLoaderChunkPosHashed;
        var floor = MathUtils.floor(chunkLoader.getLocation());
        int currentRadius = chunkLoader.getChunkLoadingRadius();
        if ((currentLoaderChunkPosHashed = HashUtils.hashXZ(floor.x >> 4, floor.z >> 4)) != lastLoaderChunkPosHashed
                || currentRadius != lastChunkLoadingRadius) {
            lastLoaderChunkPosHashed = currentLoaderChunkPosHashed;
            lastChunkLoadingRadius = currentRadius;
            updateInRadiusChunks(floor);
            removeOutOfRadiusChunks();
            updateChunkSendingQueue();
        }
        processAsyncResults();
        sendQueuedChunks();
    }

    private void updateInRadiusChunks(Vector3i currentPos) {
        inRadiusChunks.clear();
        var loaderChunkX = currentPos.x >> 4;
        var loaderChunkZ = currentPos.z >> 4;
        var chunkLoadingRadius = chunkLoader.getChunkLoadingRadius();
        for (int rx = -chunkLoadingRadius; rx <= chunkLoadingRadius; rx++) {
            for (int rz = -chunkLoadingRadius; rz <= chunkLoadingRadius; rz++) {
                if (!isChunkInRadius(rx, rz, chunkLoadingRadius)) {
                    continue;
                }

                var chunkX = loaderChunkX + rx;
                var chunkZ = loaderChunkZ + rz;
                var hashXZ = HashUtils.hashXZ(chunkX, chunkZ);
                inRadiusChunks.add(hashXZ);
            }
        }
    }

    @SuppressWarnings("DataFlowIssue")
    private void removeOutOfRadiusChunks() {
        var difference = Sets.difference(sentChunks, inRadiusChunks);
        removeChunkLoaderInChunks(difference);
        // Unload chunks out of range
        if (isActualPlayer()) {
            difference.forEach(player::removeChunk);
        }
        // The intersection of sentChunks and inRadiusChunks
        sentChunks.removeAll(difference);
    }

    private void removeChunkLoaderInChunks(Set<Long> chunkHashes) {
        chunkHashes.stream()
                .map(chunkManager::getChunk)
                .filter(Objects::nonNull)
                .forEach(chunk -> chunk.removeChunkLoader(chunkLoader));
    }

    @SuppressWarnings("ALL")
    private void updateChunkSendingQueue() {
        chunkSendingQueue.clear();
        // Chunks that have already been sent will not be resent
        var difference = Sets.difference(inRadiusChunks, sentChunks);
        difference.stream().filter(hash -> !pendingAsyncChunks.contains(hash))
                .sorted(chunkDistanceComparatorHashed).forEachOrdered(chunkHash -> {
            chunkManager.loadChunk(chunkHash);
            chunkSendingQueue.enqueue(chunkHash);
        });
    }

    private void sendQueuedChunks() {
        if (chunkSendingQueue.isEmpty()) return;

        var transferringPlayer = player instanceof AllayPlayer allayPlayer
                && allayPlayer.isPrioritizingDimensionArrival() ? allayPlayer : null;
        var chunkReadyToSend = ReadyChunkQueue.takeReady(
                chunkSendingQueue,
                chunkLoader.getChunkMaxSendCountPerTick(),
                chunkManager::getChunk,
                hash -> transferringPlayer == null || transferringPlayer.isDimensionArrivalChunk(hash)
        );
        if (chunkReadyToSend.isEmpty()) return;

        if (!isActualPlayer()) {
            chunkReadyToSend.values().forEach(chunk -> chunk.addChunkLoader(chunkLoader));
            sentChunks.addAll(chunkReadyToSend.keySet());
            return;
        }

        var chunkSendingStrategy = AllayServer.getSettings().worldSettings().chunkSendingStrategy();
        if (chunkSendingStrategy == ASYNC) {
            for (var chunk : chunkReadyToSend.values()) {
                long hash = HashUtils.hashXZ(chunk.getX(), chunk.getZ());
                if (pendingAsyncChunks.contains(hash)) continue;
                if (!canSendChunk(chunk)) {
                    deferredChunk(chunk);
                    chunkSendingQueue.enqueue(hash);
                    continue;
                }
                pendingAsyncChunks.add(hash);
                asyncChunkSender.addChunkToSendingQueue(java.util.List.of(chunk));
            }
        } else {
            chunkReadyToSend.values().stream()
                    .sorted(chunkDistanceComparator)
                    .forEachOrdered(chunk -> {
                        long hash = HashUtils.hashXZ(chunk.getX(), chunk.getZ());
                        if (sendChunk(chunk)) {
                            chunk.addChunkLoader(chunkLoader);
                            sentChunks.add(hash);
                        } else {
                            deferredChunk(chunk);
                            chunkSendingQueue.enqueue(hash);
                        }
                    });
        }
    }

    private boolean canSendChunk(Chunk chunk) {
        return !(player instanceof AllayPlayer allayPlayer) || allayPlayer.canAcceptChunk(chunk);
    }

    private boolean sendChunk(Chunk chunk) {
        if (player instanceof AllayPlayer allayPlayer) return allayPlayer.tryViewChunk(chunk);
        player.viewChunk(chunk);
        return true;
    }

    private void deferredChunk(Chunk chunk) {
        if (!warnedAboutRejectedChunks) {
            warnedAboutRejectedChunks = true;
            log.warn("[ChunkDelivery] Chunk deferred due to client dimension handshake: player={}, chunk=({}, {}), dimension={}",
                    player.getOriginName(), chunk.getX(), chunk.getZ(), chunk.getDimensionType().getIdentifier());
        }
    }

    private void processAsyncResults() {
        ChunkSendResult result;
        while ((result = asyncResults.poll()) != null) {
            var chunk = result.chunk();
            long hash = HashUtils.hashXZ(chunk.getX(), chunk.getZ());
            pendingAsyncChunks.remove(hash);
            if (!result.queued()) {
                deferredChunk(chunk);
                if (inRadiusChunks.contains(hash)) chunkSendingQueue.enqueue(hash);
            } else if (inRadiusChunks.contains(hash)) {
                // Only record/track chunks that made it through the protocol gate.
                chunk.addChunkLoader(chunkLoader);
                sentChunks.add(hash);
            } else {
                // The player moved out of range while the async packet was being queued.
                player.removeChunk(hash);
            }
        }
    }

    private boolean isChunkInRadius(int chunkX, int chunkZ, int radius) {
        return chunkX * chunkX + chunkZ * chunkZ <= radius * radius;
    }

    private boolean isActualPlayer() {
        return this.player != null;
    }

    private class AsyncChunkSender {

        private final BlockingQueue<Chunk> chunkSendingQueue;
        private final AtomicBoolean isRunning;
        private final Thread senderThread;

        public AsyncChunkSender() {
            this.chunkSendingQueue = new LinkedBlockingQueue<>();
            this.isRunning = new AtomicBoolean(true);
            this.senderThread = Thread.ofVirtual().start(() -> {
                while (isRunning.get()) {
                    Chunk chunk;
                    try {
                        chunk = chunkSendingQueue.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!isRunning.get()) return;
                    try {
                        asyncResults.add(new ChunkSendResult(chunk, sendChunk(chunk)));
                    } catch (RuntimeException exception) {
                        log.error("[ChunkDelivery] Async chunk send FAILED: player={}, chunk=({}, {})",
                                player.getOriginName(), chunk.getX(), chunk.getZ(), exception);
                        asyncResults.add(new ChunkSendResult(chunk, false));
                    }
                }
            });
        }

        public void addChunkToSendingQueue(Collection<Chunk> chunks) {
            chunkSendingQueue.addAll(chunks);
        }

        public void stop() {
            isRunning.set(false);
            chunkSendingQueue.clear();
            senderThread.interrupt();
        }
    }

    private final class HashedChunkDistanceComparator implements LongComparator {
        @Override
        public int compare(long chunkHash1, long chunkHash2) {
            var floor = MathUtils.floor(chunkLoader.getLocation());
            var loaderChunkX = floor.x >> 4;
            var loaderChunkZ = floor.z >> 4;
            var chunkDX1 = loaderChunkX - HashUtils.getXFromHashXZ(chunkHash1);
            var chunkDZ1 = loaderChunkZ - HashUtils.getZFromHashXZ(chunkHash1);
            var chunkDX2 = loaderChunkX - HashUtils.getXFromHashXZ(chunkHash2);
            var chunkDZ2 = loaderChunkZ - HashUtils.getZFromHashXZ(chunkHash2);
            return Integer.compare(
                    chunkDX1 * chunkDX1 + chunkDZ1 * chunkDZ1,
                    chunkDX2 * chunkDX2 + chunkDZ2 * chunkDZ2
            );
        }
    }

    private final class ChunkDistanceComparator implements Comparator<Chunk> {
        @Override
        public int compare(Chunk c1, Chunk c2) {
            var floor = MathUtils.floor(chunkLoader.getLocation());
            var loaderChunkX = floor.x >> 4;
            var loaderChunkZ = floor.z >> 4;
            var chunkDX1 = loaderChunkX - c1.getX();
            var chunkDZ1 = loaderChunkZ - c1.getZ();
            var chunkDX2 = loaderChunkX - c2.getX();
            var chunkDZ2 = loaderChunkZ - c2.getZ();
            return Integer.compare(
                    chunkDX1 * chunkDX1 + chunkDZ1 * chunkDZ1,
                    chunkDX2 * chunkDX2 + chunkDZ2 * chunkDZ2
            );
        }
    }
}
