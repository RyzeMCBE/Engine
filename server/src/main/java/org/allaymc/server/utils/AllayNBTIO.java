package org.allaymc.server.utils;

import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.block.property.type.BlockPropertyType;
import org.allaymc.api.block.type.BlockState;
import org.allaymc.api.block.type.BlockTypes;
import org.allaymc.api.blockentity.BlockEntity;
import org.allaymc.api.blockentity.BlockEntityInitInfo;
import org.allaymc.api.blockentity.type.BlockEntityTypes;
import org.allaymc.api.entity.Entity;
import org.allaymc.api.entity.EntityInitInfo;
import org.allaymc.api.item.ItemStack;
import org.allaymc.api.item.ItemStackInitInfo;
import org.allaymc.api.item.interfaces.ItemAirStack;
import org.allaymc.api.registry.Registries;
import org.allaymc.api.utils.NBTIO;
import org.allaymc.api.utils.identifier.Identifier;
import org.allaymc.api.world.Dimension;
import org.allaymc.server.network.ProtocolInfo;
import org.allaymc.updater.block.BlockStateUpdaters;
import org.allaymc.updater.item.ItemStateUpdaters;
import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * @author daoge_cmd
 */
@Slf4j
public class AllayNBTIO implements NBTIO {
    private final Map<String, LongAdder> unsupportedBlockEntities = new ConcurrentHashMap<>();

    /** Reports preserved unsupported tiles by type after the worlds have finished closing. */
    public void reportUnsupportedBlockEntities() {
        unsupportedBlockEntities.forEach((type, count) ->
                log.info("Preserved {} unsupported block entities of type {}", count.sum(), type));
    }

    Map<String, Long> unsupportedBlockEntityCounts() {
        var snapshot = new java.util.LinkedHashMap<String, Long>();
        unsupportedBlockEntities.forEach((type, count) -> snapshot.put(type, count.sum()));
        return Map.copyOf(snapshot);
    }
    @Override
    public BlockState fromBlockStateNBT(NbtMap nbt) {
        // Older states must be migrated. Newer states are still worth resolving:
        // if their name/properties are already known, preserving compatibility is safe.
        var version = nbt.getInt("version", 0);
        var storedName = nbt.getString("name");
        // A legacy identifier is authoritative even when an imported palette
        // carries a recent version tag. Modern, valid IDs keep their normal path.
        if (version < ProtocolInfo.BLOCK_STATE_VERSION_NUM ||
                storedName.equals("minecraft:log") || storedName.equals("minecraft:log2")) {
            nbt = BlockStateUpdaters.updateBlockState(nbt, ProtocolInfo.BLOCK_STATE_UPDATER.getVersion());
        }
        nbt = normalizeImportedLogDirection(nbt);

        // Get the block type. Malformed or future identifiers are treated like unknown blocks.
        var name = nbt.getString("name");
        final var blockType = getBlockType(name);
        if (blockType == null) {
            log.warn("Unknown block type {}", name);
            return BlockTypes.UNKNOWN.getDefaultState();
        }

        // Add missing properties
        var states = nbt.getCompound("states");
        var builder = states.toBuilder();
        for (var entry : blockType.getDefaultState().getPropertyValues().entrySet()) {
            if (builder.containsKey(entry.getKey().getName())) {
                continue;
            }

            builder.put(entry.getKey().getName(), entry.getValue().getSerializedValue());
        }
        states = builder.build();

        // Create the block property value list
        var blockPropertyValues = new ArrayList<BlockPropertyType.BlockPropertyValue<?, ?, ?>>();
        for (var entry : states.entrySet()) {
            var property = blockType.getProperties().get(entry.getKey());
            if (property == null) {
                log.warn("Unknown property {} for block {}", entry.getKey(), name);
                return BlockTypes.UNKNOWN.getDefaultState();
            }

            try {
                var value = property.tryCreateValue(normalizeLegacySlabHalf(name, entry.getKey(), entry.getValue()));
                if (value == null) {
                    log.warn("Invalid value {} for block property {} on {}", entry.getValue(), entry.getKey(), name);
                    return BlockTypes.UNKNOWN.getDefaultState();
                }
                blockPropertyValues.add(value);
            } catch (IllegalArgumentException | ClassCastException exception) {
                log.warn("Invalid value {} for block property {} on {}", entry.getValue(), entry.getKey(), name);
                return BlockTypes.UNKNOWN.getDefaultState();
            }
        }

        // Get the block state
        final BlockState blockState;
        try {
            blockState = blockType.ofState(blockPropertyValues);
        } catch (IllegalArgumentException exception) {
            log.warn("Invalid block state {}", nbt);
            return BlockTypes.UNKNOWN.getDefaultState();
        }
        if (blockState == null) {
            log.warn("Invalid block state {}", nbt);
            return BlockTypes.UNKNOWN.getDefaultState();
        }

        return blockState;
    }

