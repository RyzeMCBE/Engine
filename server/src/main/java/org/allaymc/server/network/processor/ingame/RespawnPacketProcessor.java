package org.allaymc.server.network.processor.ingame;

import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.block.component.BlockRespawnPointComponent;
import org.allaymc.api.block.dto.Block;
import org.allaymc.api.entity.EntityState;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.eventbus.event.player.PlayerRespawnEvent;
import org.allaymc.api.math.location.Location3ic;
import org.allaymc.api.math.position.Position3i;
import org.allaymc.api.player.Player;
import org.allaymc.api.scheduler.Task;
import org.allaymc.api.server.Server;
import org.allaymc.server.network.processor.PacketProcessor;
import org.allaymc.server.player.AllayPlayer;
import org.allaymc.server.world.AllayDimension;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketType;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author IWareQ | daoge_cmd
 */
@Slf4j
public class RespawnPacketProcessor extends PacketProcessor<RespawnPacket> {

    private static final int MAX_RESPAWN_WAIT_TICKS = 200;
    // Packet processor instances may be created per protocol/session. Keep one guard
    // per live controller so repeated CLIENT_READY packets cannot respawn twice.
    private static final Set<Player> RESPAWNING = ConcurrentHashMap.newKeySet();

    enum Readiness {
        IGNORE, WAIT, READY
    }

    static Readiness readiness(EntityState state, float health) {
        // Ignore unsolicited packets and packets from an already respawning player.
        if (health > 0) return Readiness.IGNORE;
        return switch (state) {
            case DESPAWNED -> Readiness.READY;
            case ALIVE, DEAD, DESPAWNED_LATER -> Readiness.WAIT;
            case SPAWNED_LATER -> Readiness.IGNORE;
        };
    }

    @Override
    public void handleSync(Player player, RespawnPacket packet, long receiveTime) {
        if (packet.getState() != RespawnPacket.State.CLIENT_READY) {
            log.warn("Respawn state must be CLIENT_READY, but got {}", packet.getState());
            return;
        }

        var entity = player.getControlledEntity();
        if (entity == null || !player.isConnected()) return;

        var readiness = readiness(entity.getState(), entity.getHealth());
        if (readiness == Readiness.IGNORE || !RESPAWNING.add(player)) return;

        if (readiness == Readiness.READY) {
            completeRespawn(player, entity);
            return;
        }

        // Immediate-respawn clients may send CLIENT_READY while the player is
        // still DEAD (or before the next tick transitions ALIVE -> DEAD).
        // Do not discard the only readiness packet; replay it on the world
        // thread as soon as the entity has actually been despawned.
        try {
            entity.getWorld().getScheduler().scheduleRepeating(Server.getInstance(), new Task() {
                private int ticksLeft = MAX_RESPAWN_WAIT_TICKS;

                @Override
                public boolean onRun() {
                    if (!player.isConnected() || player.getControlledEntity() != entity) {
                        RESPAWNING.remove(player);
                        return false;
                    }

                    var currentReadiness = readiness(entity.getState(), entity.getHealth());
                    if (currentReadiness == Readiness.READY) {
                        completeRespawn(player, entity);
                        return false;
                    }
                    if (currentReadiness == Readiness.IGNORE) {
                        RESPAWNING.remove(player);
                        return false;
                    }

                    if (--ticksLeft <= 0) {
                        RESPAWNING.remove(player);
                        log.error("Respawn timed out for {} in entity state {}",
                                player.getOriginName(), entity.getState());
                        // Do not leave the client frozen forever if the world's
                        // entity removal queue has failed to complete.
                        player.disconnect("Respawn timed out. Please reconnect.");
                        return false;
                    }
                    return true;
                }
            }, 1);
        } catch (RuntimeException exception) {
            RESPAWNING.remove(player);
            throw exception;
        }
    }

    private void completeRespawn(Player player, EntityPlayer entity) {
        try {
            var event = new PlayerRespawnEvent(entity);
            event.setRespawnLocation(findSpawnPoint(entity));
            event.call();

            var spawnPoint = event.getRespawnLocation();
            if (spawnPoint == null) {
                throw new IllegalStateException("PlayerRespawnEvent provided a null spawn point");
            }

            // NOTICE: No need to set runtime entity id
            var allayPlayer = (AllayPlayer) player;
            allayPlayer.sendPacket(allayPlayer.getProtocol().getEncoder().encodeRespawn(spawnPoint));

            // Clean up the old chunk loader and viewer state before adding the
            // player again. The callback only runs after entity registration.
            var dimension = (AllayDimension) entity.getDimension();
            dimension.removePlayer(player, () -> {
                if (!player.isConnected() || player.getControlledEntity() != entity) {
                    RESPAWNING.remove(player);
                    return;
                }
                try {
                    dimension.addPlayer(player, () -> {
                        try {
                            resetData(entity);
                            entity.teleport(spawnPoint);
                        } finally {
                            RESPAWNING.remove(player);
                        }
                    });
                } catch (RuntimeException exception) {
                    RESPAWNING.remove(player);
                    log.error("Could not add {} back to the world during respawn",
                            player.getOriginName(), exception);
                    player.disconnect("Respawn failed. Please reconnect.");
                }
            });
        } catch (RuntimeException exception) {
            RESPAWNING.remove(player);
            log.error("Failed to respawn {}", player.getOriginName(), exception);
            if (player.isConnected()) {
                player.disconnect("Respawn failed. Please reconnect.");
            }
        }
    }

    private Location3ic findSpawnPoint(EntityPlayer entity) {
        var spawnPoint = entity.validateAndGetSpawnPoint();
        var dimension = spawnPoint.dimension();

        var blockState = dimension.getBlockState(spawnPoint.x(), spawnPoint.y(), spawnPoint.z());
        var blockBehavior = blockState.getBlockType().getBlockBehavior();
        if (blockBehavior instanceof BlockRespawnPointComponent respawnPointComponent) {
            var respawnLocation = respawnPointComponent.onPlayerRespawn(entity, new Block(blockState, new Position3i(spawnPoint, dimension)));
            if (respawnLocation != null) {
                return respawnLocation;
            }

            var globalSpawnPoint = Server.getInstance().getWorldPool().getGlobalSpawnPoint();
            entity.setSpawnPoint(globalSpawnPoint);
            return globalSpawnPoint;
        }

        // Block-anchored spawn points (bed/respawn anchor) that no longer have a valid block
        // should fall back to world spawn with an appropriate message
        var source = entity.getSpawnPointType();
        if (source.invalidSpawnKey != null) {
            entity.sendTranslatable(source.invalidSpawnKey);
            var globalSpawnPoint = Server.getInstance().getWorldPool().getGlobalSpawnPoint();
            entity.setSpawnPoint(globalSpawnPoint);
            return globalSpawnPoint;
        }

        return spawnPoint;
    }

    private void resetData(EntityPlayer player) {
        player.removeAllEffects();
        player.resetHealth();
        player.resetFoodData();
        player.extinguish();
        player.setAirSupplyTicks(player.getAirSupplyMaxTicks());
    }

    @Override
    public BedrockPacketType getPacketType() {
        return BedrockPacketType.RESPAWN;
    }
}
