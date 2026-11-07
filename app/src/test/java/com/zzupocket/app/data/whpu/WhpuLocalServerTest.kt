package com.zzupocket.app.data.whpu

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WhpuLocalServerTest {
    @Test fun sendsFormAndSessionCookieToLocalServer() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val captured = executor.submit<Pair<String, String>> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val headers = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: error("Unexpected end of headers")
                            if (line.isEmpty()) break
                            headers.add(line)
                        }
                        val length = headers.first { it.startsWith("Content-Length:", true) }
                            .substringAfter(':').trim().toInt()
                        val chars = CharArray(length)
                        var read = 0
                        while (read < length) {
                            val count = reader.read(chars, read, length - read)
                            check(count > 0)
                            read += count
                        }
                        val payload = """{"kbList":[],"sjkList":[]}"""
                        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${payload.length}\r\nConnection: close\r\n\r\n$payload"
                        socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                        headers.joinToString("\n") to String(chars)
                    }
                }
                val url = "http://127.0.0.1:${server.localPort}/kbcx?gnmkdm=N2151".toHttpUrl()
                val jar = WhpuSessionManager()
                val cookie = Cookie.Builder().name("JSESSIONID").value("mock-session")
                    .hostOnlyDomain(url.host).path("/").build()
                jar.saveFromResponse(url, listOf(cookie))
                val result = WhpuAcademicProvider(jar, endpoint = url.toString()).schedule(WhpuTerm.YEAR_2026_FIRST)
                val (headers, form) = captured.get(5, TimeUnit.SECONDS)
                assertTrue(headers.contains("POST /kbcx?gnmkdm=N2151 HTTP/1.1"))
                assertTrue(headers.contains("Cookie: JSESSIONID=mock-session", ignoreCase = true))
                assertEquals("xnm=2026&xqm=3&kzlx=ck&xsdm=&kclbdm=&kclxdm=", form)
                assertTrue(result.courses.isEmpty())
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
