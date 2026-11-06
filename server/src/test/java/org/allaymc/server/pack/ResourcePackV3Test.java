package org.allaymc.server.pack;

import org.allaymc.api.pack.PackManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic test packs only: no third-party artwork or Marketplace content. */
class ResourcePackV3Test {

    private static final String MANIFEST = """
            {
              "format_version": 3,
              "header": {
                "name": "Test resource pack",
                "description": "Synthetic fixture",
                "uuid": "f90a3675-e2dd-4ac9-b29d-9f32a07bca23",
                "version": "1.1.60",
                "min_engine_version": "1.26.40"
              },
              "modules": [{
                "type": "resources",
                "uuid": "55297988-cfe2-412d-8b2d-d0b2a1a2fbd6",
                "version": "1.1.60"
              }],
              "capabilities": ["pbr", "a_future_capability"],
              "subpacks": [
                {"folder_name": "SP0", "name": "Default", "memory_performance_tier": 0},
                {"folder_name": "SP2", "name": "Full", "memory_performance_tier": 1}
              ],
              "settings": [{"type": "toggle", "name": "example:enabled", "default": true}]
            }
            """;

    @Test
    void parsesV3ManifestPbrSubpacksAndSettings() {
        var manifest = PackManifest.fromJson(new StringReader(MANIFEST));
        assertTrue(manifest.isValid());
        assertEquals("3", manifest.getFormatVersion());
        assertEquals("1.26.40", manifest.getHeader().getMinEngineVersion().getVersion());
        assertTrue(manifest.getCapabilities().contains(PackManifest.Capability.PBR));
        assertTrue(manifest.getCapabilities().contains(PackManifest.Capability.UNKNOWN));
        assertEquals("SP2", manifest.getSubpacks().get(1).getFolderName());
        assertEquals(1, manifest.getSubpacks().get(1).getMemoryPerformanceTier());
        assertEquals(1, manifest.getSettings().size());
    }

    @Test
    void supportsLegacyArrayMinimumVersion() {
        var json = com.google.gson.JsonParser.parseString(MANIFEST).getAsJsonObject();
        var header = json.getAsJsonObject("header");
        var minimum = new com.google.gson.JsonArray();
        minimum.add(1);
        minimum.add(26);
        minimum.add(40);
        header.add("min_engine_version", minimum);
        var manifest = PackManifest.fromJson(new StringReader(json.toString()));
        assertTrue(manifest.isValid());
        assertEquals("1.26.40", manifest.getHeader().getMinEngineVersion().getVersion());
        assertFalse(PackCompatibility.supports(manifest, "1.26.30"));
        assertTrue(PackCompatibility.supports(manifest, "1.26.60"));
    }

    @Test
    void selectsOnlyDeclaredSubpacks() {
        var manifest = PackManifest.fromJson(new StringReader(MANIFEST));
        assertEquals("SP2", PackSubpackSelection.resolve(manifest, "SP2"));
        assertEquals("", PackSubpackSelection.resolve(manifest, "SP9"));
        assertEquals("", PackSubpackSelection.resolve(manifest, ""));
        assertEquals("", PackSubpackSelection.resolve(manifest, null));
    }

    @Test
    void skipsIncompatiblePacksButAllowsOldClientsWithOtherPacks() {
        var manifest = PackManifest.fromJson(new StringReader(MANIFEST));
        assertFalse(PackCompatibility.supports(manifest, "1.26.30"));
        assertTrue(PackCompatibility.supports(manifest, "1.26.40"));
        assertTrue(PackCompatibility.supports(manifest, "1.26.60"));
        assertTrue(PackCompatibility.supports(manifest, "1.26.60.1"));
        assertFalse(PackCompatibility.supports(manifest, "not-a-version"));
        assertTrue(PackCompatibility.isAtLeast("1.27.0", "1.26.40"));
    }

    @Test
    void refusesManifestWithNoModules() {
        // A malformed manifest must be rejected instead of crashing the loader.
        var json = com.google.gson.JsonParser.parseString(MANIFEST).getAsJsonObject();
        json.add("modules", new com.google.gson.JsonArray());
        assertFalse(PackManifest.fromJson(new StringReader(json.toString())).isValid());
    }

    @Test
    void encryptsSubpacksWithoutZipDirectoryEntries(@TempDir Path directory) throws Exception {
        var input = directory.resolve("synthetic.mcpack");
        var output = directory.resolve("encrypted.mcpack");
        byte[] sample = "subpack content".getBytes(StandardCharsets.UTF_8);
        try (var zos = new ZipOutputStream(Files.newOutputStream(input))) {
            entry(zos, "manifest.json", MANIFEST.getBytes(StandardCharsets.UTF_8));
            entry(zos, "textures/base.txt", "resource".getBytes(StandardCharsets.UTF_8));
            entry(zos, "subpacks/SP0/textures/example.txt", sample);
            entry(zos, "subpacks/SP2/textures/example.txt", sample);
        }

        String contentKey = PackEncryptor.encrypt(input, output);
        assertEquals(32, contentKey.length());
        try (var zip = new ZipFile(output.toFile())) {
            assertNotNull(zip.getEntry("manifest.json"));
            assertNotNull(zip.getEntry("contents.json"));
            assertNotNull(zip.getEntry("subpacks/SP0/contents.json"));
            assertNotNull(zip.getEntry("subpacks/SP2/contents.json"));
            assertNotNull(zip.getEntry("subpacks/SP0/textures/example.txt"));
            assertNotNull(zip.getEntry("subpacks/SP2/textures/example.txt"));
            assertArrayEquals(MANIFEST.getBytes(StandardCharsets.UTF_8),
                    zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes());
            assertFalse(java.util.Arrays.equals(sample,
                    zip.getInputStream(zip.getEntry("subpacks/SP2/textures/example.txt")).readAllBytes()));
        }
    }

    private static void entry(ZipOutputStream zos, String path, byte[] bytes) throws Exception {
        zos.putNextEntry(new ZipEntry(path));
        zos.write(bytes);
        zos.closeEntry();
    }
}
