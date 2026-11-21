package org.allaymc.server.pack;

import org.allaymc.api.pack.Pack;

/**
 * Selects a universally compatible Bedrock subpack without server configuration.
 *
 * <p>The network protocol accepts a concrete subpack name. For packs whose
 * declared alternatives all use the baseline memory tier (0 or 1), every
 * client can use the last entry, matching Bedrock's documented tie behavior.
 * For heterogeneous or higher tiers we leave the name empty so the client
 * can make a platform-specific choice instead of forcing HD assets on a
 * lower-memory device.</p>
 */
public final class AutomaticSubpackSelector {

    private AutomaticSubpackSelector() {
    }

    public static String select(Pack pack) {
        return select(PackManifestMetadata.forPack(pack));
    }

    static String select(PackManifestMetadata.Metadata metadata) {
        if (metadata == null || !metadata.valid() || metadata.subpacks().isEmpty()) {
            return "";
        }
        var subpacks = metadata.subpacks();
        var first = subpacks.getFirst();
        if (first == null) return "";

        int baselineTier = first.memoryPerformanceTier();
        if (baselineTier < 0 || baselineTier > 1) {
            return "";
        }

        String selected = "";
        for (var subpack : subpacks) {
            if (subpack == null || subpack.memoryPerformanceTier() != baselineTier
                    || subpack.folderName() == null || subpack.folderName().isBlank()) {
                return "";
            }
            // The last declared subpack wins when memory tiers are equal.
            selected = subpack.folderName();
        }
        return selected;
    }
}
