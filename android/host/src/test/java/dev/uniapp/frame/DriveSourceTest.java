package dev.uniapp.frame;

import java.io.IOException;
import java.net.URI;
import java.net.HttpURLConnection;
import org.junit.Test;
import static org.junit.Assert.*;

public class DriveSourceTest {
    private static final String ID = "1234567890_public_file";
    private static final URI SHARE = URI.create("https://drive.google.com/file/d/" + ID + "/view?usp=sharing");
    @Test public void acceptsShareAndStableDownloadLinks() throws Exception {
        URI expected = URI.create("https://drive.google.com/uc?export=download&id=" + ID);
        assertEquals(expected, CatalogVerifier.endpoint(SHARE.toString()));
        assertEquals(expected, CatalogVerifier.endpoint(expected.toString()));
    }
    @Test public void preservesResourceKey() throws Exception {
        assertTrue(CatalogVerifier.endpoint(SHARE + "&resourcekey=0-test_key").toString().endsWith("&resourcekey=0-test_key"));
    }
    @Test public void rejectsFolderAndUnsafeLinks() throws Exception {
        for (String link : new String[]{"https://drive.google.com/drive/folders/" + ID,
                "http://drive.google.com/file/d/" + ID + "/view", SHARE + "#fragment",
                "https://drive.google.com:8443/uc?id=" + ID,
                "https://drive.google.com/uc?id=" + ID + "&id=another_file_id"}) {
            try { CatalogVerifier.endpoint(link); fail(link); } catch (IOException expected) {}
        }
    }
    private static class Response extends HttpURLConnection {
        final int code; final String location, type; boolean closed;
        Response(int code, String location, String type) throws Exception {
            super(new java.net.URL("https://drive.google.com"));
            this.code = code; this.location = location; this.type = type;
        }
        public int getResponseCode() { return code; }
        public String getHeaderField(String name) { return name.equals("Location") ? location : null; }
        public String getContentType() { return type; }
        public void disconnect() { closed = true; }
        public boolean usingProxy() { return false; }
        public void connect() {}
    }
    @Test public void followsDownloadRedirectWithoutCredentials() throws Exception {
        Response first = new Response(303, "https://drive.usercontent.google.com/download?id=" + ID, null);
        Response last = new Response(200, null, "application/octet-stream");
        int[] calls = {0};
        assertSame(last, DistributionHttp.open(SHARE, uri -> calls[0]++ == 0 ? first : last));
        assertTrue(first.closed); assertEquals(2, calls[0]);
        assertFalse(last.getInstanceFollowRedirects());
        assertNull(last.getRequestProperty("Authorization"));
    }
    @Test public void rejectsLoginRedirectAndHttpDowngrade() throws Exception {
        for (String target : new String[]{"https://accounts.google.com/login", "http://drive.usercontent.google.com/download", "https://drive.usercontent.google.com.evil.test/file"}) {
            Response response = new Response(302, target, null);
            try { DistributionHttp.open(SHARE, uri -> response); fail(target); } catch (IOException expected) {}
            assertTrue(response.closed);
        }
    }
    @Test public void rejectsConfirmationHtmlInsteadOfTreatingItAsAPackage() throws Exception {
        Response response = new Response(200, null, "text/html; charset=utf-8");
        try { DistributionHttp.open(SHARE, uri -> response); fail(); }
        catch (IOException expected) { assertEquals("DRIVE_SHARING_OR_DOWNLOAD_CONFIRMATION_REQUIRED", expected.getMessage()); }
        assertTrue(response.closed);
    }
    @Test public void boundsRedirectLoops() throws Exception {
        int[] calls = {0};
        try { DistributionHttp.open(SHARE, uri -> { calls[0]++; return new Response(302, SHARE.toString(), null); }); fail(); }
        catch (IOException expected) { assertEquals("DOWNLOAD_REDIRECT_LIMIT", expected.getMessage()); }
        assertEquals(6, calls[0]);
    }
}
