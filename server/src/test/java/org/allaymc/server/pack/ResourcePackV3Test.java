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
        var manifest = PackManifestCompatibility.fromJson(new StringReader(MANIFEST));
        assertTrue(manifest.isValid());
        assertEquals("3", manifest.getFormatVersion());
        var metadata = metadata(MANIFEST);
        assertEquals("1.26.40", metadata.minimumClientVersion());
        assertEquals("SP2", metadata.subpacks().get(1).folderName());
        assertEquals(1, metadata.subpacks().get(1).memoryPerformanceTier());
        var json = com.google.gson.JsonParser.parseString(MANIFEST).getAsJsonObject();
        assertEquals("pbr", json.getAsJsonArray("capabilities").get(0).getAsString());
        assertEquals("a_future_capability", json.getAsJsonArray("capabilities").get(1).getAsString());
        assertEquals(1, json.getAsJsonArray("settings").size());
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
        var metadata = metadata(json.toString());
        assertEquals("1.26.40", metadata.minimumClientVersion());
        assertFalse(PackCompatibility.supports(metadata, "1.26.30"));
        assertTrue(PackCompatibility.supports(metadata, "1.26.60"));
    }

    @Test
    void keepsAutomaticSubpackOrderAndPerformanceTiers() {
        var subpacks = metadata(MANIFEST).subpacks();
        // The server does not choose a subpack; Bedrock uses these
        // declarations to select the best variant for the client's device.
        assertEquals(2, subpacks.size());
        assertEquals("SP0", subpacks.get(0).folderName());
        assertEquals("SP2", subpacks.get(1).folderName());
        assertEquals(0, subpacks.get(0).memoryPerformanceTier());
        assertEquals(1, subpacks.get(1).memoryPerformanceTier());
    }

    @Test
    void automaticallySelectsLastEqualTierSubpackWithoutConfig() {
        var json = com.google.gson.JsonParser.parseString(MANIFEST).getAsJsonObject();
        var subpacks = json.getAsJsonArray("subpacks");
        subpacks.get(1).getAsJsonObject().addProperty("memory_performance_tier", 0);
        assertEquals("SP2", AutomaticSubpackSelector.select(metadata(json.toString())),
                "Equal baseline memory tiers should select the last variant");
    }

    @Test
    void doesNotForceHighOrMixedTierSubpacksOnLowerEndClients() {
        assertEquals("", AutomaticSubpackSelector.select(metadata(MANIFEST)),
                "Mixed tiers must be delegated to the client");

        var json = com.google.gson.JsonParser.parseString(MANIFEST).getAsJsonObject();
        for (var subpack : json.getAsJsonArray("subpacks")) {
            subpack.getAsJsonObject().addProperty("memory_performance_tier", 5);
        }
        assertEquals("", AutomaticSubpackSelector.select(metadata(json.toString())),
                "Do not force a high tier onto clients of unknown memory capacity");
    }

    @Test
    void noSubpacksMeansNoSelectedVariant() {
        var json = com.google.gson.JsonParser.parseString(MANIFEST).getAsJsonObject();
        json.add("subpacks", new com.google.gson.JsonArray());
        assertEquals("", AutomaticSubpackSelector.select(
                metadata(json.toString())));
    }

    @Test
    void skipsIncompatiblePacksButAllowsOldClientsWithOtherPacks() {
        var metadata = metadata(MANIFEST);
        assertFalse(PackCompatibility.supports(metadata, "1.26.30"));
        assertTrue(PackCompatibility.supports(metadata, "1.26.40"));
        assertTrue(PackCompatibility.supports(metadata, "1.26.60"));
        assertTrue(PackCompatibility.supports(metadata, "1.26.60.1"));
        assertFalse(PackCompatibility.supports(metadata, "not-a-version"));
        assertTrue(PackCompatibility.isAtLeast("1.27.0", "1.26.40"));
    }

    @Test
    void refusesManifestWithNoModules() {
        // A malformed manifest must be rejected instead of crashing the loader.
        var json = com.google.gson.JsonParser.parseString(MANIFEST).getAsJsonObject();
        json.add("modules", new com.google.gson.JsonArray());
        assertFalse(PackManifestCompatibility.isValid(
                PackManifestCompatibility.fromJson(new StringReader(json.toString()))));
    }

    @Test
    void loadsOrdinaryZipPackWithV3Manifest(@TempDir Path directory) throws Exception {
        var zipPath = directory.resolve("server-textures.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            entry(zip, "manifest.json", MANIFEST.getBytes(StandardCharsets.UTF_8));
            entry(zip, "subpacks/SP2/textures/example.txt", new byte[]{1, 2, 3});
        }
        assertTrue(org.allaymc.server.pack.loader.ZipPackLoader.FACTORY.canLoad(zipPath));
        try (var loader = org.allaymc.server.pack.loader.ZipPackLoader.FACTORY.create(zipPath)) {
            var manifest = PackManifestCompatibility.load(loader);
            assertNotNull(manifest);
            assertTrue(manifest.isValid());
            var metadata = PackManifestMetadata.read(loader);
            assertEquals("1.26.40", metadata.minimumClientVersion());
            assertEquals("SP2", metadata.subpacks().get(1).folderName());
        }
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

    private static PackManifestMetadata.Metadata metadata(String json) {
        return PackManifestMetadata.parse(new StringReader(json));
    }

    private static void entry(ZipOutputStream zos, String path, byte[] bytes) throws Exception {
        zos.putNextEntry(new ZipEntry(path));
        zos.write(bytes);
        zos.closeEntry();
    }
}
