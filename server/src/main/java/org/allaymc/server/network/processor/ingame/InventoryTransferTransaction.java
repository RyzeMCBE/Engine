package org.allaymc.server.network.processor.ingame;

import org.allaymc.api.container.Container;
import org.allaymc.api.container.ContainerViewer;
import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.item.interfaces.ItemAirStack;
import org.allaymc.api.item.type.ItemTypes;
import org.allaymc.server.container.impl.AbstractPlayerContainer;
import org.allaymc.server.container.processor.ContainerActionProcessor;
import org.allaymc.server.player.AllayPlayer;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TransferItemStackRequestAction;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
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

    /**
     * A malformed slot may fail before a transfer snapshot exists. After the ERROR response,
     * resend all player-owned inventories and currently opened containers so the client can
     * discard predictions without trusting the rejected network slot or its stack ID.
     */
    static void resyncInvalidSlots(AllayPlayer player) {
        Set<Container> sent = Collections.newSetFromMap(new IdentityHashMap<>());
        var actor = player.getControlledEntity();
        if (actor != null) {
            for (var type : List.of(ContainerTypes.INVENTORY, ContainerTypes.ARMOR,
                    ContainerTypes.OFFHAND, ContainerTypes.CURSOR)) {
                var container = actor.getContainer(type);
                if (container != null && sent.add(container)) {
                    player.viewContainerContents(container);
                }
            }
        }
        var opened = player.getOpenedContainers();
        if (opened != null) {
            for (var container : opened) {
                if (container != null && sent.add(container)) {
                    player.viewContainerContents(container);
                }
            }
        }
    }

    /** Send authoritative contents to the actor following an ERROR response. */
    void resyncActor() {
        Set<Container> opened = player.getOpenedContainers();
        // An armor/cursor rejection can leave the Bedrock UI predicting a
        // partially completed click across multiple player containers.
        // Reconcile ALL related stacks, not just the two requested slots.
        // Never accept an invalid stack ID to "fix" client prediction.
        Set<Container> sent = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean equipmentRequest = snapshots.keySet().stream().anyMatch(container ->
                container.getContainerType() == ContainerTypes.ARMOR
                        || container.getContainerType() == ContainerTypes.CURSOR);
        if (equipmentRequest && player.getControlledEntity() != null) {
            for (var type : List.of(ContainerTypes.INVENTORY, ContainerTypes.ARMOR,
                    ContainerTypes.OFFHAND, ContainerTypes.CURSOR)) {
                var container = player.getControlledEntity().getContainer(type);
                if (container != null && sent.add(container)) {
                    player.viewContainerContents(container);
                }
            }
        }
        for (var container : snapshots.keySet()) {
            if (sent.add(container) && (container instanceof AbstractPlayerContainer
                    || opened != null && opened.contains(container))) {
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
