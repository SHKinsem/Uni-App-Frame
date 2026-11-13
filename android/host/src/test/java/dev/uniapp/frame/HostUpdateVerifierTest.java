package dev.uniapp.frame;

import org.json.*;
import org.junit.*;
import java.net.URI;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public class HostUpdateVerifierTest {
    private KeyPair key;
    private JSONObject update;
    @Before public void setup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key = generator.generateKeyPair();
        update = new JSONObject().put("formatVersion", 1).put("type", "android-host-update").put("packageName", "dev.uniapp.frame")
            .put("versionCode", 4).put("versionName", "0.4.0").put("minAndroidApi", 24).put("expiresAt", 2000)
            .put("notes", "Update notes").put("apkUrl", "uni-frame-4.apk").put("apkSha256", "a".repeat(64));
    }
    private byte[] envelope() throws Exception {
        byte[] raw = update.toString().getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(key.getPrivate()); signature.update(raw);
        return new JSONObject().put("algorithm", "RS256").put("payload", Base64.getEncoder().encodeToString(raw))
            .put("signature", Base64.getEncoder().encodeToString(signature.sign())).toString().getBytes(StandardCharsets.UTF_8);
    }
    private JSONObject verify(byte[] raw) throws Exception {
        return HostUpdateVerifier.verify(raw, key.getPublic(), s -> Base64.getDecoder().decode(s), URI.create("https://example.com/apps/host-update.json"), "dev.uniapp.frame", 1000);
    }
    private void reject(String code) throws Exception {
        try { verify(envelope()); fail("Invalid update accepted"); } catch (java.io.IOException e) { assertEquals(code, e.getMessage()); }
    }
    @Test public void discoversUpdateWithoutAnotherAddress() throws Exception {
        assertEquals("https://example.com/apps/host-update.json", HostUpdateVerifier.endpoint("example.com/apps").toString());
        assertEquals("https://example.com/apps/host-update.json", HostUpdateVerifier.endpoint("https://example.com/apps/catalog.json").toString());
    }
    @Test public void acceptsSignedReleaseAndRelativeUrl() throws Exception {
        assertEquals("https://example.com/apps/uni-frame-4.apk", verify(envelope()).getString("apkUrl"));
    }
    @Test public void acceptsValidatedDriveApkLink() throws Exception {
        update.put("apkUrl", "https://drive.google.com/file/d/public_apk_file_id/view?usp=sharing");
        assertEquals(update.getString("apkUrl"), verify(envelope()).getString("apkUrl"));
    }
    @Test public void comparesNumericVersionAndAndroidSupport() throws Exception {
        JSONObject valid = verify(envelope());
        assertTrue(HostUpdateVerifier.newer(valid, 3, 34));
        assertFalse(HostUpdateVerifier.newer(valid, 4, 34));
        assertFalse(HostUpdateVerifier.newer(valid, 5, 34));
        assertFalse(HostUpdateVerifier.newer(valid, 3, 23));
    }
    @Test public void rejectsWrongApp() throws Exception { update.put("packageName", "another.app"); reject("UPDATE_PACKAGE_MISMATCH"); }
    @Test public void rejectsOtherMetadataType() throws Exception { update.put("type", "app-catalog"); reject("UPDATE_SCOPE_MISMATCH"); }
    @Test public void rejectsExpiredAnnouncement() throws Exception { update.put("expiresAt", 999); reject("UPDATE_EXPIRED"); }
    @Test public void rejectsUnsafeLinks() throws Exception {
        for (String link : new String[]{"http://example.com/update.apk", "intent://something.apk", "https://user:pass@example.com/update.apk", "https://example.com/update.html"}) {
            update.put("apkUrl", link); reject("UPDATE_HTTPS_REQUIRED");
        }
    }
    @Test public void rejectsTamperedLink() throws Exception {
        JSONObject signed = new JSONObject(new String(envelope(), StandardCharsets.UTF_8));
        update.put("apkUrl", "https://evil.example/evil.apk");
        signed.put("payload", Base64.getEncoder().encodeToString(update.toString().getBytes(StandardCharsets.UTF_8)));
        try { verify(signed.toString().getBytes(StandardCharsets.UTF_8)); fail("Tampering accepted"); }
        catch (java.io.IOException expected) { assertEquals("UPDATE_SIGNATURE_INVALID", expected.getMessage()); }
    }
}
