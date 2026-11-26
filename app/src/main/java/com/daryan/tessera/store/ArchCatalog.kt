// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import com.daryan.tessera.container.Distro
import com.daryan.tessera.container.HttpFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/** Reads an Arch package database (`<repo>.db.tar.gz`) into store entries. */
object ArchRepoDb {
    fun parse(db: InputStream, repo: String): List<StoreApp> = buildList {
        GZIPInputStream(db).use { gz ->
            Tar.entries(gz) { it.endsWith("/desc") }.forEach { (_, bytes) ->
                val fields = descFields(String(bytes, Charsets.UTF_8))
                val name = fields["NAME"]?.takeIf { it.isNotBlank() } ?: return@forEach
                add(
                    StoreApp(
                        id = "arch:$repo:$name",
                        pkg = name,
                        name = name,
                        summary = fields["DESC"].orEmpty(),
                        description = "",
                        categories = emptyList(),
                        icon = "",
                        component = repo,
                        desktopId = "",
                        source = StoreSource.ARCH,
                    ),
                )
            }
        }
    }

    /** pacman's `desc` files are `%KEY%` lines followed by a blank line and the value. */
    private fun descFields(text: String): Map<String, String> {
        val lines = text.lines()
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!line.startsWith("%") || !line.endsWith("%")) {
                i++
                continue
            }
            val key = line.trim('%')
            val value = mutableListOf<String>()
            i++
            while (i < lines.size && !(lines[i].startsWith("%") && lines[i].endsWith("%"))) {
                if (lines[i].isNotBlank()) value += lines[i]
                i++
            }
            out[key] = value.joinToString("\n").trim()
        }
        return out
    }
}

/**
 * The Arch User Repository: the v5 RPC for search and the JSON metadata archive for the full
 * catalog. Both need a User-Agent; a search shorter than two characters is refused locally so a
 * typo can't waste the shared daily quota.
 */
object AurCatalog {
    private const val RPC = "https://aur.archlinux.org/rpc/v5"
    private const val ARCHIVE = "https://aur.archlinux.org/packages-meta-v1.json.gz"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Response(
        val version: Int = 5,
        val type: String = "",
        val resultcount: Int = 0,
        val results: List<Package> = emptyList(),
        val error: String? = null,
    )

    @Serializable
    private data class Package(
        val Name: String? = null,
        val Version: String? = null,
        val Description: String? = null,
        val URLPath: String? = null,
        val NumVotes: Int = 0,
    )

    /** Maps the AUR's fields to a store app; entries without a name aren't apps. */
    fun parseSearch(json: String): List<StoreApp> {
        val response = runCatching { this.json.decodeFromString<Response>(json) }.getOrNull() ?: return emptyList()
        return response.results.mapNotNull { it.toApp() }
    }

    /** Searches names and descriptions; a query shorter than two characters is refused. */
    suspend fun search(query: String): List<StoreApp> {
        require(query.length >= 2) { "AUR search needs at least 2 characters" }
        val url = "$RPC/search/${URLEncoder.encode(query, "UTF-8")}?by=name-desc"
        val body = withContext(Dispatchers.IO) { HttpFetch.text(url) }
        val response = json.decodeFromString<Response>(body)
        if (response.type == "error") throw IOException(response.error ?: "AUR search failed")
        return parseSearch(body)
    }

    /**
     * The whole AUR metadata archive, cached as plain JSON and revalidated with ETag and
     * Last-Modified so an unchanged archive costs no download.
     */
    suspend fun loadArchive(dir: File): List<StoreApp> = withContext(Dispatchers.IO) {
        val cache = File(dir, "aur-meta.json")
        val headers = File(dir, "aur-meta.headers")
        val conn = URL(ARCHIVE).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", HttpFetch.USER_AGENT)
        if (headers.isFile) {
            val lines = headers.readLines()
            lines.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("If-None-Match", it) }
            lines.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("If-Modified-Since", it) }
        }
        try {
            when (conn.responseCode) {
                304 -> parseArchive(cache.readText())
                200 -> {
                    val text = GZIPInputStream(conn.inputStream.buffered(64 * 1024)).bufferedReader().use { it.readText() }
                    val apps = parseArchive(text)
                    cache.parentFile?.mkdirs()
                    cache.writeText(text)
                    headers.writeText(listOf(conn.getHeaderField("ETag").orEmpty(), conn.getHeaderField("Last-Modified").orEmpty()).joinToString("\n"))
                    apps
                }
                else -> throw IOException("GET $ARCHIVE: HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseArchive(json: String): List<StoreApp> =
        this.json.decodeFromString<List<Package>>(json).mapNotNull { it.toApp() }

    private fun Package.toApp(): StoreApp? {
        val name = Name?.takeIf { it.isNotBlank() } ?: return null
        return StoreApp(
            id = "aur:$name",
            pkg = name,
            name = name,
            summary = Description.orEmpty(),
            description = "",
            categories = emptyList(),
            icon = "",
            component = "aur",
            desktopId = "",
            source = StoreSource.AUR,
            aurPath = URLPath,
        )
    }
}

/** The Arch store: the distro's official repositories plus the AUR. One source failing drops only it. */
object ArchCatalog {
    private const val ALARM_MIRROR = "https://ca.us.mirror.archlinuxarm.org/aarch64"
    private val ALARM_REPOS = listOf("core", "extra", "community", "alarm")
    private const val OFFICIAL_MIRROR = "https://geo.mirror.pkgbuild.com"
    private val OFFICIAL_REPOS = listOf("core", "extra")

    /**
     * The repository databases [distro]'s store reads, grouped by repository name. ARM keeps
     * ALARM's four flat `core extra community alarm` databases byte-for-byte. x86_64 uses the
     * official Arch `core` and `extra`: the mirror serves their databases under `os/x86_64`,
     * with the flat `<repo>/<repo>.db.tar.gz` names kept as a fallback for mirrors that publish
     * the same flat layout ALARM uses.
     */
    private fun repos(distro: Distro): List<Pair<String, List<String>>> = when (distro) {
        Distro.DEBIAN -> emptyList()
        Distro.ARCH -> ALARM_REPOS.map { repo -> repo to listOf("$ALARM_MIRROR/$repo/$repo.db.tar.gz") }
        Distro.ARCH_X86_64 -> OFFICIAL_REPOS.map { repo ->
            repo to listOf(
                "$OFFICIAL_MIRROR/$repo/os/x86_64/$repo.db.tar.gz",
                "$OFFICIAL_MIRROR/$repo/$repo.db.tar.gz",
            )
        }
    }

    /** The database URLs [distro]'s store reads, one per line, in the order [load] tries them. */
    fun repoScript(distro: Distro): String = repos(distro).joinToString("\n") { (_, urls) ->
        urls.joinToString("\n")
    }

    suspend fun load(dir: File, distro: Distro): List<StoreApp> = withContext(Dispatchers.IO) {
        val apps = mutableListOf<StoreApp>()
        for ((repo, urls) in repos(distro)) {
            currentCoroutineContext().ensureActive()
            runCatching {
                val db = urls.firstNotNullOfOrNull { url -> runCatching { HttpFetch.bytes(url) }.getOrNull() }
                    ?: throw IOException("no ${repo}.db.tar.gz for ${distro.label} at ${urls.first()}")
                apps += ArchRepoDb.parse(ByteArrayInputStream(db), repo)
            }
        }
        currentCoroutineContext().ensureActive()
        runCatching { apps += AurCatalog.loadArchive(dir) }
        apps
    }
}
