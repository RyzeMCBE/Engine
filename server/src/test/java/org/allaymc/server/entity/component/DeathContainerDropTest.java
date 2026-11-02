package org.allaymc.server.entity.component;

import org.allaymc.api.container.Container;
import org.allaymc.api.container.ContainerType;
import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.item.ItemStack;
import org.allaymc.server.entity.component.event.CEntityGetDropEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeathContainerDropTest {
    private static final class TestHolder extends EntityContainerHolderComponentImpl {
        private final Map<ContainerType<?>, Container> containers;
        private final boolean keepInventory;

        private TestHolder(Container inventory, Container enderChest, boolean keepInventory) {
            super();
            this.containers = Map.of(
                    ContainerTypes.INVENTORY, inventory,
                    ContainerTypes.ENDER_CHEST, enderChest);
            this.keepInventory = keepInventory;
        }

        @Override
        public Map<ContainerType<?>, Container> getContainers() {
            return containers;
        }

        @Override
        protected boolean canDropItemInContainers() {
            return !keepInventory;
        }

        @Override
        protected boolean shouldDropContents(Container container) {
            return container.getContainerType() != ContainerTypes.ENDER_CHEST;
        }
    }

    @Test
    void deathDropsInventoryButNeverClearsOrDropsPersonalEnderChest() {
        var inventory = mock(Container.class);
        var enderChest = mock(Container.class);
        var inventoryItem = mock(ItemStack.class);
        var protectedItem = mock(ItemStack.class);

        doReturn(ContainerTypes.INVENTORY).when(inventory).getContainerType();
        doReturn(ContainerTypes.ENDER_CHEST).when(enderChest).getContainerType();
        when(inventory.getItemStacks()).thenReturn(java.util.List.of(inventoryItem));
        when(enderChest.getItemStacks()).thenReturn(java.util.List.of(protectedItem));

        var drops = new CEntityGetDropEvent(0);
        new TestHolder(inventory, enderChest, false).onGetDrop(drops);

        assertEquals(java.util.List.of(inventoryItem), drops.getDrops());
        verify(inventory).clearAllSlots();
        verify(enderChest, never()).clearAllSlots();
        verify(enderChest, never()).getItemStacks();
        verify(enderChest).removeAllViewers();
    }

    @Test
    void keepInventoryLeavesRegularAndEnderChestContentsUntouched() {
        var inventory = mock(Container.class);
        var enderChest = mock(Container.class);
        doReturn(ContainerTypes.INVENTORY).when(inventory).getContainerType();
        doReturn(ContainerTypes.ENDER_CHEST).when(enderChest).getContainerType();

        var drops = new CEntityGetDropEvent(0);
        new TestHolder(inventory, enderChest, true).onGetDrop(drops);

        assertTrue(drops.getDrops().isEmpty());
        verify(inventory, never()).clearAllSlots();
        verify(enderChest, never()).clearAllSlots();
        verify(inventory).removeAllViewers();
        verify(enderChest).removeAllViewers();
    }
}
