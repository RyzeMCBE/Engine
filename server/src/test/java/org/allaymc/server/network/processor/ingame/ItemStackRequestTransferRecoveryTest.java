package org.allaymc.server.network.processor.ingame;

import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.item.interfaces.ItemAirStack;
import org.allaymc.api.player.Player;
import org.allaymc.server.container.impl.BaseContainer;
import org.allaymc.server.container.processor.ActionResponse;
import org.allaymc.server.container.processor.ContainerActionProcessor;
import org.allaymc.server.container.processor.PlaceActionProcessor;
import org.allaymc.server.network.protocol.PacketEncoder;
import org.allaymc.server.network.protocol.Protocol;
import org.allaymc.server.player.AllayPlayer;
import org.allaymc.testutils.AllayTestExtension;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponse;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.allaymc.api.item.type.ItemTypes.DIAMOND;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class ItemStackRequestTransferRecoveryTest {
    private static final FullContainerName CHEST_NAME = new FullContainerName(ContainerSlotType.LEVEL_ENTITY, null);
    private static final FullContainerName INV_NAME = new FullContainerName(ContainerSlotType.INVENTORY, null);

    private static AllayPlayer playerWithResponses() {
        var player = mock(AllayPlayer.class);
        var protocol = mock(Protocol.class);
        var encoder = mock(PacketEncoder.class);
        when(player.getProtocol()).thenReturn(protocol);
        when(protocol.getEncoder()).thenReturn(encoder);
        when(encoder.encodeItemStackResponse(any())).thenAnswer(invocation -> {
            Collection<ItemStackResponse> responses = invocation.getArgument(0);
            var packet = new ItemStackResponsePacket();
            packet.getEntries().addAll(responses);
            return packet;
        });
        return player;
    }

    private static ItemStackRequestPacket packet(ItemStackRequestAction... actions) {
        var packet = new ItemStackRequestPacket();
        packet.getRequests().add(new ItemStackRequest(-43, actions, new String[0]));
        return packet;
    }

    private static ItemStackResponse response(AllayPlayer player) {
        var captor = ArgumentCaptor.forClass(ItemStackResponsePacket.class);
        verify(player).sendPacket(captor.capture());
        return captor.getValue().getEntries().getFirst();
    }

    @Test
    void rejectedSecondTransferUndoesFirstTransferAndResendsBothInventories() {
        var player = playerWithResponses();
        var chest = new BaseContainer(ContainerTypes.CHEST);
        var inventory = new BaseContainer(ContainerTypes.INVENTORY);
        var item = DIAMOND.createItemStack(7);
        int originalNetworkId = item.getUniqueId();
        chest.setItemStack(0, item, false);
        when(player.getOpenedContainers()).thenReturn(Set.of(chest, inventory));
        var source = new ItemStackRequestSlotData(ContainerSlotType.LEVEL_ENTITY, 0, originalNetworkId, CHEST_NAME);
        var destination = new ItemStackRequestSlotData(ContainerSlotType.INVENTORY, 3, 0, INV_NAME);

        var processor = new ItemStackRequestPacketProcessor() {
            @Override boolean allowRequest(Player ignored, ItemStackRequestAction[] actions) { return true; }
        };
        processor.processorHolder.registerProcessor(new ContainerActionProcessor<PlaceAction>() {
            @Override
            public ActionResponse handle(PlaceAction action, Player p, int index, ItemStackRequestAction[] all, Map<String, Object> pool) {
                chest.setItemStack(0, ItemAirStack.AIR_STACK, false);
                inventory.setItemStack(3, item, false);
                return new ActionResponse(true, List.of());
            }
            @Override public ItemStackRequestActionType getType() { return ItemStackRequestActionType.PLACE; }
        });
        processor.processorHolder.registerProcessor(new ContainerActionProcessor<TakeAction>() {
            @Override
            public ActionResponse handle(TakeAction action, Player p, int index, ItemStackRequestAction[] all, Map<String, Object> pool) {
                return error();
            }
            @Override public ItemStackRequestActionType getType() { return ItemStackRequestActionType.TAKE; }
        });

        try (var slots = mockStatic(ContainerActionProcessor.class)) {
            slots.when(() -> ContainerActionProcessor.getContainerFrom(player, CHEST_NAME)).thenReturn(chest);
            slots.when(() -> ContainerActionProcessor.getContainerFrom(player, INV_NAME)).thenReturn(inventory);
            slots.when(() -> ContainerActionProcessor.fromNetworkSlotIndex(chest, 0)).thenReturn(0);
            slots.when(() -> ContainerActionProcessor.fromNetworkSlotIndex(inventory, 3)).thenReturn(3);
            processor.handleSync(player, packet(new PlaceAction(7, source, destination),
                    new TakeAction(7, source, destination)), 0);
        }

        assertEquals(ItemStackResponseStatus.ERROR, response(player).result());
        assertEquals(7, chest.getItemStack(0).getCount());
        assertEquals(originalNetworkId, chest.getItemStack(0).getUniqueId());
        assertEquals(ItemAirStack.AIR_STACK, inventory.getItemStack(3));
        verify(player).viewContainerContents(chest);
        verify(player).viewContainerContents(inventory);
    }

    @Test
    void mismatchedStackNetworkIdRejectsPlacementWithoutMovingAnItem() {
        var player = playerWithResponses();
        var chest = new BaseContainer(ContainerTypes.CHEST);
        var inventory = new BaseContainer(ContainerTypes.INVENTORY);
        var item = DIAMOND.createItemStack(5);
        chest.setItemStack(0, item, false);
        when(player.getOpenedContainers()).thenReturn(Set.of(chest, inventory));
        var source = new ItemStackRequestSlotData(ContainerSlotType.LEVEL_ENTITY, 0, item.getUniqueId() + 999, CHEST_NAME);
        var destination = new ItemStackRequestSlotData(ContainerSlotType.INVENTORY, 3, 0, INV_NAME);

        var processor = new ItemStackRequestPacketProcessor() {
            @Override boolean allowRequest(Player ignored, ItemStackRequestAction[] actions) { return true; }
        };
        processor.processorHolder.registerProcessor(new PlaceActionProcessor());
        try (var slots = mockStatic(ContainerActionProcessor.class)) {
            slots.when(() -> ContainerActionProcessor.getContainerFrom(player, CHEST_NAME)).thenReturn(chest);
            slots.when(() -> ContainerActionProcessor.getContainerFrom(player, INV_NAME)).thenReturn(inventory);
            slots.when(() -> ContainerActionProcessor.fromNetworkSlotIndex(chest, 0)).thenReturn(0);
            slots.when(() -> ContainerActionProcessor.fromNetworkSlotIndex(inventory, 3)).thenReturn(3);
            processor.handleSync(player, packet(new PlaceAction(1, source, destination)), 0);
        }
        assertEquals(ItemStackResponseStatus.ERROR, response(player).result());
        assertEquals(5, chest.getItemStack(0).getCount());
        assertEquals(ItemAirStack.AIR_STACK, inventory.getItemStack(3));
        verify(player).viewContainerContents(chest);
    }
}
