package dev.uniapp.frame

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.zip.ZipFile

class ModuleStore(private val context: Context) {
    private val root = File(context.filesDir, "modules").apply { mkdirs() }
    private val indexFile = AtomicFile(File(root, "accepted.json"))
    private val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(context.resources.openRawResource(R.raw.publisher).use { it.readBytes() }))
    val abi: String = (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).first()
    val profile: BundleVerifier.Profile by lazy {
        val profiles = JSONObject(context.resources.openRawResource(R.raw.runtime_profiles).bufferedReader().use { it.readText() })
        val p = profiles.getJSONObject(abi)
        BundleVerifier.Profile(Build.VERSION.SDK_INT, abi, p.getString("fingerprint"), p.getString("engineSha256"))
    }
    data class Prepared(val manifest: JSONObject, val apk: File, val nativeDir: File, val odex: File)

    @Synchronized fun index(): JSONObject = if (indexFile.baseFile.exists()) indexFile.openRead().use { JSONObject(it.bufferedReader().readText()) } else JSONObject()
    private fun save(value: JSONObject) {
        val output = indexFile.startWrite()
        try { output.write(value.toString().toByteArray()); indexFile.finishWrite(output) }
        catch (failure: Throwable) { indexFile.failWrite(output); throw failure }
    }
    private fun validId(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))) { "INVALID_ID" }
        return value
    }
    private fun verify(bundle: File, project: String, highest: Long, offline: Boolean): JSONObject =
        BundleVerifier.verify(bundle, publicKey, { Base64.decode(it, Base64.DEFAULT) }, profile, project, "dev", highest, System.currentTimeMillis() / 1000, offline)

    private fun copyReadOnly(input: InputStream, destination: File, limit: Long) {
        check(!destination.exists()) { "IMMUTABLE_PATH_EXISTS" }
        FileOutputStream(destination).use { output ->
            // Android 14 DCL: open the descriptor, mark read-only, then write through that descriptor.
            Os.fchmod(output.fd, 0x124) // 0444
            val buffer = ByteArray(32768)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= limit) { "SIZE_LIMIT" }
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
    }

    @Synchronized fun download(address: String, projectId: String, expectedDigest: String,
                               expectedSequence: Long, displayName: String): JSONObject {
        val project = validId(projectId)
        val url = URL(address)
        require(url.protocol == "https" && url.userInfo == null) { "HTTPS_REQUIRED" }
        val connection = DistributionHttp.open(url.toURI())
        val staging = File(root, ".download-${UUID.randomUUID()}")
        try {
            check(connection.responseCode == 200) { "HTTP_${connection.responseCode}" }
            check(connection.contentLengthLong <= BundleVerifier.MAX_BUNDLE) { "SIZE_LIMIT" }
            connection.inputStream.use { copyReadOnly(it, staging, BundleVerifier.MAX_BUNDLE) }
            val digest = BundleVerifier.hash(staging)
            check(digest == expectedDigest) { "CATALOG_PACKAGE_MISMATCH" }
            val index = index()
            val old = index.optJSONObject(project)
            val highest = old?.getLong("highestSequence") ?: 0L
            val manifest = verify(staging, project, highest, false)
            check(manifest.getLong("releaseSequence") == expectedSequence) { "CATALOG_SEQUENCE_MISMATCH" }
            val directory = File(root, "$project/$digest").apply { mkdirs() }
            val target = File(directory, "release.uaf")
            if (target.exists()) check(BundleVerifier.hash(target) == digest) else check(staging.renameTo(target))
            val version = JSONObject().put("digest", digest).put("buildId", manifest.getString("buildId"))
                .put("entryActivity", manifest.getString("entryActivity"))
            val record = old ?: JSONObject()
            val versions = record.optJSONObject("versions") ?: JSONObject()
            versions.put(digest, version)
            record.put("versions", versions).put("latest", digest).put("highestSequence", manifest.getLong("releaseSequence"))
                .put("name", displayName)
            index.put(project, record)
            save(index) // Only complete, authenticated directories become visible; last good is preserved.
            return manifest
        } finally { connection.disconnect(); staging.delete() }
    }

    @Synchronized fun prepare(projectId: String, digest: String): Prepared {
        val project = validId(projectId)
        require(digest.matches(Regex("[0-9a-f]{64}")))
        val record = index().getJSONObject(project)
        record.getJSONObject("versions").getJSONObject(digest) // Must have been accepted earlier.
        val directory = File(root, "$project/$digest")
        val bundle = File(directory, "release.uaf")
        check(BundleVerifier.hash(bundle) == digest) { "CACHED_BUNDLE_CHANGED" }
        val manifest = verify(bundle, project, record.getLong("highestSequence"), true)
        val run = File(context.codeCacheDir, "run-${UUID.randomUUID()}").apply { mkdirs() }
        val apk = File(run, "project.apk")
        ZipFile(bundle).use { zip -> zip.getInputStream(zip.getEntry(BundleVerifier.APK)).use { copyReadOnly(it, apk, BundleVerifier.MAX_BUNDLE) } }
        check(BundleVerifier.hash(apk) == manifest.getJSONArray("files").getJSONObject(0).getString("sha256"))
        val nativeDir = File(run, "lib").apply { mkdirs() }
        ZipFile(apk).use { zip ->
            val engine = zip.getEntry("lib/$abi/libflutter.so") ?: error("ENGINE_MISSING")
            check(zip.getInputStream(engine).use { BundleVerifier.hash(it, BundleVerifier.MAX_BUNDLE) } == profile.engineHash) { "ENGINE_DIGEST_MISMATCH" }
            check(zip.getEntry("lib/$abi/libapp.so") != null) { "AOT_MISSING" }
            val names = HashSet<String>()
            var total = 0L
            zip.entries().asSequence().filter { it.name.startsWith("lib/$abi/") }.forEach { entry ->
                val name = entry.name.removePrefix("lib/$abi/")
                require(name.matches(Regex("lib[A-Za-z0-9_.-]+\\.so")) && names.add(name)) { "UNSAFE_NATIVE_PATH" }
                total += entry.size
                check(entry.size > 0 && total <= BundleVerifier.MAX_BUNDLE) { "SIZE_LIMIT" }
                zip.getInputStream(entry).use { copyReadOnly(it, File(nativeDir, name), entry.size) }
            }
        }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.path, android.content.pm.PackageManager.GET_ACTIVITIES or android.content.pm.PackageManager.GET_SERVICES or android.content.pm.PackageManager.GET_PERMISSIONS) ?: error("APK_INVALID")
        check(info.packageName == context.packageName) { "HOST_IDENTITY_MISMATCH" }
        check(info.applicationInfo!!.minSdkVersion <= Build.VERSION.SDK_INT) { "ANDROID_TOO_OLD" }
        check(info.activities?.any { it.name == manifest.getString("entryActivity") } == true) { "ENTRY_NOT_DECLARED" }
        check(info.services.isNullOrEmpty()) { "SERVICE_PROXY_NOT_SUPPORTED_YET" }
        return Prepared(manifest, apk, nativeDir, File(run, "odex").apply { mkdirs() })
    }
}
