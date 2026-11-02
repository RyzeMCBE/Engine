package org.allaymc.server.network.processor.ingame;

import org.allaymc.api.container.Container;
import org.allaymc.api.container.ContainerViewer;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.item.interfaces.ItemAirStack;
import org.allaymc.api.item.type.ItemTypes;
import org.allaymc.server.container.impl.AbstractPlayerContainer;
import org.allaymc.server.container.processor.ContainerActionProcessor;
import org.allaymc.server.player.AllayPlayer;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TransferItemStackRequestAction;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Captures the server-authoritative state of all slots in a pure transfer/swap request.
 * No world drops, crafting operations or plugin side effects are rolled back.
 *
 * The processor sends ItemStackResponse before publishing updated slots to other
 * viewers, so the initiating player gets its authoritative stack IDs first.
 */
final class InventoryTransferTransaction {
    private final AllayPlayer player;
    private final Map<Container, Map<Integer, ItemStack>> snapshots = new IdentityHashMap<>();

    InventoryTransferTransaction(AllayPlayer player) {
        this.player = player;
    }

    static boolean supports(ItemStackRequestAction[] actions) {
        if (actions.length == 0) return false;
        for (var action : actions) {
            if (!(action instanceof TransferItemStackRequestAction || action instanceof SwapAction)) {
                return false;
            }
        }
        return true;
    }

    static InventoryTransferTransaction capture(AllayPlayer player, ItemStackRequestAction[] actions) {
        var transaction = new InventoryTransferTransaction(player);
        for (var action : actions) {
            for (var slot : ItemStackRequestPacketProcessor.referencedSlots(action)) {
                var container = ContainerActionProcessor.getContainerFrom(player, slot.containerName());
                int index = ContainerActionProcessor.fromNetworkSlotIndex(container, slot.slot());
                transaction.captureSlot(container, index);
            }
        }
        return transaction;
    }

    // Package-private for regression tests of rollback, stack IDs and observer updates.
    void captureSlot(Container container, int index) {
        var original = container.getItemStack(index);
        snapshots.computeIfAbsent(container, ignored -> new LinkedHashMap<>())
                .putIfAbsent(index, original.getItemType() == ItemTypes.AIR
                        ? ItemAirStack.AIR_STACK : original.copy(false));
    }

    /** Restore every captured slot, including counts and original stack network IDs. */
    void rollback() {
        snapshots.forEach((container, slots) -> slots.forEach((index, before) ->
                container.setItemStack(index, before, false)));
    }

    /** Send authoritative contents to the actor following an ERROR response. */
    void resyncActor() {
        Set<Container> opened = player.getOpenedContainers();
        for (var container : snapshots.keySet()) {
            if (container instanceof AbstractPlayerContainer || opened != null && opened.contains(container)) {
                player.viewContainerContents(container);
            }
        }
    }

    /** Publish committed slot changes to every OTHER viewer of the inventory. */
    void notifyObservers() {
        snapshots.forEach((container, slots) -> {
            var viewers = container.getViewers();
            if (viewers == null) return;
            for (ContainerViewer viewer : viewers.values()) {
                if (viewer == player) continue;
                for (int index : slots.keySet()) {
                    viewer.viewContainerSlot(container, index);
                }
            }
        });
    }
}
