package org.allaymc.server.network.processor.login;

import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.eventbus.event.server.PlayerJoinEvent;
import org.allaymc.api.message.TrKeys;
import org.allaymc.api.player.ClientState;
import org.allaymc.api.player.Player;
import org.allaymc.api.server.Server;
import org.allaymc.api.utils.TextFormat;
import org.allaymc.server.network.processor.ingame.ILoginPacketProcessor;
import org.allaymc.server.player.AllayPlayer;
import org.allaymc.server.world.AllayDimension;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketType;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;

/**
 * @author daoge_cmd
 */
public class SetLocalPlayerAsInitializedPacketProcessor extends ILoginPacketProcessor<SetLocalPlayerAsInitializedPacket> {
    @Override
    public void handle(Player player, SetLocalPlayerAsInitializedPacket packet) {
        var allayPlayer = (AllayPlayer) player;
        var event = new PlayerJoinEvent(player, TextFormat.YELLOW + "%" + TrKeys.MC_MULTIPLAYER_PLAYER_JOINED, TrKeys.MC_DISCONNECTIONSCREEN_NOREASON);
        if (!event.call()) {
            player.disconnect(event.getDisconnectReason());
            return;
        }

        allayPlayer.setClientState(ClientState.IN_GAME);
        var entity = player.getControlledEntity();
        // The initial inventory snapshot is sent before the client reports that
        // local player initialization is complete. Join listeners may also restore
        // combatlogger/kit equipment in between. Reconcile the final authoritative
        // stack network IDs after join handlers and client initialization.
        for (var containerType : java.util.List.of(
                ContainerTypes.INVENTORY, ContainerTypes.ARMOR,
                ContainerTypes.OFFHAND, ContainerTypes.CURSOR)) {
            var container = entity.getContainer(containerType);
            if (container != null) player.viewContainerContents(container);
        }
        // We only accept player's movement inputs, which are after SetLocalPlayerAsInitializedPacket,
        // So after the player sent SetLocalPlayerAsInitializedPacket, we need to sync the pos with the
        // client, otherwise the client will snap into the ground
        player.viewEntityLocation(entity, entity.getLocation(), true);
        // Send primitive shapes to the player after the player fully joined
        ((AllayDimension) entity.getDimension()).addPrimitiveShapesTo(player);

        var joinMessage = event.getJoinMessage();
        if (joinMessage != null && !joinMessage.isBlank()) {
            Server.getInstance().getMessageChannel().broadcastTranslatable(joinMessage, allayPlayer.getLoginData().getXname());
        }
    }

    @Override
    public BedrockPacketType getPacketType() {
        return BedrockPacketType.SET_LOCAL_PLAYER_AS_INITIALIZED;
    }
}
