package dev.uniapp.frame;

import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.json.*;

/** Run the host's actual Java download and signature code against a public source. */
public class VerifyDistribution {
    static byte[] download(URI uri, long limit) throws Exception {
        HttpURLConnection connection = DistributionHttp.open(uri);
        try {
            BundleVerifier.require(connection.getResponseCode() == 200, "HTTP_" + connection.getResponseCode());
            try (java.io.InputStream stream = connection.getInputStream()) { return BundleVerifier.readBounded(stream, limit); }
        } finally { connection.disconnect(); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), output = Path.of(args[2]); Files.createDirectories(output);
        URI endpoint = CatalogVerifier.endpoint(args[1]);
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Files.readAllBytes(root.resolve("android/host/src/main/res/raw/publisher.der"))));
        BundleVerifier.Decoder decoder = text -> Base64.getDecoder().decode(text);
        long now = System.currentTimeMillis() / 1000;
        JSONObject catalog = CatalogVerifier.verify(download(endpoint, 1048576), key, decoder, endpoint, now, false);
        JSONObject profiles = new JSONObject(Files.readString(root.resolve("android/host/src/main/res/raw/runtime_profiles.json")));
        JSONArray verified = new JSONArray();
        for (Object item : catalog.getJSONArray("apps")) {
            JSONObject app = (JSONObject) item;
            for (Object candidate : app.getJSONArray("releases")) {
                JSONObject release = (JSONObject) candidate;
                String abi = release.getString("abi");
                Path bundle = output.resolve(app.getString("projectId") + "-" + abi + ".uaf");
                Files.write(bundle, download(URI.create(release.getString("url")), BundleVerifier.MAX_BUNDLE));
                BundleVerifier.require(BundleVerifier.hash(bundle.toFile()).equals(release.getString("sha256")), "CATALOG_PACKAGE_MISMATCH");
                JSONObject profile = profiles.getJSONObject(abi);
                BundleVerifier.verify(bundle.toFile(), key, decoder, new BundleVerifier.Profile(36, abi,
                    profile.getString("fingerprint"), profile.getString("engineSha256")), app.getString("projectId"), "dev", 0, now, false);
                verified.put(new JSONObject().put("project", app.getString("projectId")).put("abi", abi).put("sha256", release.getString("sha256")));
            }
        }
        JSONObject report = new JSONObject().put("catalog", args[1]).put("expiresAt", catalog.getLong("expiresAt")).put("releases", verified);
        if (catalog.has("hostUpdateUrl")) {
            URI updateUrl = URI.create(catalog.getString("hostUpdateUrl"));
            JSONObject update = HostUpdateVerifier.verify(download(updateUrl, 65536), key, decoder, updateUrl, "dev.uniapp.frame", now);
            Path apk = output.resolve("host.apk");
            try {
                Files.write(apk, download(URI.create(update.getString("apkUrl")), BundleVerifier.MAX_BUNDLE));
                BundleVerifier.require(BundleVerifier.hash(apk.toFile()).equals(update.getString("apkSha256")), "APK_DIGEST_MISMATCH");
                report.put("hostDownload", "sha256-verified");
            } catch (java.io.IOException failure) {
                if (!DriveSource.isDrive(URI.create(update.getString("apkUrl")))
                    || !"DRIVE_SHARING_OR_DOWNLOAD_CONFIRMATION_REQUIRED".equals(failure.getMessage())) throw failure;
                // Host APKs deliberately open in a browser; the user can review Drive's download prompt.
                report.put("hostDownload", "browser-confirmation-required; automated APK download not verified");
            }
            report.put("hostVersion", update.getString("versionName")).put("hostSha256", update.getString("apkSha256"));
        }
        Files.writeString(output.resolve("verification.json"), report.toString(2) + "\n");
        System.out.println(report.toString(2));
    }
}
