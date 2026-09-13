package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.utils.webdav.OkHttpWebDavClient
import com.huanchengfly.tieba.post.utils.webdav.WebDavClient
import com.huanchengfly.tieba.post.utils.webdav.WebDavException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 01 号票验收项的 JVM 单元测试:MockWebServer 起 HTTP 服务,
 * 只断言可观察的 HTTP 行为(方法、路径、请求体、认证头、Depth 头),
 * 不窥探实现内部。认证头期望值经独立 base64 编码核实。
 */
class WebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    // 独立核实的字面值:base64("user@example.com:app-password")
    private val expectedAuth = "Basic dXNlckBleGFtcGxlLmNvbTphcHAtcGFzc3dvcmQ="

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = newClient()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun newClient(baseUrl: String = server.url("/dav/").toString()): WebDavClient =
        OkHttpWebDavClient(
            baseUrl = baseUrl,
            username = "user@example.com",
            password = "app-password",
            okHttpClient = OkHttpClient(),
        )

    private fun enqueue(code: Int, body: String = "") {
        server.enqueue(MockResponse(code = code, headers = Headers.headersOf(), body = body))
    }

    // ── PUT ──────────────────────────────────────────────────

    @Test
    fun `put 上传请求体到目标路径并携带预授权认证头`() = runTest {
        enqueue(201)

        client.put("tblite/backup.json", "hello backup".toByteArray())

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/dav/tblite/backup.json", request.target)
        assertEquals("hello backup", request.body?.utf8())
        assertEquals(expectedAuth, request.headers["Authorization"])
    }

    @Test
    fun `put 路径含空格与中文时按 RFC 3986 编码(空格为 %20)`() = runTest {
        enqueue(200)

        client.put("目录/my backup.json", "x".toByteArray())

        val request = server.takeRequest()
        assertEquals("/dav/%E7%9B%AE%E5%BD%95/my%20backup.json", request.target)
    }

    @Test
    fun `put 收到 5xx 抛 Http 异常`() = runTest {
        enqueue(502)

        val e = runCatching { client.put("backup.json", "x".toByteArray()) }
            .exceptionOrNull()

        assertTrue(e is WebDavException.Http)
        assertEquals(502, (e as WebDavException.Http).code)
    }

    // ── GET ──────────────────────────────────────────────────

    @Test
    fun `get 下载目标路径内容`() = runTest {
        enqueue(200, body = "file content")

        val bytes = client.get("tblite/backup.json")

        assertArrayEquals("file content".toByteArray(), bytes)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/dav/tblite/backup.json", request.target)
        assertEquals(expectedAuth, request.headers["Authorization"])
    }

    // ── MKCOL ────────────────────────────────────────────────

    @Test
    fun `mkcol 对不存在的目录发 MKCOL 请求且带尾斜杠`() = runTest {
        enqueue(201)

        client.mkcol("tblite")

        val request = server.takeRequest()
        assertEquals("MKCOL", request.method)
        assertEquals("/dav/tblite/", request.target)
        assertEquals(expectedAuth, request.headers["Authorization"])
    }

    @Test
    fun `mkcol 收到 405 目录已存在视为成功不抛错`() = runTest {
        enqueue(405)

        // 不抛错即通过:405 语义为"集合已存在"(坚果云)
        client.mkcol("tblite")

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `mkcol 多级路径逐级创建缺失父级`() = runTest {
        // /backup/ 已存在(405),/backup/tblite/ 创建成功(201)
        enqueue(405)
        enqueue(201)

        client.mkcol("backup/tblite")

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/dav/backup/", first.target)
        assertEquals("/dav/backup/tblite/", second.target)
    }

    // ── PROPFIND / exists ────────────────────────────────────

    @Test
    fun `exists 以 Depth 0 的 PROPFIND 探测目录存在性`() = runTest {
        enqueue(207)

        val exists = client.exists("tblite/")

        assertTrue(exists)
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("0", request.headers["Depth"])
        assertEquals("/dav/tblite/", request.target)
    }

    @Test
    fun `exists 收到 404 返回 false 不抛错`() = runTest {
        enqueue(404)

        assertFalse(client.exists("tblite"))
    }

    @Test
    fun `exists 收到其他错误抛 Http 异常`() = runTest {
        enqueue(500)

        val e = runCatching { client.exists("tblite") }.exceptionOrNull()

        assertTrue(e is WebDavException.Http)
        assertEquals(500, (e as WebDavException.Http).code)
    }

    @Test
    fun `exists 对文件路径不带尾斜杠 尊重调用方路径形态`() = runTest {
        enqueue(207)

        val exists = client.exists("tblite/backup.json")

        assertTrue(exists)
        val request = server.takeRequest()
        assertEquals("/dav/tblite/backup.json", request.target)
    }

    // ── baseUrl 归一化 ───────────────────────────────────────

    @Test
    fun `baseUrl 不带尾斜杠时不会丢失最后一段路径`() = runTest {
        // 用户配置 https://host/dav(缺尾斜杠)是常见输入,resolve 相对解析
        // 会把 dav 当"文件"丢掉 → 请求应仍打到 /dav/ 下
        val noSlashClient = newClient(baseUrl = server.url("/dav").toString())
        enqueue(201)

        noSlashClient.put("tblite/backup.json", "x".toByteArray())

        val request = server.takeRequest()
        assertEquals("/dav/tblite/backup.json", request.target)
    }

    // ── 并发安全 ─────────────────────────────────────────────

    @Test
    fun `同一实例并发 get 各自拿到自己的响应体`() = runTest {
        // 两个不同响应排队;若实现共享可变字段,慢请求可能读到快请求的字节
        enqueue(200, body = "first response")
        enqueue(200, body = "second response")

        val deferred: List<kotlinx.coroutines.Deferred<String>> = (1..2).map {
            async { client.get("backup.json").toString(Charsets.UTF_8) }
        }
        val results = deferred.map { it.await() }

        // MockWebServer 按队列顺序响应,但两个并发请求到达顺序不定;
        // 关键断言:两次结果分别是两种已知体之一,不可能出现混合/覆盖损坏
        assertTrue(results.all { it == "first response" || it == "second response" })
        assertEquals(2, results.distinct().size)
    }

    // ── mkcol 边界 ───────────────────────────────────────────

    @Test
    fun `mkcol 空路径抛 IllegalArgumentException 不发请求`() = runTest {
        val e = runCatching { client.mkcol("") }.exceptionOrNull()

        assertTrue(e is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    // ── 错误映射 ─────────────────────────────────────────────

    @Test
    fun `连接失败映射为 Network 异常`() = runTest {
        // 指向一个必然拒绝连接的端口(未启动任何服务)
        val deadClient = newClient(baseUrl = "http://127.0.0.1:1/dav/")

        val e = runCatching { deadClient.put("x", "y".toByteArray()) }.exceptionOrNull()

        assertTrue(e is WebDavException.Network)
    }

    @Test
    fun `get 收到 401 映射为 Auth 异常`() = runTest {
        enqueue(401)

        val e = runCatching { client.get("backup.json") }.exceptionOrNull()

        assertTrue(e is WebDavException.Auth)
    }

    @Test
    fun `get 收到 403 映射为 Auth 异常`() = runTest {
        enqueue(403)

        val e = runCatching { client.get("backup.json") }.exceptionOrNull()

        assertTrue(e is WebDavException.Auth)
    }

    @Test
    fun `get 收到 5xx 映射为 Http 异常且带状态码`() = runTest {
        enqueue(503)

        val e = runCatching { client.get("backup.json") }.exceptionOrNull()

        assertTrue(e is WebDavException.Http)
        assertEquals(503, (e as WebDavException.Http).code)
    }

    @Test
    fun `get 收到其他 4xx 映射为 Http 异常`() = runTest {
        enqueue(412)

        val e = runCatching { client.get("backup.json") }.exceptionOrNull()

        assertTrue(e is WebDavException.Http)
        assertEquals(412, (e as WebDavException.Http).code)
    }
}
