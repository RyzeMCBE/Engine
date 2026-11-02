package org.allaymc.server.entity.component.player;

import org.allaymc.api.container.Container;
import org.allaymc.api.container.ContainerTypes;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.world.gamerule.GameRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerDeathPersistenceTest {

    @Test
    void onlyEnderChestIsProtectedFromNormalDeathDrops() {
        var holder = mock(EntityPlayerContainerHolderComponentImpl.class, CALLS_REAL_METHODS);
        var enderChest = mock(Container.class);
        var inventory = mock(Container.class);
        var armor = mock(Container.class);
        var offhand = mock(Container.class);

        doReturn(ContainerTypes.ENDER_CHEST).when(enderChest).getContainerType();
        doReturn(ContainerTypes.INVENTORY).when(inventory).getContainerType();
        doReturn(ContainerTypes.ARMOR).when(armor).getContainerType();
        doReturn(ContainerTypes.OFFHAND).when(offhand).getContainerType();

        assertFalse(holder.shouldDropContents(enderChest));
        assertTrue(holder.shouldDropContents(inventory));
        assertTrue(holder.shouldDropContents(armor));
        assertTrue(holder.shouldDropContents(offhand));
    }

    @Test
    void deathTimerRespectsImmediateRespawnGamerule() {
        var player = mock(EntityPlayer.class, RETURNS_DEEP_STUBS);
        var living = new TestableLiving();
        living.setPlayer(player);

        when(player.getWorld().getWorldData().<Boolean>getGameRuleValue(GameRule.DO_IMMEDIATE_RESPAWN))
                .thenReturn(true, false);

        assertFalse(living.hasTimer(), "Immediate respawn must not wait for the 20-tick death timer");
        assertTrue(living.hasTimer(), "Normal respawn retains the standard death timer");
    }

    private static final class TestableLiving extends EntityPlayerLivingComponentImpl {
        private void setPlayer(EntityPlayer player) {
            this.thisPlayer = player;
        }

        private boolean hasTimer() {
            return hasDeadTimer();
        }
    }
}
