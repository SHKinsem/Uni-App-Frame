package dev.uniapp.frame;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class BundleVerifierTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private PublicKey key;
    private File good;
    private BundleVerifier.Profile profile;
    @Before public void setup() throws Exception {
        key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(getClass().getResourceAsStream("/test-public.der").readAllBytes()));
        good = temporary.newFile("accepted.uaf");
        try (OutputStream out = new FileOutputStream(good)) { getClass().getResourceAsStream("/accepted.uaf").transferTo(out); }
        profile = new BundleVerifier.Profile(34,"arm64-v8a","b".repeat(64),"c".repeat(64));
    }
    private void verify(File input, long sequence, long now, boolean offline) throws Exception {
        BundleVerifier.verify(input,key,s -> Base64.getDecoder().decode(s),profile,"hello","dev",sequence,now,offline);
    }
    private void reject(String code, File input, long sequence, long now) throws Exception {
        try { verify(input,sequence,now,false); fail("Accepted invalid package"); }
        catch (IOException expected) { assertEquals(code,expected.getMessage()); }
    }
    private File tampered(String name, byte[] value, String extra) throws Exception {
        File result = temporary.newFile();
        try (ZipFile source = new ZipFile(good); ZipOutputStream output = new ZipOutputStream(new FileOutputStream(result))) {
            Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                output.putNextEntry(new ZipEntry(entry.getName()));
                output.write(entry.getName().equals(name) ? value : source.getInputStream(entry).readAllBytes());
                output.closeEntry();
            }
            if (extra != null) { output.putNextEntry(new ZipEntry(extra)); output.write(1); output.closeEntry(); }
        }
        return result;
    }
    @Test public void acceptsPythonSignature() throws Exception { verify(good,1,1000,false); }
    @Test public void rejectsManifestTampering() throws Exception { reject("SIGNATURE_INVALID",tampered("manifest.json","{}".getBytes(),null),1,1000); }
    @Test public void rejectsPayloadTampering() throws Exception {
        byte[] payload = "cross-language signature fixture only".getBytes(); payload[0] = 'X';
        reject("DIGEST_MISMATCH",tampered(BundleVerifier.APK,payload,null),1,1000);
    }
    @Test public void rejectsExtraCodeAndTraversal() throws Exception {
        reject("FILE_SET_MISMATCH",tampered("absent",null,"payload/evil.dex"),1,1000);
        reject("FILE_SET_MISMATCH",tampered("absent",null,"../evil.dex"),1,1000);
    }
    @Test public void rejectsRemoteReplay() throws Exception { reject("RELEASE_REPLAY",good,2,1000); }
    @Test public void rejectsExpiredRemoteRelease() throws Exception { reject("METADATA_EXPIRED",good,1,2000); }
    @Test public void acceptedOfflineCacheDoesNotExpire() throws Exception { verify(good,2,3000,true); }
    @Test public void rejectsWrongAbi() throws Exception {
        profile = new BundleVerifier.Profile(34,"x86_64","b".repeat(64),"c".repeat(64)); reject("ABI_MISMATCH",good,1,1000);
    }
    @Test public void rejectsWrongRuntime() throws Exception {
        profile = new BundleVerifier.Profile(34,"arm64-v8a","c".repeat(64),"c".repeat(64)); reject("RUNTIME_MISMATCH",good,1,1000);
    }
    @Test public void rejectsOldAndroid() throws Exception {
        profile = new BundleVerifier.Profile(23,"arm64-v8a","b".repeat(64),"c".repeat(64)); reject("ANDROID_TOO_OLD",good,1,1000);
    }
}
