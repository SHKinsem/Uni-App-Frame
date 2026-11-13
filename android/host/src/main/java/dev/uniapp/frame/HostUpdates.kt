package dev.uniapp.frame

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

class HostUpdates(private val context: Context) {
    fun check(site: String, catalog: JSONObject?): JSONObject? {
        val explicit = catalog?.optString("hostUpdateUrl", "") ?: ""
        if (explicit.isBlank() && DriveSource.isDrive(CatalogVerifier.endpoint(site))) return null
        val endpoint = if (explicit.isNotBlank()) java.net.URI(explicit) else HostUpdateVerifier.endpoint(site)
        val connection = DistributionHttp.open(endpoint)
        try {
            if (connection.responseCode == 404) return null // Older catalogs need not publish host releases.
            check(connection.responseCode == 200) { "HTTP_${connection.responseCode}" }
            val raw = connection.inputStream.use { BundleVerifier.readBounded(it, 65536) }
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(
                context.resources.openRawResource(R.raw.publisher).use { it.readBytes() }))
            return HostUpdateVerifier.verify(raw, key, { Base64.decode(it, Base64.DEFAULT) }, endpoint,
                context.packageName, System.currentTimeMillis() / 1000)
        } finally { connection.disconnect() }
    }
}
