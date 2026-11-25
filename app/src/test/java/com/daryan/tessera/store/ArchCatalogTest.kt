// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
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
