// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * A distribution we can install: Debian from its official OCI image, Arch Linux ARM from its
 * published tarball, and Arch Linux x86_64 from the official OCI index pinned by digest.
 */
enum class Distro(
    val label: String,
    val repo: String,
    val tag: String,
    val downloadMb: Int,
    val installedMb: Int,
    val requiredFreeMb: Int,
) {
    DEBIAN("Debian 13", "library/debian", "trixie", 50, 200, 150),
    ARCH("Arch Linux ARM", "archlinuxarm", "aarch64", 830, 2500, 2490),
    ARCH_X86_64(
        "Arch Linux (x86_64)",
        "library/archlinux",
        "sha256:375a401bb3c6376b37f40b6dfa25067db37ec78d0b7af67ae6315f43a0184a9d",
        135, 411, 650,
    ),
}

/** Both Arch variants use pacman and the Arch package overlays; Debian keeps apt/dpkg. */
val Distro.isArch: Boolean get() = this == Distro.ARCH || this == Distro.ARCH_X86_64

/** An Arch rootfs only runs on the ABI it was built for: no emulation, no silent swap. */
fun Distro.supportedOn(abi: String): Boolean = when (this) {
    Distro.DEBIAN -> true
    Distro.ARCH -> abi == "arm64-v8a"
    Distro.ARCH_X86_64 -> abi == "x86_64"
}

/** x86_64 Arch is PRoot-only; the untested root path is refused. */
fun Distro.chrootSupported(): Boolean = when (this) {
    Distro.DEBIAN, Distro.ARCH -> true
    Distro.ARCH_X86_64 -> false
}

/** OCI platform for an Android ABI. */
data class Platform(val architecture: String, val variant: String? = null) {
    companion object {
        fun forAbi(abi: String) = when (abi) {
            "arm64-v8a" -> Platform("arm64", "v8")
            "armeabi-v7a" -> Platform("arm", "v7")
            "x86_64" -> Platform("amd64")
            "x86" -> Platform("386")
            else -> throw IllegalArgumentException("unsupported ABI $abi")
        }
    }
}

data class Layer(val digest: String, val size: Long)

/**
 * Just enough of the registry API to pull an image: anonymous token, image index, per-platform
 * manifest, then every layer blob. Blobs are content-addressed, so the SHA-256 we check after
 * downloading each one is the integrity guarantee.
 */
