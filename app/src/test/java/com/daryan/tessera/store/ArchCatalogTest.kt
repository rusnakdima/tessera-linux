// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import com.daryan.tessera.container.Distro
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class ArchCatalogTest {
    @Test fun repoDescFilesBecomeStoreApps() {
        val gz = gzipUstar("%NAME%\n\ntestpkg\n\n%VERSION%\n\n1.2-3\n\n%DESC%\n\nA test\n\n".toByteArray(), "testpkg-1.2-3/desc")
        val apps = ArchRepoDb.parse(gz, "extra")
        assertEquals("testpkg", apps.single().pkg)
        assertEquals("A test", apps.single().summary)
        assertEquals(StoreSource.ARCH, apps.single().source)
    }

    @Test fun aurRpcResponseBecomesStoreApps() {
        val json = """{"version":5,"type":"search","resultcount":1,"results":[{"Name":"foo","PackageBase":"foo","Version":"1-1","Description":"Foo app","URLPath":"/cgit/aur.git/snapshot/foo.tar.gz","NumVotes":3}]}"""
        val app = AurCatalog.parseSearch(json).single()
        assertEquals("foo", app.pkg)
        assertEquals(StoreSource.AUR, app.source)
        assertEquals("/cgit/aur.git/snapshot/foo.tar.gz", app.aurPath)
    }

    @Test fun shortAurQueryIsRejectedWithoutRequest() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { AurCatalog.search("a") } }
    }

    @Test fun aurArchiveIsDecodedFromGzipStream() {
        val json = """[{"Name":"yay-bin","Version":"13.1.0-1","Description":"AUR helper","URLPath":"/cgit/aur.git/snapshot/yay-bin.tar.gz","NumVotes":7},{"Name":"foo","Version":"1","Description":"Foo","URLPath":null,"NumVotes":0}]"""
        val apps = AurCatalog.parseArchive(GZIPInputStream(ByteArrayInputStream(gzip(json))))
        assertEquals(2, apps.size)
        assertEquals("yay-bin", apps[0].pkg)
        assertEquals("/cgit/aur.git/snapshot/yay-bin.tar.gz", apps[0].aurPath)
        assertEquals(StoreSource.AUR, apps[0].source)
        assertEquals("foo", apps[1].pkg)
    }

    @Test fun truncatedAurArchiveThrows() {
        val full = """[{"Name":"one","Description":"first"},{"Name":"two","Description":"second"}]"""
        val truncated = full.substring(0, full.length - 15)
        assertThrows(Exception::class.java) {
            AurCatalog.parseArchive(GZIPInputStream(ByteArrayInputStream(gzip(truncated))))
        }
    }

    @Test
    fun x8664UsesOfficialRepos() {
        val script = ArchCatalog.repoScript(Distro.ARCH_X86_64)
        assertTrue(script.contains("https://geo.mirror.pkgbuild.com"))
        assertTrue(script.contains("/core/core.db.tar.gz"))
        assertTrue(script.contains("/extra/extra.db.tar.gz"))
        assertFalse(script.contains("archlinuxarm"))
    }

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().apply {
        GZIPOutputStream(this).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()

    private fun gzipUstar(data: ByteArray, name: String): java.io.InputStream {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        "%07o".format(0).toByteArray().copyInto(h, 100)
        "%011o".format(data.size).toByteArray().copyInto(h, 124)
        h[156] = '0'.code.toByte()
        "ustar".toByteArray().copyInto(h, 257)
        val tar = ByteArrayOutputStream().apply {
            write(h)
            write(data)
            write(ByteArray((512 - data.size % 512) % 512))
            write(ByteArray(1024))
        }.toByteArray()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(tar) }
        return out.toByteArray().inputStream()
    }
}
