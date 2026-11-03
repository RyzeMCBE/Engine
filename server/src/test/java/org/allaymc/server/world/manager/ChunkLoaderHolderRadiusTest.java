package org.allaymc.server.world.manager;

import org.allaymc.api.math.location.Location3dc;
import org.allaymc.api.utils.hash.HashUtils;
import org.allaymc.api.world.chunk.ChunkLoader;
import org.allaymc.api.world.manager.ChunkManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;

class ChunkLoaderHolderRadiusTest {

    @Test
    void aRadiusChangeRebuildsChunkQueueWithoutMovingToAnotherChunk() {
        var manager = mock(ChunkManager.class);
        var loader = mock(ChunkLoader.class);
        var location = mock(Location3dc.class);
        var radius = new AtomicInteger(1);

        when(loader.getLocation()).thenReturn(location);
        when(location.x()).thenReturn(0d);
        when(location.y()).thenReturn(64d);
        when(location.z()).thenReturn(0d);
        when(loader.getChunkLoadingRadius()).thenAnswer(invocation -> radius.get());

        var holder = new ChunkLoaderHolder(manager, loader);
        holder.tick();
        verify(manager, never()).loadChunk(HashUtils.hashXZ(2, 0));

        radius.set(2);
        holder.tick();
        verify(manager).loadChunk(HashUtils.hashXZ(2, 0));
    }
}
