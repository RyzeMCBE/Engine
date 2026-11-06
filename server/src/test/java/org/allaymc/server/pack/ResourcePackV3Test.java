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
    void keepsAutomaticSubpackOrderAndPerformanceTiers() {
        var manifest = PackManifest.fromJson(new StringReader(MANIFEST));
        // The server does not choose a subpack; Bedrock uses these
        // declarations to select the best variant for the client's device.
        assertEquals(2, manifest.getSubpacks().size());
        assertEquals("SP0", manifest.getSubpacks().get(0).getFolderName());
        assertEquals("SP2", manifest.getSubpacks().get(1).getFolderName());
        assertEquals(0, manifest.getSubpacks().get(0).getMemoryPerformanceTier());
        assertEquals(1, manifest.getSubpacks().get(1).getMemoryPerformanceTier());
    }

    @Test
    void automaticallySelectsLastEqualTierSubpackWithoutConfig() {
        var json = com.google.gson.JsonParser.parseString(MANIFEST).getAsJsonObject();
        var subpacks = json.getAsJsonArray("subpacks");
        subpacks.get(1).getAsJsonObject().addProperty("memory_performance_tier", 0);
        var manifest = PackManifest.fromJson(new StringReader(json.toString()));

        assertEquals("SP2", AutomaticSubpackSelector.select(manifest),
                "Equal baseline memory tiers should select the last variant");
    }

    @Test
    void doesNotForceHighOrMixedTierSubpacksOnLowerEndClients() {
        var manifest = PackManifest.fromJson(new StringReader(MANIFEST));
        assertEquals("", AutomaticSubpackSelector.select(manifest),
                "Mixed tiers must be delegated to the client");

        var json = com.google.gson.JsonParser.parseString(MANIFEST).getAsJsonObject();
        for (var subpack : json.getAsJsonArray("subpacks")) {
            subpack.getAsJsonObject().addProperty("memory_performance_tier", 5);
        }
        var hdManifest = PackManifest.fromJson(new StringReader(json.toString()));
        assertEquals("", AutomaticSubpackSelector.select(hdManifest),
                "Do not force a high tier onto clients of unknown memory capacity");
    }

    @Test
    void noSubpacksMeansNoSelectedVariant() {
        var json = com.google.gson.JsonParser.parseString(MANIFEST).getAsJsonObject();
        json.add("subpacks", new com.google.gson.JsonArray());
        assertEquals("", AutomaticSubpackSelector.select(
                PackManifest.fromJson(new StringReader(json.toString()))));
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
    void loadsOrdinaryZipPackWithV3Manifest(@TempDir Path directory) throws Exception {
        var zipPath = directory.resolve("server-textures.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            entry(zip, "manifest.json", MANIFEST.getBytes(StandardCharsets.UTF_8));
            entry(zip, "subpacks/SP2/textures/example.txt", new byte[]{1, 2, 3});
        }
        assertTrue(org.allaymc.server.pack.loader.ZipPackLoader.FACTORY.canLoad(zipPath));
        try (var loader = org.allaymc.server.pack.loader.ZipPackLoader.FACTORY.create(zipPath)) {
            var manifest = PackManifest.load(loader);
            assertNotNull(manifest);
            assertTrue(manifest.isValid());
            assertEquals("1.26.40", manifest.getHeader().getMinEngineVersion().getVersion());
            assertTrue(manifest.getCapabilities().contains(PackManifest.Capability.PBR));
            assertEquals("SP2", manifest.getSubpacks().get(1).getFolderName());
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

    private static void entry(ZipOutputStream zos, String path, byte[] bytes) throws Exception {
        zos.putNextEntry(new ZipEntry(path));
        zos.write(bytes);
        zos.closeEntry();
    }
}
