package org.allaymc.server.network.processor.ingame;

import org.allaymc.api.container.Container;
import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.container.ContainerViewer;
import org.allaymc.api.item.interfaces.ItemAirStack;
import org.allaymc.server.container.impl.BaseContainer;
import org.allaymc.server.player.AllayPlayer;
import org.allaymc.testutils.AllayTestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.Set;

import static org.allaymc.api.item.type.ItemTypes.DIAMOND;
import static org.allaymc.api.item.type.ItemTypes.STONE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class InventoryTransferTransactionTest {

    @Test
    void restoresBothStacksAndNetworkIdsAfterRejectedFullTransfer() {
        var player = mock(AllayPlayer.class);
        var chest = new BaseContainer(ContainerTypes.CHEST);
        var inventory = new BaseContainer(ContainerTypes.INVENTORY);
        var original = DIAMOND.createItemStack(12);
        int originalId = original.getUniqueId();
        chest.setItemStack(0, original, false);

        var transaction = new InventoryTransferTransaction(player);
        transaction.captureSlot(chest, 0);
        transaction.captureSlot(inventory, 3);

        chest.setItemStack(0, ItemAirStack.AIR_STACK, false);
        inventory.setItemStack(3, original, false);
        transaction.rollback();

        assertEquals(12, chest.getItemStack(0).getCount());
        assertEquals(DIAMOND, chest.getItemStack(0).getItemType());
        assertEquals(originalId, chest.getItemStack(0).getUniqueId());
        assertEquals(ItemAirStack.AIR_STACK, inventory.getItemStack(3));
    }

    @Test
    void restoresInPlaceCountMutationsAfterRejectedPartialMerge() {
        var player = mock(AllayPlayer.class);
        var chest = new BaseContainer(ContainerTypes.CHEST);
        var inventory = new BaseContainer(ContainerTypes.INVENTORY);
        var source = STONE.createItemStack(20);
        var destination = STONE.createItemStack(8);
        int sourceId = source.getUniqueId();
        int destinationId = destination.getUniqueId();
        chest.setItemStack(1, source, false);
        inventory.setItemStack(5, destination, false);

        var transaction = new InventoryTransferTransaction(player);
        transaction.captureSlot(chest, 1);
        transaction.captureSlot(inventory, 5);
        source.setCount(14);
        destination.setCount(14);
        transaction.rollback();

        assertEquals(20, chest.getItemStack(1).getCount());
        assertEquals(8, inventory.getItemStack(5).getCount());
        assertEquals(sourceId, chest.getItemStack(1).getUniqueId());
        assertEquals(destinationId, inventory.getItemStack(5).getUniqueId());
    }

    @Test
    void onlyOtherChestViewersReceiveCommittedSlotUpdates() {
        var actor = mock(AllayPlayer.class);
        var spectator = mock(ContainerViewer.class);
        var chest = mock(Container.class);
        when(chest.getItemStack(2)).thenReturn(DIAMOND.createItemStack(1));
        when(chest.getViewers()).thenReturn(Map.of((byte) 1, actor, (byte) 2, spectator));

        var transaction = new InventoryTransferTransaction(actor);
        transaction.captureSlot(chest, 2);
        transaction.notifyObservers();

        verify(spectator).viewContainerSlot(chest, 2);
        verify(actor, never()).viewContainerSlot(chest, 2);
    }

    @Test
    void rejectedRequestResendsAuthoritativeOpenedChestContents() {
        var actor = mock(AllayPlayer.class);
        var chest = mock(Container.class);
        when(chest.getItemStack(0)).thenReturn(STONE.createItemStack(4));
        when(actor.getOpenedContainers()).thenReturn(Set.of(chest));

        var transaction = new InventoryTransferTransaction(actor);
        transaction.captureSlot(chest, 0);
        transaction.resyncActor();

        verify(actor).viewContainerContents(chest);
    }

    @Test
    void doesNotResyncContainersNotOpenedByPlayer() {
        var actor = mock(AllayPlayer.class);
        var chest = mock(Container.class);
        when(chest.getItemStack(0)).thenReturn(STONE.createItemStack(4));
        when(actor.getOpenedContainers()).thenReturn(Set.of());

        var transaction = new InventoryTransferTransaction(actor);
        transaction.captureSlot(chest, 0);
        transaction.resyncActor();

        verify(actor, never()).viewContainerContents(chest);
    }
}
