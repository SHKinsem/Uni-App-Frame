package dev.uniapp.frame;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** P1 accepts exactly one APK. It never executes, extracts paths, or updates persistent state. */
public final class BundleVerifier {
    public interface Decoder { byte[] decode(String text); }
    public static final long MAX_BUNDLE = 256L * 1024 * 1024;
    public static final String APK = "payload/project.apk";
    public static final class Profile {
        public final int api;
        public final String abi, runtime, engineHash;
        public Profile(int api, String abi, String runtime, String engineHash) {
            this.api = api; this.abi = abi; this.runtime = runtime; this.engineHash = engineHash;
        }
    }
    static void require(boolean condition, String code) throws IOException {
        if (!condition) throw new IOException(code);
    }
    static long integer(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        require(value instanceof Integer || value instanceof Long, "INVALID_INTEGER");
        return ((Number) value).longValue();
    }
    static String identifier(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        require(value instanceof String && ((String) value).matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"), "INVALID_ID");
        return (String) value;
    }
    static byte[] readBounded(InputStream stream, long limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            require((long) output.size() + count <= limit, "SIZE_LIMIT");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    public static String hex(byte[] data) {
        StringBuilder result = new StringBuilder();
        for (byte b : data) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    public static String hash(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) { return hash(input, MAX_BUNDLE); }
    }
    public static String hash(InputStream input, long limit) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32768]; long total = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            total += count; require(total <= limit, "SIZE_LIMIT"); digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }
    public static JSONObject verify(File file, PublicKey key, Decoder decoder, Profile profile,
                                    String project, String channel, long highest, long now,
                                    boolean acceptedOffline) throws Exception {
        require(file.length() > 0 && file.length() <= MAX_BUNDLE, "SIZE_LIMIT");
        try (ZipFile zip = new ZipFile(file)) {
            Set<String> names = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                require(names.add(entry.getName()), "DUPLICATE_ZIP_ENTRY");
                require(names.size() <= 3 && !entry.isDirectory(), "FILE_SET_MISMATCH");
            }
            require(names.equals(new HashSet<>(Arrays.asList("manifest.json", "signature.json", APK))), "FILE_SET_MISMATCH");
            byte[] raw, signatureBytes;
            try (InputStream input = zip.getInputStream(zip.getEntry("manifest.json"))) { raw = readBounded(input, 1048576); }
            try (InputStream input = zip.getInputStream(zip.getEntry("signature.json"))) { signatureBytes = readBounded(input, 16384); }
            JSONObject envelope = new JSONObject(new String(signatureBytes, StandardCharsets.UTF_8));
            require("RS256".equals(envelope.getString("algorithm")), "SIGNATURE_ALGORITHM");
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key); signature.update(raw);
            require(signature.verify(decoder.decode(envelope.getString("signature"))), "SIGNATURE_INVALID");
            JSONObject manifest = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            require(integer(manifest, "formatVersion") == 1, "FORMAT_UNSUPPORTED");
            require(identifier(manifest, "projectId").equals(project) && identifier(manifest, "channel").equals(channel), "RELEASE_SCOPE_MISMATCH");
            identifier(manifest, "buildId");
            require(manifest.getString("sourceCommit").matches("[0-9a-f]{40}|[0-9a-f]{64}"), "INVALID_SOURCE_COMMIT");
            long sequence = integer(manifest, "releaseSequence");
            require(sequence > 0 && highest >= 0, "INVALID_SEQUENCE");
            if (!acceptedOffline) {
                require(sequence > highest, "RELEASE_REPLAY");
                require(integer(manifest, "expiresAt") > now, "METADATA_EXPIRED");
            }
            require(integer(manifest, "minAndroidApi") >= 24 && integer(manifest, "minAndroidApi") <= profile.api, "ANDROID_TOO_OLD");
            require(integer(manifest, "hostApi") == 1, "HOST_API_MISMATCH");
            require(integer(manifest, "dataSchemaVersion") == 1, "DATA_MIGRATION_UNSUPPORTED");
            require(profile.runtime.equals(manifest.getString("runtimeFingerprint")), "RUNTIME_MISMATCH");
            JSONArray abis = manifest.getJSONArray("abis");
            require(abis.length() == 1 && profile.abi.equals(abis.getString(0)), "ABI_MISMATCH");
            Set<String> capabilities = new HashSet<>(Arrays.asList("ble.scan.v1", "ble.connect.v1", "garmin.connectiq.v1", "storage.project.v1"));
            JSONArray required = manifest.getJSONArray("requiredCapabilities");
            for (int i = 0; i < required.length(); i++) require(capabilities.contains(required.getString(i)), "CAPABILITY_MISSING");
            require(manifest.getString("entryActivity").matches("[A-Za-z_$][A-Za-z0-9_.$]{1,255}"), "INVALID_ENTRY");
            JSONArray files = manifest.getJSONArray("files");
            require(files.length() == 1, "FILE_SET_MISMATCH");
            JSONObject apk = files.getJSONObject(0);
            require(APK.equals(apk.getString("path")), "UNSAFE_PATH");
            long size = integer(apk, "size");
            require(size > 0 && size <= MAX_BUNDLE && zip.getEntry(APK).getSize() == size, "SIZE_MISMATCH");
            try (InputStream input = zip.getInputStream(zip.getEntry(APK))) {
                require(hash(input, size).equals(apk.getString("sha256")), "DIGEST_MISMATCH");
            }
            return manifest;
        }
    }
}
