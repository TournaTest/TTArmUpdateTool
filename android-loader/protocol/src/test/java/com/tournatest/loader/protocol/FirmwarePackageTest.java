package com.tournatest.loader.protocol;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class FirmwarePackageTest {
    static final String LATEST = "TTArm-build20260629d.zip";

    static FirmwarePackage realFirmware() throws IOException {
        try (InputStream input = Files.newInputStream(Path.of(System.getProperty("firmwareRoot"), LATEST))) {
            return FirmwarePackage.read(LATEST, input);
        }
    }

    @Test public void allShippedFirmwarePackagesPassValidation() throws Exception {
        int count = 0;
        try (var paths = Files.list(Path.of(System.getProperty("firmwareRoot")))) {
            for (Path file : paths.filter(path -> path.getFileName().toString().matches("TTArm-build.*\\.zip")).toList()) {
                try (InputStream input = Files.newInputStream(file)) {
                    FirmwarePackage firmware = FirmwarePackage.read(file.getFileName().toString(), input);
                    assertTrue(firmware.size() > 100_000);
                    assertEquals(64, firmware.sha256.length());
                    count++;
                }
            }
        }
        assertTrue("Tests must exercise actual distributed firmware", count >= 3);
    }

    @Test public void packageDoesNotExposeMutableFirmware() throws Exception {
        FirmwarePackage firmware = realFirmware();
        byte original = firmware.application()[0];
        firmware.application()[0] ^= 0xff;
        assertEquals(original, firmware.application()[0]);
    }

    @Test public void corruptApplicationFailsEvenWithValidZipCrc() throws Exception {
        Map<String, byte[]> entries = entries();
        entries.get("Feather_TestBox_v0.ino.bin")[100] ^= 1;
        rejected(entries);
    }

    @Test public void secureOrBootloaderPackagesAreRejected() throws Exception {
        Map<String, byte[]> entries = entries();
        JSONObject json = manifest(entries);
        json.getJSONObject("manifest").put("dfu_version", 1.0);
        replaceManifest(entries, json);
        rejected(entries);
        entries = entries();
        json = manifest(entries);
        json.getJSONObject("manifest").put("bootloader", new JSONObject());
        replaceManifest(entries, json);
        rejected(entries);
    }

    @Test public void initAndManifestMustAgreeOnTargetAndCrc() throws Exception {
        for (String key : new String[]{"device_type", "device_revision", "application_version", "firmware_crc16"}) {
            Map<String, byte[]> entries = entries();
            JSONObject json = manifest(entries);
            json.getJSONObject("manifest").getJSONObject("application").getJSONObject("init_packet_data").put(key, 123);
            replaceManifest(entries, json);
            rejected(entries);
        }
    }

    @Test public void truncatedInitOrMissingBinaryFails() throws Exception {
        Map<String, byte[]> entries = entries();
        entries.put("Feather_TestBox_v0.ino.dat", new byte[10]);
        rejected(entries);
        entries = entries();
        entries.remove("Feather_TestBox_v0.ino.bin");
        rejected(entries);
    }

    @Test public void zipTraversalAndExcessiveEntriesFail() throws Exception {
        Map<String, byte[]> entries = entries();
        entries.put("../unexpected.bin", new byte[0]);
        rejected(entries);
        entries = entries();
        for (int i = 0; i < 17; i++) entries.put("extra" + i, new byte[0]);
        rejected(entries);
    }

    @Test public void compressedOversizeApplicationFailsBeforeExpansionCanExhaustMemory() throws Exception {
        Map<String, byte[]> entries = entries();
        entries.put("Feather_TestBox_v0.ino.bin", new byte[FirmwarePackage.MAX_APPLICATION_BYTES + 4]);
        rejected(entries);
    }

    @Test public void randomOrEmptyInputIsRejected() {
        for (byte[] invalid : new byte[][]{new byte[0], new byte[]{1, 2, 3, 4}}) {
            assertThrows(IOException.class, () -> FirmwarePackage.read("bad.zip", new ByteArrayInputStream(invalid)));
        }
    }

    private static void rejected(Map<String, byte[]> entries) throws IOException {
        byte[] archive = zip(entries);
        assertThrows(IOException.class, () -> FirmwarePackage.read("test.zip", new ByteArrayInputStream(archive)));
    }
    private static Map<String, byte[]> entries() throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(Path.of(System.getProperty("firmwareRoot"), LATEST)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) result.put(entry.getName(), zip.readAllBytes());
        }
        return result;
    }
    private static JSONObject manifest(Map<String, byte[]> entries) {
        return new JSONObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
    }
    private static void replaceManifest(Map<String, byte[]> entries, JSONObject value) {
        entries.put("manifest.json", value.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
