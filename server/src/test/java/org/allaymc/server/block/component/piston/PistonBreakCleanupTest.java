package org.allaymc.server.block.component.piston;

import org.allaymc.api.block.data.BlockFace;
import org.allaymc.api.block.dto.Block;
import org.allaymc.api.block.dto.PlayerInteractInfo;
import org.allaymc.api.block.type.BlockState;
import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.api.blockentity.BlockEntity;
import org.allaymc.api.blockentity.interfaces.BlockEntityMovingBlock;
import org.allaymc.api.blockentity.interfaces.BlockEntityPistonArm;
import org.allaymc.api.entity.Entity;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.math.position.Position3i;
import org.allaymc.api.world.Dimension;
import org.allaymc.testutils.AllayTestExtension;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.allaymc.api.block.property.type.BlockPropertyTypes.FACING_DIRECTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(AllayTestExtension.class)
class PistonBreakCleanupTest {
    private static final Vector3i BASE = new Vector3i(0, 64, 0);
    private static final Vector3i HEAD = new Vector3i(1, 64, 0);
    private static final Vector3i FRONT = new Vector3i(2, 64, 0);

    @Test
    void stickyPistonHeadIsClearedEvenWhenClaimRejectsASeparateBreak() {
        var world = new TestWorld();
        world.set(BASE, pistonState(true));
        world.set(HEAD, armState(true));
        // Simulate HCF rejecting a BlockBreakEvent without a player.
        var piston = new SilentStickyPiston();
        piston.onBreak(world.block(BASE), null, mock(Entity.class));
        assertEquals(BlockTypes.AIR, world.state(HEAD).getBlockType(),
                "The head must not survive the base even when a synthetic break is cancelled");
        assertEquals(BlockTypes.AIR, world.state(FRONT).getBlockType(),
                "Extending into air must not leave a moving block in front of the head");
    }

    @Test
    void normalPistonHeadIsClearedEvenWhenClaimRejectsASeparateBreak() {
        var world = new TestWorld();
        world.set(BASE, pistonState(false));
        world.set(HEAD, armState(false));
        new SilentNormalPiston().onBreak(world.block(BASE), null, mock(Entity.class));
        assertEquals(BlockTypes.AIR, world.state(HEAD).getBlockType());
        assertEquals(BlockTypes.AIR, world.state(FRONT).getBlockType());
    }

    @Test
    void pistonBrokenWithoutPlayerStillCleansItsHead() {
        var world = new TestWorld();
        world.set(BASE, pistonState(true));
        world.set(HEAD, armState(true));
        new SilentStickyPiston().onBreak(world.block(BASE), null, null);
        assertEquals(BlockTypes.AIR, world.state(HEAD).getBlockType(),
                "Programmatic removal must not leave an orphan piston head");
    }

    @Test
    void breakingDuringExtensionFinishesTheMovingBlockInsteadOfLeavingAGhost() {
        var world = new TestWorld();
        world.set(BASE, pistonState(true));
        world.set(HEAD, armState(true));
        world.set(FRONT, BlockTypes.MOVING_BLOCK.getDefaultState());
        var arm = mock(BlockEntityPistonArm.class);
        when(arm.isFinished()).thenReturn(false);
        when(arm.isExtending()).thenReturn(true);
        when(arm.getAttachedBlocks()).thenReturn(List.of(HEAD));
        world.tiles.put(BASE, arm);
        world.movingBlock(FRONT, BlockTypes.STONE.getDefaultState(), BASE);

        new SilentStickyPiston().onBreak(world.block(BASE), null, mock(Entity.class));

        assertEquals(BlockTypes.AIR, world.state(HEAD).getBlockType());
        assertEquals(BlockTypes.STONE, world.state(FRONT).getBlockType(),
                "The pushed block must not remain a MOVING_BLOCK after its piston is removed");
    }

    @Test
    void breakingDuringStickyRetractionPreservesPulledBlockAtHeadPosition() {
        var world = new TestWorld();
        world.set(BASE, pistonState(true));
        world.set(HEAD, BlockTypes.MOVING_BLOCK.getDefaultState());
        var arm = mock(BlockEntityPistonArm.class);
        when(arm.isFinished()).thenReturn(false);
        when(arm.isExtending()).thenReturn(false);
        when(arm.getAttachedBlocks()).thenReturn(List.of(FRONT));
        world.tiles.put(BASE, arm);
        world.movingBlock(HEAD, BlockTypes.STONE.getDefaultState(), BASE);

        new SilentStickyPiston().onBreak(world.block(BASE), null, mock(Entity.class));

        assertEquals(BlockTypes.STONE, world.state(HEAD).getBlockType(),
                "Finishing retraction must retain the pulled block rather than erase it");
        assertEquals(BlockTypes.AIR, world.state(FRONT).getBlockType());
    }

