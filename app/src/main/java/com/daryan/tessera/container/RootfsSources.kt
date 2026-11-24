// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.File
import java.io.IOException

/**
 * The distro-specific half of a rootfs install: what to fetch for a distro, and how to tell a good
 * copy from a stale cache entry. Debian goes through the OCI registry, and checks the layer's
 * SHA-256; Arch Linux ARM goes through its published tarball, and checks the MD5 the mirror
 * publishes alongside it.
 */
object RootfsSources {
    private val registry = OciRegistry()

    /** Maps a registry layer to the artifact that downloads it. */
    fun fromLayer(layer: Layer): RootfsArtifact = RootfsArtifact(
        key = layer.digest,
        url = "${OciRegistry.REGISTRY_URL}/v2/library/debian/blobs/${layer.digest}",
        size = layer.size,
        checksum = layer.digest.substringAfter(':'),
        algorithm = "SHA-256",
    )

    /** Resolves the artifact of [distro]'s rootfs for [abi]. */
    suspend fun resolve(distro: Distro, abi: Platform): RootfsArtifact = when (distro) {
        Distro.DEBIAN -> fromLayer(registry.resolve(distro, abi))
        Distro.ARCH -> arch(distro)
    }

    /** Downloads [artifact] to [dest], verifying it against its checksum. */
    suspend fun download(artifact: RootfsArtifact, dest: File, onProgress: (Long) -> Unit) {
        when (artifact.algorithm) {
            "SHA-256" -> registry.download(Distro.DEBIAN, Layer(artifact.key, artifact.size), dest) { onProgress(it) }
            else -> {
                HttpFetch.download(artifact.url, dest, artifact.size) { onProgress(it) }
                if (!artifact.verify(dest)) {
                    dest.delete()
                    throw IOException("download corrupted: expected ${artifact.checksum}, got ${artifact.algorithm} of ${dest.name}")
                }
            }
        }
    }

    /**
     * The published checksum of a rootfs file: the first token of a `.md5` file, whitespace and all.
     */
    fun parseMd5(text: String): String =
        text.trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() }?.lowercase()
            ?: throw IllegalArgumentException("no MD5 checksum in: ${text.trim()}")

    /** The Arch Linux ARM tarball: MD5-verified, sized from the file the mirror serves today. */
    private suspend fun arch(distro: Distro): RootfsArtifact {
        val url = "https://ca.us.mirror.archlinuxarm.org/os/ArchLinuxARM-${distro.tag}-latest.tar.gz"
        return RootfsArtifact(
            key = "arch-${distro.tag}-latest",
            url = url,
            size = HttpFetch.contentLength(url) ?: throw IOException("no Content-Length for $url"),
            checksum = parseMd5(HttpFetch.text("$url.md5")),
            algorithm = "MD5",
        )
    }
}
