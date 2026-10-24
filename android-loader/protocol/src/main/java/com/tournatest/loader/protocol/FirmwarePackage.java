package com.tournatest.loader.protocol;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bounded, in-memory reader for application-only legacy Adafruit DFU ZIPs. */
public final class FirmwarePackage {
    private static final int MAX_ZIP_BYTES = 1024 * 1024;
    private static final int MAX_EXPANDED_BYTES = 600 * 1024;
    public static final int MAX_APPLICATION_BYTES = 512 * 1024;
    private final byte[] application;
    private final byte[] initPacket;
    public final String name;
    public final String sha256;

    private FirmwarePackage(String name, byte[] application, byte[] initPacket) {
        this.name = name;
        this.application = application.clone();
        this.initPacket = initPacket.clone();
        this.sha256 = sha256(application);
    }

    public byte[] application() { return application.clone(); }
    public byte[] initPacket() { return initPacket.clone(); }
    public int size() { return application.length; }

    public static FirmwarePackage read(String name, InputStream input) throws IOException {
        if (input == null) throw new IOException("The firmware file could not be opened.");
        byte[] archive = readBounded(input, MAX_ZIP_BYTES);
        Map<String, byte[]> files = new HashMap<>();
        int total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 16) throw new IOException("Too many entries in firmware ZIP.");
                String path = entry.getName();
                if (path.startsWith("/") || path.contains("\\") || path.contains(":")
                        || path.contains("..")) {
                    throw new IOException("Unsafe filename in firmware ZIP.");
                }
                if (entry.isDirectory()) continue;
                if (files.containsKey(path)) throw new IOException("Duplicate filename in firmware ZIP.");
                int limit = path.endsWith(".json") ? 32768
                        : path.endsWith(".dat") ? 1024 : MAX_APPLICATION_BYTES;
                byte[] content = readBounded(zip, Math.min(limit, MAX_EXPANDED_BYTES - total));
                total += content.length;
                files.put(path, content);
                zip.closeEntry(); // Also verifies each ZIP entry's CRC.
            }
        }
        try {
            byte[] manifestBytes = required(files, "manifest.json");
            JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8))
                    .getJSONObject("manifest");
            if (manifest.length() != 2 || !manifest.has("application")
                    || manifest.getDouble("dfu_version") != 0.5) {
                throw new IOException("Select a legacy application-only TournaTest firmware ZIP (DFU 0.5).");
            }
            JSONObject app = manifest.getJSONObject("application");
            byte[] binary = required(files, app.getString("bin_file"));
            byte[] init = required(files, app.getString("dat_file"));
            if (binary.length == 0 || binary.length > MAX_APPLICATION_BYTES || binary.length % 4 != 0) {
                throw new IOException("Firmware application has an invalid size.");
            }
            if (init.length < 14 || init.length > 1024) throw new IOException("Invalid DFU init packet.");
            ByteBuffer data = ByteBuffer.wrap(init).order(ByteOrder.LITTLE_ENDIAN);
            int deviceType = Short.toUnsignedInt(data.getShort());
            int revision = Short.toUnsignedInt(data.getShort());
            long version = Integer.toUnsignedLong(data.getInt());
            int softDevices = Short.toUnsignedInt(data.getShort());
            if (deviceType != 0x0052 || softDevices == 0 || init.length != 12 + softDevices * 2) {
                throw new IOException("This firmware is not a supported nRF52 legacy application.");
            }
            JSONObject info = app.getJSONObject("init_packet_data");
            if (info.getInt("device_type") != deviceType || info.getInt("device_revision") != revision
                    || info.getLong("application_version") != version
                    || info.getJSONArray("softdevice_req").length() != softDevices) {
                throw new IOException("Firmware manifest and init packet do not match.");
            }
            for (int i = 0; i < softDevices; i++) {
                if (Short.toUnsignedInt(data.getShort()) != info.getJSONArray("softdevice_req").getInt(i)) {
                    throw new IOException("Firmware SoftDevice requirements do not match.");
                }
            }
            int crc = Short.toUnsignedInt(data.getShort());
            if (info.getInt("firmware_crc16") != crc || Crc16.calculate(binary) != crc) {
                throw new IOException("Firmware CRC check failed. Download the ZIP again.");
            }
            return new FirmwarePackage(name, binary, init);
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("Invalid firmware manifest or DFU init packet.", e);
        }
    }

    private static byte[] required(Map<String, byte[]> files, String name) throws IOException {
        byte[] value = files.get(name);
        if (value == null) throw new IOException("Firmware ZIP is missing " + name + ".");
        return value;
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (output.size() + n > limit) throw new IOException("Firmware ZIP exceeds the supported size.");
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