    @Test
    void breakingPistonHeadPassesOriginalPlayerToBaseBreakProtection() {
        var world = new TestWorld();
        world.set(BASE, pistonState(true));
        world.set(HEAD, armState(true));
        var player = mock(Entity.class);

        new SilentArm().onBreak(world.block(HEAD), null, player);

        verify(world.dimension).breakBlock(eq(BASE), isNull(), same(player));
    }

    private static BlockState pistonState(boolean sticky) {
        return (sticky ? BlockTypes.STICKY_PISTON : BlockTypes.PISTON).getDefaultState()
                .setPropertyValue(FACING_DIRECTION, BlockFace.WEST.ordinal());
    }

    private static BlockState armState(boolean sticky) {
        return (sticky ? BlockTypes.STICKY_PISTON_ARM_COLLISION : BlockTypes.PISTON_ARM_COLLISION)
                .getDefaultState().setPropertyValue(FACING_DIRECTION, BlockFace.WEST.ordinal());
    }

    private static final class SilentStickyPiston extends BlockStickyPistonBaseComponentImpl {
        SilentStickyPiston() {
            super(BlockTypes.STICKY_PISTON);
        }

        @Override
        public boolean isDroppable(Block block, ItemStack usedItem, Entity entity) {
            return false;
        }
    }

    private static final class SilentNormalPiston extends BlockPistonBaseComponentImpl {
        SilentNormalPiston() {
            super(BlockTypes.PISTON);
        }

        @Override
        public boolean isDroppable(Block block, ItemStack usedItem, Entity entity) {
            return false;
        }
    }

    private static final class SilentArm extends BlockPistonArmCollisionBaseComponentImpl {
        SilentArm() {
            super(BlockTypes.STICKY_PISTON_ARM_COLLISION);
        }

        @Override
        public boolean isDroppable(Block block, ItemStack usedItem, Entity entity) {
            return false;
        }
    }

    private static final class TestWorld {
        final Map<Vector3i, BlockState> blocks = new HashMap<>();
        final Map<Vector3i, BlockEntity> tiles = new HashMap<>();
        final Dimension dimension = mock(Dimension.class, CALLS_REAL_METHODS);

        TestWorld() {
            doAnswer(call -> state(new Vector3i(call.<Integer>getArgument(0), call.<Integer>getArgument(1), call.<Integer>getArgument(2))))
                    .when(dimension).getBlockState(anyInt(), anyInt(), anyInt(), anyInt());
            doAnswer(call -> {
                var pos = new Vector3i(call.<Integer>getArgument(0), call.<Integer>getArgument(1), call.<Integer>getArgument(2));
                set(pos, call.getArgument(3));
                return true;
            }).when(dimension).setBlockState(anyInt(), anyInt(), anyInt(), any(BlockState.class),
                    anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), nullable(PlayerInteractInfo.class));
            doAnswer(call -> tiles.get(new Vector3i(call.<Integer>getArgument(0), call.<Integer>getArgument(1), call.<Integer>getArgument(2))))
                    .when(dimension).getBlockEntity(anyInt(), anyInt(), anyInt());
        }

        void set(Vector3i pos, BlockState state) {
            blocks.put(new Vector3i(pos), state);
            if (state.getBlockType() != BlockTypes.MOVING_BLOCK) {
                tiles.remove(pos);
            }
        }

        BlockState state(Vector3i pos) {
            return blocks.getOrDefault(pos, BlockTypes.AIR.getDefaultState());
        }

        Block block(Vector3i pos) {
            return new Block(state(pos), new Position3i(pos.x(), pos.y(), pos.z(), dimension));
        }

        void movingBlock(Vector3i pos, BlockState original, Vector3i pistonPos) {
            var tile = mock(BlockEntityMovingBlock.class);
            when(tile.getMovingBlockState()).thenReturn(original);
            when(tile.getPistonPos()).thenReturn(pistonPos);
            tiles.put(pos, tile);
        }
    }
}
