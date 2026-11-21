package org.allaymc.server.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.allaymc.api.pack.PackLoader;
import org.allaymc.api.pack.PackManifest;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Parses Bedrock pack manifests while remaining compatible with the published
 * Engine API, whose Capability enum predates some newer Bedrock v3 values.
 *
 * <p>Only the in-memory copy supplied to the API parser is adjusted. The
 * original manifest.json is never rewritten: the client still downloads the
 * unmodified manifest with its full capabilities, subpacks and settings.</p>
 */
@Slf4j
public final class PackManifestCompatibility {

    private PackManifestCompatibility() {
    }

    public static PackManifest load(PackLoader loader) {
        if (loader == null || !loader.hasFile(PackManifest.FILE_NAME)) {
            return null;
        }
        try (var stream = loader.getFile(PackManifest.FILE_NAME)) {
            if (stream == null) return null;
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return fromJson(reader);
            }
        } catch (Exception exception) {
            log.error("Unable to parse pack manifest from {}", loader.getLocation(), exception);
            return null;
        }
    }

    public static PackManifest fromJson(Reader reader) {
        var original = JsonParser.parseReader(reader);
        if (!original.isJsonObject()) {
            throw new IllegalArgumentException("Pack manifest must be a JSON object");
        }

        // Older published API versions throw for unknown capabilities instead
        // of retaining UNKNOWN. Drop just the unrecognised values in the copy
        // used for server-side validation; leave the source ZIP untouched.
        var compatible = original.getAsJsonObject().deepCopy();
        if (compatible.has("capabilities") && compatible.get("capabilities").isJsonArray()) {
            var recognized = new JsonArray();
            for (var element : compatible.getAsJsonArray("capabilities")) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    continue;
                }
                try {
                    PackManifest.Capability.valueOf(element.getAsString().toUpperCase(Locale.ROOT));
                    recognized.add(element);
                } catch (IllegalArgumentException ignored) {
                    // Future/client-side capabilities are not needed for server validation.
                }
            }
            compatible.add("capabilities", recognized);
        }
        return PackManifest.fromJson(new StringReader(compatible.toString()));
    }
}