    @Override
    public ItemStack fromItemStackNBT(NbtMap nbt) {
        try {
            nbt = ItemStateUpdaters.updateItemState(nbt, ProtocolInfo.ITEM_STATE_UPDATER.getVersion());
            int count = nbt.getByte("Count", (byte) 1);
            int meta = nbt.getShort("Damage");
            var name = nbt.getString("Name");
            var itemType = Objects.requireNonNull(Registries.ITEMS.get(new Identifier(name)), "Unknown item type " + name + " while loading container items!");
            return itemType.createItemStack(
                    ItemStackInitInfo
                            .builder()
                            .count(count)
                            .meta(meta)
                            .extraTag(nbt.getCompound("tag"))
                            .build()
            );
        } catch (Throwable t) {
            log.error("Failed to load item from NBT", t);
            return ItemAirStack.AIR_STACK;
        }
    }

    @Override
    public Entity fromEntityNBT(Dimension dimension, NbtMap nbt) {
        String storedIdentifier = nbt.getString("identifier");
        // Bedrock LevelDB's legacy entity records often have an integer `id`
        // and a definitions list but no string identifier at all.
        // Recover only an unambiguous, registered vanilla entity.
        if (storedIdentifier.isBlank() || storedIdentifier.equals("minecraft:")) {
            var recovered = recoverLegacyEntityIdentifier(nbt);
            if (recovered == null) {
                log.warn("Entity has no usable identifier or unambiguous vanilla definition");
                return null;
            }
            storedIdentifier = recovered.toString();
            nbt = nbt.toBuilder().putString("identifier", storedIdentifier).build();
        }

        final Identifier identifier;
        try {
            identifier = new Identifier(storedIdentifier);
        } catch (RuntimeException exception) {
            log.warn("Malformed entity identifier {}", storedIdentifier);
            return null;
        }

        var entityType = Registries.ENTITIES.get(identifier);
        if (entityType == null) {
            log.warn("Unknown entity type {}", identifier);
            return null;
        }

        return entityType.createEntity(EntityInitInfo.builder().dimension(dimension).nbt(nbt).build());
    }

