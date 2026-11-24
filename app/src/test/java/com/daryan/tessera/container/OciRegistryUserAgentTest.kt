// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * The global constraint every Tessera network read carries a descriptive User-Agent: the token,
 * manifest and blob reads of the registry path must not be the exception.
 */
class OciRegistryUserAgentTest {
    private lateinit var server: HttpServer
    private val userAgents = mutableListOf<String>()
    private lateinit var registry: OciRegistry

    @Before
    fun setUp() {
        userAgents.clear()
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/") { exchange: HttpExchange ->
            userAgents += exchange.requestHeaders.getFirst("User-Agent") ?: "<none>"
            val body = if (exchange.requestURI.path.startsWith("/token")) """{"token":"tok"}""" else
                """{"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"sha256:x","size":42}]}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val port = server.address.port
        registry = OciRegistry(
            registry = "http://localhost:$port",
            auth = "http://localhost:$port/token?service=registry.docker.io",
        )
    }

    @After
    fun tearDown() = server.stop(0)

    @Test fun `registry network reads carry the tessera user agent`() {
        val layer = registry.resolve(Distro.DEBIAN, Platform("amd64"))
        assertEquals(Layer("sha256:x", 42), layer)
        assertEquals(listOf("Tessera/1.0", "Tessera/1.0"), userAgents)
    }
}
