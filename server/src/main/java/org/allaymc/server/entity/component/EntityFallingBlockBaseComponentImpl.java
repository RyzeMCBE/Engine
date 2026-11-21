package org.allaymc.server.entity.component;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.block.component.BlockFallableComponent;
import org.allaymc.api.block.data.BlockFace;
import org.allaymc.api.block.data.BlockTags;
import org.allaymc.api.block.type.BlockState;
import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.api.entity.EntityInitInfo;
import org.allaymc.api.entity.component.EntityFallingBlockBaseComponent;
import org.allaymc.api.entity.component.EntityLivingComponent;
import org.allaymc.api.entity.component.EntityPhysicsComponent;
import org.allaymc.api.eventbus.EventHandler;
import org.allaymc.api.registry.Registries;
import org.allaymc.server.component.annotation.Dependency;
import org.allaymc.server.entity.component.event.CEntityFallEvent;
import org.cloudburstmc.nbt.NbtMap;
import org.joml.Vector3d;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;

/**
 * @author IWareQ
 */
@Getter
@Setter
@Slf4j
public class EntityFallingBlockBaseComponentImpl extends EntityBaseComponentImpl implements EntityFallingBlockBaseComponent {

    @Dependency
    protected EntityPhysicsComponent physicsComponent;

    protected BlockState blockState;

    public EntityFallingBlockBaseComponentImpl(EntityInitInfo info) {
        super(info);
    }

    @Override
    public void tick(long currentTick) {
        super.tick(currentTick);
        tickFalling();
    }

    protected void tickFalling() {
        if (this.willBeDespawnedLater()) {
            return;
        }
        // Unknown/legacy NBT hashes can leave the state unresolved. A falling
        // entity without a state cannot turn safely into a block or an item.
        if (blockState == null) {
            log.warn("Discarding falling block with missing block state at {}", getLocation());
            thisEntity.remove();
            return;
        }
        if (physicsComponent.isOnGround()) {
            // The falling block entity already became block
            return;
        }

        var dimension = getDimension();
        var currentBlock = dimension.getBlockState(location);
        if (currentBlock.getBlockType() == BlockTypes.AIR) {
            return;
        }

        if (currentBlock.getBlockType().hasBlockTag(BlockTags.REPLACEABLE)) {
            dimension.setBlockState(location.floor(new Vector3d()), BlockTypes.AIR.getDefaultState());
        }
    }

    @EventHandler
    protected void onFall(CEntityFallEvent event) {
        var dimension = getDimension();
        // Capture the state before queuing the removal callback; it may run later.
        final var landedState = blockState;
        // It is better to place the block when the Entity is removed, so that it looks visually normal.
        dimension.getEntityManager().removeEntity(thisEntity, () -> {
            if (landedState == null) {
                log.warn("Discarded falling block with missing block state on landing at {}", getLocation());
                return;
            }
            if (!(landedState.getBehavior() instanceof BlockFallableComponent fallableComponent)) {
                return;
            }

            var damage = fallableComponent.calculateDamage(event.getFallDistance());
            if (damage > 0) {
                var damageContainer = fallableComponent.createDamageContainer(damage);
                dimension.getEntityManager().getPhysicsService().computeCollidingEntities(getOffsetAABB(), true)
                        .stream()
                        .filter(entity -> entity instanceof EntityLivingComponent)
                        .map(EntityLivingComponent.class::cast)
                        .forEach(entity -> entity.attack(damageContainer));
            }

            if (!physicsComponent.getBlockStateStandingOn().getBlockStateData().collisionShape().isFull(BlockFace.UP)) {
                // Falling on a block which is not full in upper face, for example torch.
                // In this case, the falling block should be turned into item instead of block
                dimension.dropItem(landedState.toItemStack(), location);
            } else {
                fallableComponent.onLanded(location, event.getFallDistance(), landedState);
            }
        });
    }

    @Override
    public NbtMap saveNBT() {
        // A malformed entity must not crash saving the other entities in its chunk.
        // Its incomplete NBT will be safely discarded on the next load/tick.
        final var nbt = super.saveNBT();
        return blockState == null ? nbt : nbt.toBuilder()
                .putInt("BlockStateHash", blockState.blockStateHash())
                .build();
    }

    @Override
    public void loadNBT(NbtMap nbt) {
        super.loadNBT(nbt);
        nbt.listenForInt("BlockStateHash", blockStateHash -> {
            blockState = Registries.BLOCK_STATE_PALETTE.get(blockStateHash);
            if (blockState == null) {
                log.warn("Unrecognized falling block state hash {} at {}", blockStateHash, getLocation());
            }
        });
    }

    @Override
    public AABBdc getBaseAABB() {
        return new AABBd(-0.49, 0, -0.49, 0.49, 0.98, 0.49);
    }

    @Override
    public boolean hasEntityCollision() {
        return false;
    }
}
