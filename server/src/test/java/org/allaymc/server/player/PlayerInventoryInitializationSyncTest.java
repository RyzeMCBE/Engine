package org.allaymc.server.player;

import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.container.Container;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * The client may finish initializing after the initial inventory packets, and
 * join listeners can replace the equipped items in between.
 */
class PlayerInventoryInitializationSyncTest {
    @Test
    void sendsAllPlayerOwnedContainersAfterClientInitialization() {
        var player = mock(AllayPlayer.class);
        var actor = mock(EntityPlayer.class);
        var inventory = mock(Container.class);
        var armor = mock(Container.class);
        var offhand = mock(Container.class);
        var cursor = mock(Container.class);
        when(player.getControlledEntity()).thenReturn(actor);
        doReturn(inventory).when(actor).getContainer(ContainerTypes.INVENTORY);
        doReturn(armor).when(actor).getContainer(ContainerTypes.ARMOR);
        doReturn(offhand).when(actor).getContainer(ContainerTypes.OFFHAND);
        doReturn(cursor).when(actor).getContainer(ContainerTypes.CURSOR);

        doCallRealMethod().when(player).synchronizePlayerInventory();
        player.synchronizePlayerInventory();

        var order = inOrder(player);
        order.verify(player).viewContainerContents(inventory);
        order.verify(player).viewContainerContents(armor);
        order.verify(player).viewContainerContents(offhand);
        order.verify(player).viewContainerContents(cursor);
    }
}