class OciRegistry(
    private val registry: String = "https://registry-1.docker.io",
    private val auth: String = "https://auth.docker.io/token?service=registry.docker.io",
) {
    /** The one layer of a single-layer image; multi-layer distros use [resolveAll]. */
    fun resolve(distro: Distro, platform: Platform): Layer = singleLayer(manifest(distro, platform))

    /** Every gzip layer of [distro]'s image for [platform], in manifest order. */
    fun resolveAll(distro: Distro, platform: Platform): List<Layer> = layers(manifest(distro, platform))

    private fun manifest(distro: Distro, platform: Platform): JsonObject {
        val token = token(distro.repo)
        val top = getJson("$registry/v2/${distro.repo}/manifests/${distro.tag}", token, MANIFEST_TYPES)
        return if ("manifests" in top) {
            val digest = selectManifest(top, platform)
            getJson("$registry/v2/${distro.repo}/manifests/$digest", token, MANIFEST_TYPES)
        } else top
    }

    /** Downloads [layer] from [distro]'s repository to [dest], verifying its digest. */
    suspend fun download(distro: Distro, layer: Layer, dest: File, onProgress: (Long) -> Unit) =
        download(distro.repo, layer, dest, onProgress)

    /**
     * Downloads [layer] from [repo] to [dest], verifying its digest. Calls [onProgress] with bytes
     * so far. The caller picks the repository because an artifact carries it (`ociRepo`).
     */
    suspend fun download(repo: String, layer: Layer, dest: File, onProgress: (Long) -> Unit) {
        val token = token(repo)
        // The registry answers with a redirect to a signed CDN URL; follow it without our token.
        var conn = open("$registry/v2/$repo/blobs/${layer.digest}", token, null)
        conn.instanceFollowRedirects = false
        var hops = 0
        while (conn.responseCode in 300..399) {
            if (++hops > 5) throw IOException("too many redirects")
            val next = conn.getHeaderField("Location") ?: throw IOException("redirect without Location")
            conn.disconnect()
            conn = open(URL(conn.url, next).toString(), null, null)
            conn.instanceFollowRedirects = false
        }
        if (conn.responseCode != 200) throw IOException("blob download failed: HTTP ${conn.responseCode}")

        val sha = MessageDigest.getInstance("SHA-256")
        val part = File(dest.path + ".part")
        var done = 0L
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    sha.update(buf, 0, n)
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done)
                }
            }
        }
        val actual = "sha256:" + sha.digest().joinToString("") { "%02x".format(it) }
        if (actual != layer.digest) {
            part.delete()
            throw IOException("download corrupted: expected ${layer.digest}, got $actual")
        }
        if (!part.renameTo(dest)) throw IOException("could not move ${part.name} into place")
    }

    private fun token(repo: String): String {
        val url = "$auth&scope=" + URLEncoder.encode("repository:$repo:pull", "UTF-8")
        return getJson(url, null, null)["token"]!!.jsonPrimitive.content
    }

    private fun getJson(url: String, token: String?, accept: String?): JsonObject {
        val conn = open(url, token, accept)
        try {
            if (conn.responseCode != 200) throw IOException("GET $url: HTTP ${conn.responseCode}")
            return json.parseToJsonElement(conn.inputStream.reader().use { it.readText() }).jsonObject
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, token: String?, accept: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", USER_AGENT)
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            accept?.let { setRequestProperty("Accept", it) }
        }

    companion object {
        const val REGISTRY_URL = "https://registry-1.docker.io"
        const val USER_AGENT = "Tessera/1.0"
        private val json = Json { ignoreUnknownKeys = true }
        private const val MANIFEST_TYPES =
            "application/vnd.oci.image.index.v1+json, " +
                "application/vnd.docker.distribution.manifest.list.v2+json, " +
                "application/vnd.oci.image.manifest.v1+json, " +
                "application/vnd.docker.distribution.manifest.v2+json"

        /** Picks the manifest digest for [platform] out of an image index. */
        internal fun selectManifest(index: JsonObject, platform: Platform): String {
            val candidates = index["manifests"]!!.jsonArray.map { it.jsonObject }.filter { m ->
                val p = m["platform"]?.jsonObject ?: return@filter false
                p["architecture"]?.jsonPrimitive?.content == platform.architecture &&
                    p["os"]?.jsonPrimitive?.content == "linux" &&
                    (platform.variant == null || p["variant"] == null ||
                        p["variant"]!!.jsonPrimitive.content == platform.variant)
            }
            val m = candidates.firstOrNull()
                ?: throw IOException("image has no linux/${platform.architecture} build")
            return m["digest"]!!.jsonPrimitive.content
        }

        /** All `tar+gzip` layers of [manifest], in the order the manifest lists them. */
        internal fun layers(manifest: JsonObject): List<Layer> {
            val entries = manifest["layers"]?.jsonArray
                ?: throw IOException("image manifest has no layers")
            if (entries.isEmpty()) throw IOException("image manifest has no layers")
            // Whiteout entries (.wh.*) are out of scope: the pinned Arch image's layers only
            // overwrite earlier files, so extracting in manifest order is enough.
            return entries.mapIndexed { index, element ->
                val l = element.jsonObject
                val type = l["mediaType"]?.jsonPrimitive?.content
                    ?: throw IOException("layer $index has no media type")
                if (!type.endsWith("tar+gzip") && !type.endsWith("tar.gzip")) {
                    throw IOException("unsupported layer type $type")
                }
                Layer(l["digest"]!!.jsonPrimitive.content, l["size"]!!.jsonPrimitive.long)
            }
        }

        /** The only layer of a single-layer image (Debian); kept as a delegating alias for callers. */
        internal fun singleLayer(manifest: JsonObject): Layer {
            val all = layers(manifest)
            if (all.size != 1) throw IOException("expected a single-layer image, got ${all.size} layers")
            return all.single()
        }
    }
}
