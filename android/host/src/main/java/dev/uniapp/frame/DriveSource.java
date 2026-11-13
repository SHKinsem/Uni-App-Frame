package dev.uniapp.frame;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Public Drive blob links only. A signed catalog file contains the app list. */
public final class DriveSource {
    public static boolean isDrive(URI uri) {
        return "drive.google.com".equalsIgnoreCase(uri.getHost())
            || "drive.usercontent.google.com".equalsIgnoreCase(uri.getHost());
    }
    public static URI download(URI uri) throws Exception {
        BundleVerifier.require(isDrive(uri) && "https".equals(uri.getScheme())
            && uri.getUserInfo() == null && uri.getFragment() == null
            && (uri.getPort() == -1 || uri.getPort() == 443), "DRIVE_LINK_INVALID");
        Map<String, String> query = new HashMap<>();
        if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name());
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name()) : "";
            BundleVerifier.require(!query.containsKey(key), "DRIVE_LINK_INVALID");
            query.put(key, value);
        }
        String path = uri.getPath(), id = null;
        if ("drive.google.com".equalsIgnoreCase(uri.getHost()) && path.matches("/file/d/[A-Za-z0-9_-]+(?:/view)?/?"))
            id = path.split("/")[3];
        else if (path.equals("/uc") || path.equals("/open") || path.equals("/download")) id = query.get("id");
        BundleVerifier.require(id != null && id.matches("[A-Za-z0-9_-]{10,128}"), "DRIVE_CATALOG_FILE_LINK_REQUIRED");
        String resource = query.get("resourcekey");
        BundleVerifier.require(resource == null || resource.matches("[A-Za-z0-9_-]{1,256}"), "DRIVE_LINK_INVALID");
        return URI.create("https://drive.google.com/uc?export=download&id=" + id
            + (resource == null ? "" : "&resourcekey=" + resource));
    }
    public static boolean downloadHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(java.util.Locale.ROOT);
        return host.equals("drive.google.com") || host.equals("drive.usercontent.google.com")
            || host.endsWith(".googleusercontent.com");
    }
}
