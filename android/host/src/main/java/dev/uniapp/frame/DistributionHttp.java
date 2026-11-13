package dev.uniapp.frame;

import java.net.URI;
import java.net.HttpURLConnection;

/** Bounded HTTPS redirects; never interprets a Drive login/confirmation page as a package. */
public final class DistributionHttp {
    interface Factory { HttpURLConnection connect(URI uri) throws Exception; }
    public static HttpURLConnection open(URI address) throws Exception {
        return open(address, uri -> (HttpURLConnection) uri.toURL().openConnection());
    }
    static HttpURLConnection open(URI address, Factory factory) throws Exception {
        boolean drive = DriveSource.isDrive(address);
        URI current = drive ? DriveSource.download(address) : address;
        for (int hop = 0; hop <= 5; hop++) {
            BundleVerifier.require("https".equals(current.getScheme()) && current.getHost() != null
                && current.getUserInfo() == null && current.getFragment() == null, "HTTPS_REQUIRED");
            BundleVerifier.require(!drive || DriveSource.downloadHost(current.getHost()), "DRIVE_PUBLIC_DOWNLOAD_REQUIRED");
            HttpURLConnection connection = factory.connect(current);
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "UniAppFrame/0.4.0 (Android; signed application distribution)");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    BundleVerifier.require(location != null && hop < 5, "DOWNLOAD_REDIRECT_LIMIT");
                    current = current.resolve(location);
                    connection.disconnect();
                    continue;
                }
                String type = connection.getContentType();
                BundleVerifier.require(!drive || status != 200 || type == null
                    || !(type.toLowerCase(java.util.Locale.ROOT).contains("text/html")
                        || type.toLowerCase(java.util.Locale.ROOT).contains("application/xhtml")),
                    "DRIVE_SHARING_OR_DOWNLOAD_CONFIRMATION_REQUIRED");
                return connection;
            } catch (Exception failure) { connection.disconnect(); throw failure; }
        }
        throw new java.io.IOException("DOWNLOAD_REDIRECT_LIMIT");
    }
}
