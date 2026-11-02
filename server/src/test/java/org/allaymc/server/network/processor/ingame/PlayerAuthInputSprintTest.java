package org.allaymc.server.network.processor.ingame;

import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.player.Player;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerAuthInputSprintTest {

    @Test
    void lowFoodSprintRejectionStillProcessesFollowingInputs() {
        var player = mock(Player.class);
        var entity = mock(EntityPlayer.class);
        when(player.getControlledEntity()).thenReturn(entity);
        when(entity.getFoodLevel()).thenReturn(6);

        Set<PlayerAuthInputData> inputs = new LinkedHashSet<>();
        inputs.add(PlayerAuthInputData.START_SPRINTING);
        inputs.add(PlayerAuthInputData.START_SNEAKING);
        inputs.add(PlayerAuthInputData.STOP_GLIDING);

        new PlayerAuthInputPacketProcessor().handleInputData(player, inputs);

        verify(entity, never()).setSprinting(true);
        verify(entity).setSneaking(true);
        verify(entity).setGliding(false);
    }

    @Test
    void sprintStartsWhenFoodIsAboveVanillaThreshold() {
        var player = mock(Player.class);
        var entity = mock(EntityPlayer.class);
        when(player.getControlledEntity()).thenReturn(entity);
        when(entity.getFoodLevel()).thenReturn(7);

        new PlayerAuthInputPacketProcessor().handleInputData(
                player, Set.of(PlayerAuthInputData.START_SPRINTING));

        verify(entity).setSprinting(true);
    }
}
