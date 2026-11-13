package dev.uniapp.frame;

import org.json.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Signed discovery metadata. The package still passes independent verification before installation. */
public final class CatalogVerifier {
    public static URI endpoint(String address) throws Exception {
        String input = address.trim();
        if (!input.contains("://")) input = "https://" + input;
        URI uri = new URI(input);
        if (DriveSource.isDrive(uri)) return DriveSource.download(uri);
        BundleVerifier.require("https".equals(uri.getScheme()) && uri.getHost() != null
            && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null, "CATALOG_HTTPS_REQUIRED");
        if (uri.getPath().endsWith("/catalog.json")) return uri;
        BundleVerifier.require(!uri.getPath().endsWith(".json"), "CATALOG_ADDRESS_INVALID");
        return new URI(input + (input.endsWith("/") ? "" : "/") + "catalog.json");
    }
    public static JSONObject verify(byte[] raw, PublicKey key, BundleVerifier.Decoder decoder,
                                    URI endpoint, long now, boolean cached) throws Exception {
        BundleVerifier.require(raw.length <= 1048576, "SIZE_LIMIT");
        JSONObject envelope = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        BundleVerifier.require("RS256".equals(envelope.getString("algorithm")), "SIGNATURE_ALGORITHM");
        byte[] payload = decoder.decode(envelope.getString("payload"));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(key); signature.update(payload);
        BundleVerifier.require(signature.verify(decoder.decode(envelope.getString("signature"))), "CATALOG_SIGNATURE_INVALID");
        JSONObject catalog = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        BundleVerifier.require(BundleVerifier.integer(catalog, "formatVersion") == 1, "FORMAT_UNSUPPORTED");
        if (!cached) BundleVerifier.require(BundleVerifier.integer(catalog, "expiresAt") > now, "CATALOG_EXPIRED");
        text(catalog, "name", 80);
        if (catalog.has("hostUpdateUrl")) {
            URI update = new URI(catalog.getString("hostUpdateUrl"));
            BundleVerifier.require("https".equals(update.getScheme()) && update.getHost() != null
                && update.getUserInfo() == null && update.getFragment() == null, "UPDATE_HTTPS_REQUIRED");
        }
        JSONArray apps = catalog.getJSONArray("apps");
        BundleVerifier.require(apps.length() <= 500, "CATALOG_TOO_LARGE");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.getJSONObject(i);
            BundleVerifier.require(ids.add(BundleVerifier.identifier(app, "projectId")), "DUPLICATE_APP");
            text(app, "name", 80); text(app, "description", 500);
            JSONArray releases = app.getJSONArray("releases");
            BundleVerifier.require(releases.length() > 0 && releases.length() <= 16, "INVALID_RELEASES");
            Set<String> abis = new HashSet<>();
            for (int j = 0; j < releases.length(); j++) {
                JSONObject release = releases.getJSONObject(j);
                String abi = release.getString("abi");
                BundleVerifier.require(Arrays.asList("arm64-v8a", "x86_64").contains(abi) && abis.add(abi), "INVALID_ABI");
                BundleVerifier.require(BundleVerifier.integer(release, "sequence") > 0 &&
                    BundleVerifier.integer(release, "minAndroidApi") >= 24, "INVALID_RELEASE");
                BundleVerifier.require(release.getString("sha256").matches("[0-9a-f]{64}") &&
                    release.getString("runtimeFingerprint").matches("[0-9a-f]{64}"), "INVALID_DIGEST");
                text(release, "version", 80);
                URI reference = new URI(release.getString("url"));
                BundleVerifier.require(!DriveSource.isDrive(endpoint) || reference.isAbsolute(), "DRIVE_ABSOLUTE_PACKAGE_URL_REQUIRED");
                URI url = endpoint.resolve(reference);
                BundleVerifier.require("https".equals(url.getScheme()) && url.getHost() != null &&
                    url.getUserInfo() == null && url.getFragment() == null, "PACKAGE_HTTPS_REQUIRED");
                release.put("url", url.toString());
            }
        }
        return catalog;
    }
    private static void text(JSONObject object, String key, int limit) throws Exception {
        Object value = object.get(key);
        BundleVerifier.require(value instanceof String && !((String) value).trim().isEmpty()
            && ((String) value).length() <= limit, "INVALID_TEXT");
    }
}