    /**
     * Normalize old log axes in imported mixed-version palettes. This is also
     * needed when Engine still uses a released StateUpdater predating the same
     * compatibility fix. Never discard an unknown or conflicting orientation:
     * ChunkSectionCodec must be able to preserve that state's original NBT.
     */
    private static NbtMap normalizeImportedLogDirection(NbtMap nbt) {
        String name = nbt.getString("name");
        if (!isOldDirectionalLog(name)) return nbt;
        var states = nbt.getCompound("states");
        Object direction = states.get("direction");
        if (direction == null) return nbt;

        String axis = null;
        if (direction instanceof Number number) {
            int value = number.intValue();
            if (number.doubleValue() == value) {
                axis = switch (value) {
                    case 0, 3 -> "y";
                    case 1 -> "x";
                    case 2 -> "z";
                    default -> null;
                };
            }
        } else if (direction instanceof String value) {
            axis = switch (value) {
                case "0", "3", "y" -> "y";
                case "1", "x" -> "x";
                case "2", "z" -> "z";
                default -> null;
            };
        }
        if (axis == null) return nbt;

        Object existingAxis = states.get("pillar_axis");
        if (existingAxis != null && !axis.equals(existingAxis)) return nbt;

        var updatedStates = states.toBuilder();
        updatedStates.remove("direction");
        updatedStates.putString("pillar_axis", axis);
        boolean bark = direction instanceof Number number && number.doubleValue() == 3 ||
                direction instanceof String value && value.equals("3");
        if (bark && name.endsWith("_log")) {
            name = name.substring(0, name.length() - "_log".length()) + "_wood";
        }
        return nbt.toBuilder().putString("name", name).putCompound("states", updatedStates.build()).build();
    }

    private static boolean isOldDirectionalLog(String name) {
        if (!name.startsWith("minecraft:")) return false;
        String path = name.substring("minecraft:".length());
        if (path.startsWith("stripped_")) path = path.substring("stripped_".length());
        if (!path.endsWith("_log") && !path.endsWith("_wood")) return false;
        String wood = path.substring(0, path.lastIndexOf('_'));
        return switch (wood) {
            case "oak", "spruce", "birch", "jungle", "acacia", "dark_oak" -> true;
            default -> false;
        };
    }

    private static Identifier recoverLegacyEntityIdentifier(NbtMap nbt) {
        if (!(nbt.get("definitions") instanceof java.util.List<?> definitions)) return null;
        Identifier found = null;
        for (Object entry : definitions) {
            if (!(entry instanceof String definition) || !definition.startsWith("+minecraft:")) continue;
            Identifier candidate;
            try {
                candidate = new Identifier(definition.substring(1));
            } catch (RuntimeException exception) {
                continue;
            }
            if (candidate.path().isEmpty() || Registries.ENTITIES.get(candidate) == null) continue;
            if (found != null && !found.equals(candidate)) return null;
            found = candidate;
        }
        return found;
    }

    /**
     * Some imported worlds store the post-1.20.20 vertical half enum as a
     * numeric NBT tag even when their palette version is already up to date.
     * Limit this compatibility path to vanilla slabs; all other values must
     * still be rejected and preserved by the chunk codec if unrecognized.
     */
    private static Object normalizeLegacySlabHalf(String blockName, String propertyName, Object value) {
        if (!blockName.startsWith("minecraft:") || !blockName.endsWith("_slab") ||
                !"minecraft:vertical_half".equals(propertyName)) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short ||
                value instanceof Integer || value instanceof Long) {
            long half = ((Number) value).longValue();
            if (half == 0) return "bottom";
            if (half == 1) return "top";
        }
        return value;
    }

    private org.allaymc.api.block.type.BlockType<?> getBlockType(String name) {
        try {
            return Registries.BLOCKS.get(new Identifier(name));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    @Override
    public BlockEntity fromBlockEntityNBT(Dimension dimension, NbtMap nbt) {
        var id = nbt.getString("id");
        var blockEntityType = Registries.BLOCK_ENTITIES.get(id);
        if (blockEntityType == null) {
            // Preserve the imported tile NBT rather than deleting it on save.
            // This is storage compatibility, not support for its gameplay behavior.
            unsupportedBlockEntities.computeIfAbsent(id, type -> {
                log.warn("Preserving unsupported block entity type: {} (further entries will be summarized)", type);
                return new LongAdder();
            }).increment();
            blockEntityType = BlockEntityTypes.UNKNOWN;
        }

        return blockEntityType.createBlockEntity(BlockEntityInitInfo.builder().dimension(dimension).nbt(nbt).build());
    }
}
