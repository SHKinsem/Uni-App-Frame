package dev.uniapp.frame;

import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;

/** Verifies the update announcement. APK download and installation are delegated to Android. */
public final class HostUpdateVerifier {
    public static URI endpoint(String site) throws Exception {
        return CatalogVerifier.endpoint(site).resolve("host-update.json");
    }
    public static JSONObject verify(byte[] raw, PublicKey key, BundleVerifier.Decoder decoder,
                                    URI endpoint, String packageName, long now) throws Exception {
        BundleVerifier.require(raw.length <= 65536, "SIZE_LIMIT");
        JSONObject envelope = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        BundleVerifier.require("RS256".equals(envelope.getString("algorithm")), "SIGNATURE_ALGORITHM");
        byte[] payload = decoder.decode(envelope.getString("payload"));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(key); signature.update(payload);
        BundleVerifier.require(signature.verify(decoder.decode(envelope.getString("signature"))), "UPDATE_SIGNATURE_INVALID");
        JSONObject update = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        BundleVerifier.require(BundleVerifier.integer(update, "formatVersion") == 1, "FORMAT_UNSUPPORTED");
        BundleVerifier.require("android-host-update".equals(update.getString("type")), "UPDATE_SCOPE_MISMATCH");
        BundleVerifier.require(packageName.equals(update.getString("packageName")), "UPDATE_PACKAGE_MISMATCH");
        BundleVerifier.require(BundleVerifier.integer(update, "expiresAt") > now, "UPDATE_EXPIRED");
        BundleVerifier.require(BundleVerifier.integer(update, "versionCode") > 0
            && BundleVerifier.integer(update, "versionCode") <= Integer.MAX_VALUE, "INVALID_VERSION");
        BundleVerifier.require(BundleVerifier.integer(update, "minAndroidApi") >= 24
            && BundleVerifier.integer(update, "minAndroidApi") <= 10000, "INVALID_ANDROID_API");
        String version = update.getString("versionName"), notes = update.getString("notes");
        BundleVerifier.require(!version.trim().isEmpty() && version.length() <= 80 && notes.length() <= 4000, "INVALID_TEXT");
        BundleVerifier.require(update.getString("apkSha256").matches("[0-9a-f]{64}"), "INVALID_DIGEST");
        URI url = endpoint.resolve(update.getString("apkUrl"));
        BundleVerifier.require("https".equals(url.getScheme()) && url.getHost() != null
            && url.getUserInfo() == null && url.getFragment() == null
            && (url.getPath().endsWith(".apk") || DriveSource.isDrive(url)), "UPDATE_HTTPS_REQUIRED");
        if (DriveSource.isDrive(url)) DriveSource.download(url); // Validate the browser link, keep its readable form.
        update.put("apkUrl", url.toString());
        return update;
    }
    public static boolean newer(JSONObject update, long installed, int api) throws Exception {
        return BundleVerifier.integer(update, "versionCode") > installed
            && BundleVerifier.integer(update, "minAndroidApi") <= api;
    }
}
