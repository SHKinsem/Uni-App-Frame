package dev.uniapp.frame;

import org.json.*;
import org.junit.*;
import java.net.URI;
import java.security.*;
import java.util.Base64;
import static org.junit.Assert.*;

public class CatalogVerifierTest {
    private KeyPair key;
    private JSONObject catalog;
    private final URI endpoint = URI.create("https://apps.example/lab/catalog.json");
    @Before public void setup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key = generator.generateKeyPair();
        JSONObject release = new JSONObject().put("abi", "x86_64").put("sequence", 2).put("version", "v2")
            .put("minAndroidApi", 24).put("runtimeFingerprint", "b".repeat(64)).put("sha256", "a".repeat(64)).put("url", "hello.uaf");
        JSONObject app = new JSONObject().put("projectId", "hello").put("name", "DemoApp").put("description", "BLE controller")
            .put("releases", new JSONArray().put(release));
        catalog = new JSONObject().put("formatVersion", 1).put("name", "My apps").put("expiresAt", 2000)
            .put("apps", new JSONArray().put(app));
    }
    private byte[] envelope(JSONObject value) throws Exception {
        byte[] payload = value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(key.getPrivate()); signature.update(payload);
        return new JSONObject().put("algorithm", "RS256").put("payload", Base64.getEncoder().encodeToString(payload))
            .put("signature", Base64.getEncoder().encodeToString(signature.sign())).toString().getBytes();
    }
    private JSONObject verify(byte[] raw, long now, boolean cached) throws Exception {
        return CatalogVerifier.verify(raw, key.getPublic(), s -> Base64.getDecoder().decode(s), endpoint, now, cached);
    }
    private void reject(String expected, byte[] raw) throws Exception {
        try { verify(raw, 1000, false); fail("Accepted invalid catalog"); }
        catch (java.io.IOException e) { assertEquals(expected, e.getMessage()); }
    }
    @Test public void discoversRootAndSubpath() throws Exception {
        assertEquals("https://example.com/catalog.json", CatalogVerifier.endpoint("example.com").toString());
        assertEquals(endpoint, CatalogVerifier.endpoint("https://apps.example/lab"));
        assertEquals(endpoint, CatalogVerifier.endpoint(endpoint.toString()));
    }
    @Test public void rejectsUnsafeDiscoveryAddress() throws Exception {
        for (String address : new String[]{"http://apps.example", "https://user:pass@apps.example", "https://apps.example?q=secret", "https://apps.example#fragment"}) {
            try { CatalogVerifier.endpoint(address); fail("Accepted unsafe address"); } catch (java.io.IOException expected) {}
        }
    }
    @Test public void resolvesRelativePackageUrl() throws Exception {
        assertEquals("https://apps.example/lab/hello.uaf", verify(envelope(catalog), 1000, false)
            .getJSONArray("apps").getJSONObject(0).getJSONArray("releases").getJSONObject(0).getString("url"));
    }
    @Test public void rejectsTamperedAppNamesAndUrls() throws Exception {
        JSONObject signed = new JSONObject(new String(envelope(catalog)));
        catalog.getJSONArray("apps").getJSONObject(0).put("name", "Imposter");
        signed.put("payload", Base64.getEncoder().encodeToString(catalog.toString().getBytes()));
        reject("CATALOG_SIGNATURE_INVALID", signed.toString().getBytes());
    }
    @Test public void expiredCacheIsDisplayOnly() throws Exception {
        byte[] raw = envelope(catalog); verify(raw, 3000, true);
        try { verify(raw, 3000, false); fail("Accepted expired remote metadata"); }
        catch (java.io.IOException expected) { assertEquals("CATALOG_EXPIRED", expected.getMessage()); }
    }
    @Test public void rejectsDuplicateApps() throws Exception {
        catalog.getJSONArray("apps").put(catalog.getJSONArray("apps").getJSONObject(0));
        reject("DUPLICATE_APP", envelope(catalog));
    }
    @Test public void rejectsInsecurePackageUrl() throws Exception {
        catalog.getJSONArray("apps").getJSONObject(0).getJSONArray("releases").getJSONObject(0).put("url", "http://example.com/hello.uaf");
        reject("PACKAGE_HTTPS_REQUIRED", envelope(catalog));
    }
    @Test public void rejectsInvalidPackageDigest() throws Exception {
        catalog.getJSONArray("apps").getJSONObject(0).getJSONArray("releases").getJSONObject(0).put("sha256", "invalid");
        reject("INVALID_DIGEST", envelope(catalog));
    }
    @Test public void acceptsSignedExplicitHostUpdateLink() throws Exception {
        catalog.put("hostUpdateUrl", "https://drive.google.com/file/d/public_update_id/view");
        assertEquals(catalog.getString("hostUpdateUrl"), verify(envelope(catalog), 1000, false).getString("hostUpdateUrl"));
        catalog.put("hostUpdateUrl", "http://example.com/update");
        reject("UPDATE_HTTPS_REQUIRED", envelope(catalog));
    }
    @Test public void requiresAbsolutePackageLinksForDriveCatalogs() throws Exception {
        URI drive = CatalogVerifier.endpoint("https://drive.google.com/file/d/public_catalog_id/view");
        try {
            CatalogVerifier.verify(envelope(catalog), key.getPublic(), s -> Base64.getDecoder().decode(s), drive, 1000, false);
            fail();
        } catch (java.io.IOException expected) { assertEquals("DRIVE_ABSOLUTE_PACKAGE_URL_REQUIRED", expected.getMessage()); }
    }
}
