package org.allaymc.server.pack;

import org.allaymc.api.pack.Pack;
import org.allaymc.api.pack.PackManifest;

import java.util.regex.Pattern;

/**
 * Bedrock pack compatibility based on the manifest min_engine_version.
 *
 * <p>The version is the Minecraft client version, not the Bedrock protocol ID.
 * A pack with no minimum remains compatible with all existing clients.</p>
 */
public final class PackCompatibility {

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[.-].*)?$");

    private PackCompatibility() {
    }

    public static boolean supports(Pack pack, String minecraftVersion) {
        return pack != null && supports(pack.getManifest(), minecraftVersion);
    }

    public static boolean supports(PackManifest manifest, String minecraftVersion) {
        if (manifest == null || manifest.getHeader() == null) return false;
        var minimum = manifest.getHeader().getMinEngineVersion();
        if (minimum == null) return true;
        return isAtLeast(minecraftVersion, minimum.getVersion());
    }

    public static boolean isAtLeast(String client, String minimum) {
        int[] clientParts = parse(client);
        int[] minimumParts = parse(minimum);
        if (clientParts == null || minimumParts == null) return false;
        for (int i = 0; i < 3; i++) {
            if (clientParts[i] < minimumParts[i]) return false;
            if (clientParts[i] > minimumParts[i]) return true;
        }
        return true;
    }

    private static int[] parse(String version) {
        if (version == null) return null;
        var matcher = VERSION.matcher(version.trim());
        if (!matcher.matches()) return null;
        try {
            return new int[]{
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
