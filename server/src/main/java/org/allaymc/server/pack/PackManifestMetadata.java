package org.allaymc.server.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.pack.Pack;
import org.allaymc.api.pack.PackLoader;
import org.allaymc.api.pack.PackManifest;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Reads optional manifest v3 fields from the original manifest.json.
 *
 * <p>The published engine-api:0.30.0 does not expose min_engine_version or
 * subpacks on PackManifest. Read the source document instead of invoking
 * methods that do not exist in the published SDK. This also preserves these
 * fields when an older PackManifest parser ignores unknown JSON properties.</p>
 */
@Slf4j
public final class PackManifestMetadata {

    private static final ConcurrentMap<String, Metadata> CACHE = new ConcurrentHashMap<>();
    private static final Metadata INVALID = new Metadata(false, null, List.of());

    private PackManifestMetadata() {
    }

    public static Metadata forPack(Pack pack) {
        // Reading the ZIP manifest during every resource-pack chunk request would
        // be expensive. A pack's SHA-256 changes when its contents are replaced.
        var key = pack.getId() + ":" + HexFormat.of().formatHex(pack.getHash());
        return CACHE.computeIfAbsent(key, ignored -> read(pack.getLoader()));
    }

    public static Metadata read(PackLoader loader) {
        try (var stream = loader.getFile(PackManifest.FILE_NAME)) {
            if (stream == null) {
                log.warn("Pack {} is missing {}", loader.getLocation(), PackManifest.FILE_NAME);
                return INVALID;
            }
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return parse(reader);
            }
        } catch (Exception exception) {
            log.warn("Cannot read pack manifest metadata from {}", loader.getLocation(), exception);
            return INVALID;
        }
    }

    static Metadata parse(Reader reader) {
        return parse(JsonParser.parseReader(reader));
    }

    private static Metadata parse(JsonElement root) {
        if (root == null || !root.isJsonObject()) return INVALID;
        var manifest = root.getAsJsonObject();
        var header = object(manifest, "header");
        if (header == null) return INVALID;

        String minimum = null;
        if (header.has("min_engine_version") && !header.get("min_engine_version").isJsonNull()) {
            var version = header.get("min_engine_version");
            try {
                if (version.isJsonPrimitive() && version.getAsJsonPrimitive().isString()) {
                    minimum = version.getAsString();
                } else if (version.isJsonArray() && version.getAsJsonArray().size() == 3) {
                    var parts = version.getAsJsonArray();
                    minimum = parts.get(0).getAsInt() + "." + parts.get(1).getAsInt() + "." + parts.get(2).getAsInt();
                } else {
                    // A malformed minimum must not silently permit older clients.
                    minimum = "";
                }
            } catch (RuntimeException ignored) {
                minimum = "";
            }
        }

        var subpacks = new ArrayList<Subpack>();
        if (manifest.has("subpacks") && manifest.get("subpacks").isJsonArray()) {
            JsonArray entries = manifest.getAsJsonArray("subpacks");
            for (var entry : entries) {
                if (!entry.isJsonObject()) {
                    subpacks.add(new Subpack(null, -1));
                    continue;
                }
                JsonObject subpack = entry.getAsJsonObject();
                try {
                    var folder = subpack.has("folder_name") && !subpack.get("folder_name").isJsonNull()
                            ? subpack.get("folder_name").getAsString() : null;
                    int tier = subpack.has("memory_performance_tier")
                            ? subpack.get("memory_performance_tier").getAsInt() : -1;
                    subpacks.add(new Subpack(folder, tier));
                } catch (RuntimeException ignored) {
                    subpacks.add(new Subpack(null, -1));
                }
            }
        }
        return new Metadata(true, minimum, List.copyOf(subpacks));
    }

    private static JsonObject object(JsonObject source, String member) {
        var value = source.get(member);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    public record Metadata(boolean valid, String minimumClientVersion, List<Subpack> subpacks) {
    }

    public record Subpack(String folderName, int memoryPerformanceTier) {
    }
}
