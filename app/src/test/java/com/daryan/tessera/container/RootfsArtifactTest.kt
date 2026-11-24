// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootfsArtifactTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `md5FromSiblingFileIsParsedAndVerified`() {
        val file = tmp.newFile("root.tar.gz").apply { writeBytes("arch".toByteArray()) }
        val artifact = RootfsArtifact(
            key = "arch-aarch64-latest", url = "http://example/root.tar.gz",
            size = 4, checksum = "d6194c68fcc7e79bb57401be603cb1cc", algorithm = "MD5",
        )
        assertTrue(artifact.verify(file))
        assertFalse(artifact.copy(checksum = "0".repeat(32)).verify(file))
    }

    @Test fun `cachedFileWithWrongChecksumIsRejected`() {
        val file = tmp.newFile("root.tar.gz").apply { writeBytes("old".toByteArray()) }
        val artifact = RootfsArtifact("arch-aarch64-latest", "http://example/root", 4,
            "149603e6c03516362a8da23f624db945", "MD5")
        assertFalse(artifact.verify(file)) // stale cached file: content does not match
    }

    @Test fun `parseMd5FileTakesFirstToken`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e",
            RootfsSources.parseMd5("d41d8cd98f00b204e9800998ecf8427e  ArchLinuxARM-aarch64-latest.tar.gz\n"))
    }
}
