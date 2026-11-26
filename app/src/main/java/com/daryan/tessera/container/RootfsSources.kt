// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.File
import java.io.IOException

/**
 * The distro-specific half of a rootfs install: what to fetch for a distro, and how to tell a good
 * copy from a stale cache entry. OCI distros go through the registry and check each layer's
 * SHA-256; Arch Linux ARM goes through its published tarball, and checks the MD5 the mirror
 * publishes alongside it.
 */
object RootfsSources {
    private val registry = OciRegistry()

    /** Maps a layer from [repo] to the artifact that downloads it. */
    fun fromLayer(layer: Layer, repo: String = Distro.DEBIAN.repo): RootfsArtifact = RootfsArtifact(
        key = layer.digest,
        url = "${OciRegistry.REGISTRY_URL}/v2/$repo/blobs/${layer.digest}",
        size = layer.size,
        checksum = layer.digest.substringAfter(':'),
        algorithm = "SHA-256",
        ociRepo = repo,
    )

    /** Pure mapping from layers to artifacts; keeps their manifest order. */
    fun artifactsFromLayers(layers: List<Layer>, repo: String): List<RootfsArtifact> =
        layers.map { fromLayer(it, repo) }

    /** Resolves every artifact of [distro]'s rootfs for [abi], in extraction order. */
    suspend fun resolveAll(distro: Distro, abi: Platform): List<RootfsArtifact> = when (distro) {
        Distro.ARCH -> listOf(arch(distro))
        Distro.DEBIAN, Distro.ARCH_X86_64 -> artifactsFromLayers(registry.resolveAll(distro, abi), distro.repo)
    }

    /** The single artifact of a one-layer distro; multi-layer callers use [resolveAll]. */
    suspend fun resolve(distro: Distro, abi: Platform): RootfsArtifact = resolveAll(distro, abi).first()

    /** Downloads [artifact] to [dest], verifying it against its checksum. */
    suspend fun download(artifact: RootfsArtifact, dest: File, onProgress: (Long) -> Unit) {
        val repo = artifact.ociRepo
        if (repo != null) {
            // OciRegistry verifies the blob's digest against the layer while streaming it.
            registry.download(repo, Layer(artifact.key, artifact.size), dest) { onProgress(it) }
        } else {
            HttpFetch.download(artifact.url, dest, artifact.size) { onProgress(it) }
            if (!artifact.verify(dest)) {
                dest.delete()
                throw IOException("download corrupted: expected ${artifact.checksum}, got ${artifact.algorithm} of ${dest.name}")
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
