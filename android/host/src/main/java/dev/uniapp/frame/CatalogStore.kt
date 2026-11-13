package dev.uniapp.frame

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

class CatalogStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "catalog-cache.json"))
    private val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(
        context.resources.openRawResource(R.raw.publisher).use { it.readBytes() }))
    private val decode = BundleVerifier.Decoder { Base64.decode(it, Base64.DEFAULT) }
    @Synchronized fun savedAddress(): String = read()?.getString("address") ?: ""
    private fun read(): JSONObject? = if (file.baseFile.exists()) file.openRead().use {
        JSONObject(it.bufferedReader().readText())
    } else null
    @Synchronized fun cached(allowExpired: Boolean = true): JSONObject? {
        val record = read() ?: return null
        return CatalogVerifier.verify(decode.decode(record.getString("envelope")), key, decode,
            CatalogVerifier.endpoint(record.getString("address")), System.currentTimeMillis() / 1000, allowExpired)
    }
    @Synchronized fun fetch(address: String): JSONObject {
        val endpoint = CatalogVerifier.endpoint(address)
        val connection = DistributionHttp.open(endpoint)
        try {
            check(connection.responseCode == 200) { "HTTP_${connection.responseCode}" }
            val raw = connection.inputStream.use { BundleVerifier.readBounded(it, 1048576) }
            val result = CatalogVerifier.verify(raw, key, decode, endpoint, System.currentTimeMillis() / 1000, false)
            val record = JSONObject().put("address", endpoint.toString()).put("envelope", Base64.encodeToString(raw, Base64.NO_WRAP))
            val output = file.startWrite()
            try { output.write(record.toString().toByteArray()); file.finishWrite(output) }
            catch (failure: Throwable) { file.failWrite(output); throw failure }
            return result
        } finally { connection.disconnect() }
    }
    fun release(app: JSONObject, store: ModuleStore): JSONObject? {
        if (store.abi !in setOf("arm64-v8a", "x86_64")) return null
        val releases = app.getJSONArray("releases")
        return (0 until releases.length()).map { releases.getJSONObject(it) }.firstOrNull {
            it.getString("abi") == store.abi && it.getInt("minAndroidApi") <= Build.VERSION.SDK_INT &&
                it.getString("runtimeFingerprint") == store.profile.runtime
        }
    }
}
