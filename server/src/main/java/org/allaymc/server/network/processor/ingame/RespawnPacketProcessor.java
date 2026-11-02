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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author IWareQ | daoge_cmd
 */
@Slf4j
public class RespawnPacketProcessor extends PacketProcessor<RespawnPacket> {

    private static final int MAX_RESPAWN_WAIT_TICKS = 200;
    private static final int SLOW_RESPAWN_WARN_TICKS = 40;

    // A single attempt follows the whole lifecycle, not just the CLIENT_READY -> DESPAWNED wait.
    // Without this guard, an entity-registration callback which never runs can freeze the client.
    private static final Map<Player, RespawnProgress> RESPAWNING = new ConcurrentHashMap<>();

    enum Readiness {
        IGNORE, WAIT, READY
    }

    enum Phase {
        WAITING_FOR_DESPAWN, REMOVING_PLAYER, REGISTERING_PLAYER,
        WAITING_FOR_CHUNK_LOADER, COMPLETE
    }

    static final class RespawnProgress {
        final EntityPlayer entity;
        volatile Phase phase = Phase.WAITING_FOR_DESPAWN;
        volatile AllayDimension destination;
        int elapsedTicks;

        RespawnProgress(EntityPlayer entity) {
            this.entity = entity;
        }

        void advance(Phase next) {
            phase = next;
        }

        boolean shouldWarn() {
            return elapsedTicks == SLOW_RESPAWN_WARN_TICKS;
        }

        boolean timedOut() {
            return elapsedTicks >= MAX_RESPAWN_WAIT_TICKS;
        }
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
            log.warn("[Respawn] Unexpected packet state {} from {}", packet.getState(), player.getOriginName());
            return;
        }

        var entity = player.getControlledEntity();
        if (entity == null || !player.isConnected()) return;

        var readiness = readiness(entity.getState(), entity.getHealth());
        if (readiness == Readiness.IGNORE) return;

        var progress = new RespawnProgress(entity);
        if (RESPAWNING.putIfAbsent(player, progress) != null) return;

        // Watch the entire transaction on the world scheduler. This also catches a callback
        // rejected by the entity manager, which previously left RESPAWNING stuck indefinitely.
        try {
            entity.getWorld().getScheduler().scheduleRepeating(Server.getInstance(), new Task() {
                @Override
                public boolean onRun() {
                    if (!isCurrent(player, progress)) return false;
                    if (!player.isConnected() || player.getControlledEntity() != entity) {
                        finish(player, progress);
                        return false;
                    }

                    if (progress.phase == Phase.WAITING_FOR_DESPAWN) {
                        var current = readiness(entity.getState(), entity.getHealth());
                        if (current == Readiness.READY) {
                            completeRespawn(player, progress);
                        } else if (current == Readiness.IGNORE) {
                            log.warn("[Respawn] Player {} no longer needs respawn (state={}, health={})",
                                    player.getOriginName(), entity.getState(), entity.getHealth());
                            finish(player, progress);
                        }
                    } else if (progress.phase == Phase.WAITING_FOR_CHUNK_LOADER
                            && entity.isSpawned()
                            && entity.getHealth() > 0
                            && entity.getDimension() == progress.destination
                            && progress.destination.getChunkManager().getChunkLoaders().contains(entity)) {
                        log.debug("[Respawn] Completed for {} after {} ticks", player.getOriginName(), progress.elapsedTicks);
                        finish(player, progress);
                    }

                    if (!isCurrent(player, progress)) return false;
                    progress.elapsedTicks++;
                    if (progress.shouldWarn()) {
                        log.warn("[Respawn] Slow recovery: player={}, phase={}, ticks={}, state={}, health={}, "
                                        + "currentDimension={}, targetDimension={}",
                                player.getOriginName(), progress.phase, progress.elapsedTicks,
                                entity.getState(), entity.getHealth(), entity.getDimension(), progress.destination);
                    }
                    if (progress.timedOut()) {
                        log.error("[Respawn] Timed out: player={}, phase={}, ticks={}, state={}, health={}, "
                                        + "currentDimension={}, targetDimension={}, chunkLoaderRegistered={}",
                                player.getOriginName(), progress.phase, progress.elapsedTicks,
                                entity.getState(), entity.getHealth(), entity.getDimension(), progress.destination,
                                progress.destination != null
                                        && progress.destination.getChunkManager().getChunkLoaders().contains(entity));
                        finish(player, progress);
                        player.disconnect("Respawn timed out. Please reconnect.");
                        return false;
                    }
                    return true;
                }
            }, 1);
        } catch (RuntimeException exception) {
            fail(player, progress, exception);
        }
    }

    /** True when a CLIENT_READY transaction is under recovery. */
    public static boolean isRespawnPending(Player player) {
        return RESPAWNING.containsKey(player);
    }

    private static boolean isCurrent(Player player, RespawnProgress progress) {
        return RESPAWNING.get(player) == progress;
    }

    private static void finish(Player player, RespawnProgress progress) {
        progress.advance(Phase.COMPLETE);
        RESPAWNING.remove(player, progress);
    }

    private static void fail(Player player, RespawnProgress progress, RuntimeException exception) {
        if (!isCurrent(player, progress)) return;
        log.error("[Respawn] Failed: player={}, phase={}, entityState={}, dimension={}",
                player.getOriginName(), progress.phase, progress.entity.getState(),
                progress.entity.getDimension(), exception);
        finish(player, progress);
        if (player.isConnected()) {
            player.disconnect("Respawn failed. Please reconnect.");
        }
    }

    private void completeRespawn(Player player, RespawnProgress progress) {
        if (!isCurrent(player, progress)) return;
        var entity = progress.entity;
        try {
            var event = new PlayerRespawnEvent(entity);
            event.setRespawnLocation(findSpawnPoint(entity));
            event.call();

            if (!isCurrent(player, progress) || !player.isConnected()) {
                finish(player, progress);
                return;
            }
            var spawnPoint = event.getRespawnLocation();
            if (spawnPoint == null || spawnPoint.dimension() == null) {
                throw new IllegalStateException("PlayerRespawnEvent provided an invalid spawn point");
            }

            progress.destination = (AllayDimension) spawnPoint.dimension();
            var allayPlayer = (AllayPlayer) player;
            allayPlayer.sendPacket(allayPlayer.getProtocol().getEncoder().encodeRespawn(spawnPoint));

            // Remove the old viewer/chunk state, then register and teleport.
            // Keep the watchdog running until the destination chunk loader exists.
            var source = (AllayDimension) entity.getDimension();
            progress.advance(Phase.REMOVING_PLAYER);
            source.removePlayer(player, () -> {
                if (!isCurrent(player, progress) || !player.isConnected()) return;
                try {
                    if (!entity.canBeSpawned()) {
                        throw new IllegalStateException("Entity not spawnable after removal: state=" + entity.getState());
                    }
                    progress.advance(Phase.REGISTERING_PLAYER);
                    source.addPlayer(player, () -> {
                        if (!isCurrent(player, progress) || !player.isConnected()) return;
                        try {
                            resetData(entity);
                            if (!entity.teleport(spawnPoint)) {
                                throw new IllegalStateException("Respawn teleport was rejected");
                            }
                            progress.advance(Phase.WAITING_FOR_CHUNK_LOADER);
                        } catch (RuntimeException exception) {
                            fail(player, progress, exception);
                        }
                    });
                } catch (RuntimeException exception) {
                    fail(player, progress, exception);
                }
            });
        } catch (RuntimeException exception) {
            fail(player, progress, exception);
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
