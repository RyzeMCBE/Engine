package org.allaymc.server.entity.component;

import org.allaymc.api.entity.EntityState;
import org.allaymc.api.entity.component.EntityBaseComponent;
import org.allaymc.api.entity.effect.EffectTypes;
import org.allaymc.api.entity.interfaces.EntityLiving;
import org.allaymc.api.eventbus.event.entity.EntityDieEvent;
import org.allaymc.api.eventbus.event.entity.EntityHealthChangeEvent;
import org.allaymc.api.math.location.Location3d;
import org.allaymc.api.server.Server;
import org.allaymc.api.world.gamerule.GameRule;
import org.allaymc.server.component.ComponentManager;
import org.allaymc.testutils.AllayTestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class DeathHealthConsistencyTest {

    @Test
    void deadAndDespawnedEntitiesCannotRecoverHealth() {
        for (var state : new EntityState[]{
                EntityState.DEAD, EntityState.DESPAWNED_LATER, EntityState.DESPAWNED
        }) {
            var entity = mock(EntityLiving.class);
            var base = mock(EntityBaseComponent.class);
            when(base.getState()).thenReturn(state);
            var living = new TestLiving(entity, base, 0);

            living.setHealth(8f);
            assertEquals(0f, living.getHealth(), "Unexpected revival from " + state);
        }
    }

    @Test
    void healthListenerCannotReviveDeadEntityByRewritingTheEvent() {
        var entity = mock(EntityLiving.class);
        var base = mock(EntityBaseComponent.class);
        when(base.getState()).thenReturn(EntityState.DEAD);
        var living = new TestLiving(entity, base, 0);

        Consumer<EntityHealthChangeEvent> change = event -> {
            if (event.getEntity() == entity) event.setNewHealth(10f);
        };
        var bus = Server.getInstance().getEventBus();
        bus.registerListenerFor(EntityHealthChangeEvent.class, change);
        try {
            living.setHealth(0f);
            assertEquals(0f, living.getHealth());
        } finally {
            bus.unregisterListenerFor(EntityHealthChangeEvent.class, change);
        }
    }

    @Test
    void eventHealthIsClampedAndFractionalHealthCannotConfuseTheClient() {
        var entity = mock(EntityLiving.class);
        var base = mock(EntityBaseComponent.class);
        when(base.getState()).thenReturn(EntityState.ALIVE);
        var living = new TestLiving(entity, base, 10);

        Consumer<EntityHealthChangeEvent> change = event -> {
            if (event.getEntity() == entity) event.setNewHealth(0.5f);
        };
        var bus = Server.getInstance().getEventBus();
        bus.registerListenerFor(EntityHealthChangeEvent.class, change);
        try {
            living.setHealth(9f);
            assertEquals(0f, living.getHealth());
        } finally {
            bus.unregisterListenerFor(EntityHealthChangeEvent.class, change);
        }
    }

    @Test
    void lethalHealthCannotBeReversedBeforeTheDeathTick() {
        var entity = mock(EntityLiving.class);
        var base = mock(EntityBaseComponent.class);
        when(base.getState()).thenReturn(EntityState.ALIVE);
        when(base.isAlive()).thenReturn(true);
        var living = new TestLiving(entity, base, 4);

        living.setHealth(0f);
        living.setHealth(8f);

        assertEquals(0f, living.getHealth(), "Health must stay at zero until death is processed");
    }

    @Test
    void zeroHealthDoesNotTickRegenerationBeforeDeathProcessing() {
        var entity = mock(EntityLiving.class);
        var base = mock(EntityBaseComponent.class);
        when(base.getState()).thenReturn(EntityState.ALIVE);
        var living = new TestLiving(entity, base, 0);
        var regeneration = EffectTypes.REGENERATION.createInstance(0, 100);
        living.effects.put(EffectTypes.REGENERATION, regeneration);

        living.tickEffects();

        assertEquals(0f, living.getHealth());
        assertEquals(100, regeneration.getDuration());
    }

    @Test
    void respawnCanRestoreHealthOnceEntityIsAliveAgain() {
        var entity = mock(EntityLiving.class);
        var base = mock(EntityBaseComponent.class);
        when(base.getState()).thenReturn(EntityState.ALIVE);
        var living = new TestLiving(entity, base, 0);

        living.resetHealth();

        assertEquals(living.getMaxHealth(), living.getHealth());
    }

    @Test
    void deathEventSeesDeadStateAndCannotHealThePlayer() {
        var entity = mock(EntityLiving.class, RETURNS_DEEP_STUBS);
        var base = mock(EntityBaseComponentImpl.class);
        var state = new AtomicReference<>(EntityState.ALIVE);
        when(base.getState()).thenAnswer(invocation -> state.get());
        when(base.setState(any(EntityState.class))).thenAnswer(invocation -> {
            state.set(invocation.getArgument(0));
            return true;
        });
        when(base.getLocation()).thenReturn(mock(Location3d.class));
        when(entity.getWorld().getWorldData().<Boolean>getGameRuleValue(GameRule.DO_MOB_LOOT))
                .thenReturn(false);

        var living = new TestLiving(entity, base, 0);
        living.manager = mock(ComponentManager.class);
        var observedDead = new AtomicBoolean();
        Consumer<EntityDieEvent> onDeath = event -> {
            if (event.getEntity() == entity) {
                observedDead.set(state.get() == EntityState.DEAD);
                entity.setHealth(10f);
            }
        };
        var bus = Server.getInstance().getEventBus();
        bus.registerListenerFor(EntityDieEvent.class, onDeath);
        try {
            living.onDie();
            assertTrue(observedDead.get(), "Plugins must see DEAD during EntityDieEvent");
            assertEquals(EntityState.DEAD, state.get());
            assertEquals(0f, living.getHealth());
        } finally {
            bus.unregisterListenerFor(EntityDieEvent.class, onDeath);
        }
    }

    private static final class TestLiving extends EntityLivingComponentImpl {
        private TestLiving(EntityLiving entity, EntityBaseComponent base, float initialHealth) {
            this.thisEntity = entity;
            this.baseComponent = base;
            this.health = initialHealth;
            when(entity.getHealth()).thenAnswer(invocation -> this.health);
            doAnswer(invocation -> {
                setHealth(invocation.getArgument(0));
                return null;
            }).when(entity).setHealth(anyFloat());
        }
    }
}
