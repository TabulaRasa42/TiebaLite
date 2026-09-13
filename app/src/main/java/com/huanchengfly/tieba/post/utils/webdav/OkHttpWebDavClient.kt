package com.huanchengfly.tieba.post.utils.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * [WebDavClient] 的 OkHttp 实现(ADR-0001)。
 *
 * Basic Auth 预授权:RFC 7617 要求 user:password 按 UTF-8 编码(坚果云用户名即邮箱,
 * 含非 ASCII 时 UTF-8 是唯一正确选择)。目录集合路径带尾斜杠(坚果云要求)。
 *
 * 实例无共享可变状态,同一实例可并发调用。
 */
class OkHttpWebDavClient(
    baseUrl: String,
    username: String,
    password: String,
    private val okHttpClient: OkHttpClient,
) : WebDavClient {

    // 归一化尾斜杠:baseUrl 缺尾斜杠时 HttpUrl.resolve 会把最后一段当"文件"丢掉
    // (RFC 3986 相对解析),用户填 https://dav.example.com/dav 时所有请求将打到错误路径。
    private val baseUrl: HttpUrl = baseUrl.toHttpUrl().let {
        if (it.encodedPath.endsWith("/")) it
        else it.newBuilder().encodedPath("${it.encodedPath}/").build()
    }

    // RFC 7617 Basic:UTF-8 的 user:password。预授权,每请求直接带 Authorization 头。
    private val authorization: String =
        okhttp3.Credentials.basic(username, password, charset = Charsets.UTF_8)

    // ── WebDavClient ─────────────────────────────────────────

    override suspend fun put(path: String, content: ByteArray) {
        val body = content.toRequestBody("application/octet-stream".toMediaType())
        val request = baseRequest(path).put(body).build()
        requireSuccess(execute(request).first, "PUT $path")
    }

    override suspend fun get(path: String): ByteArray {
        val request = baseRequest(path).get().build()
        val (code, bytes) = execute(request)
        requireSuccess(code, "GET $path")
        return bytes
    }

    override suspend fun mkcol(path: String) {
        require(path.trim('/').isNotEmpty()) { "mkcol path must not be empty or root" }
        val collectionPath = ensureTrailingSlash(path)
        // 逐级补齐缺失父级:直接 MKCOL /backup/tblite/ 时,若 /backup/ 不存在,
        // 部分服务器(标准行为)返回 409,先逐级创建避免。
        var current = ""
        for (segment in collectionPath.trim('/').split('/').filter { it.isNotEmpty() }) {
            current += "/$segment"
            val request = baseRequest("$current/").method("MKCOL", null).build()
            val (code, _) = execute(request)
            // 405 = 目录已存在(坚果云等),201/200 = 创建成功,两者都算"目录已就绪"
            if (code != 405) {
                requireSuccess(code, "MKCOL $current/")
            }
        }
    }

    override suspend fun exists(path: String): Boolean {
        // PROPFIND 目标照调用方原样:尾斜杠 = 目录(坚果云要求),不带 = 文件。
        val request = baseRequest(path)
            .method("PROPFIND", PROPFIND_DEPTH0_BODY.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "0")
            .build()
        val (code, _) = execute(request)
        return when {
            code.in2xx() -> true
            // 404 = 路径不存在,是 exists 的合法答案而非错误
            code == 404 -> false
            else -> throw mapHttpError(code, "PROPFIND $path")
        }
    }

    // ── 内部 ─────────────────────────────────────────────────

    private fun baseRequest(path: String): Request.Builder =
        Request.Builder()
            .url(baseUrl.resolveEncoded(path))
            .header("Authorization", authorization)

    /**
     * 执行请求,返回 (状态码, 响应体字节)。状态码由调用方按各方法语义判断
     * (如 MKCOL 405、PROPFIND 404 是合法状态),字节仅供 get() 消费。
     * 网络层错误映射:IOException → Network。每次调用独立返回,无共享可变状态。
     */
    private suspend fun execute(request: Request): Pair<Int, ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    response.code to response.body.bytes()
                }
            } catch (e: IOException) {
                throw WebDavException.Network("connection failed", e)
            }
        }

    /** 2xx 返回,401/403 → Auth,其余 → Http(code)。 */
    private fun requireSuccess(code: Int, what: String) {
        if (!code.in2xx()) {
            throw mapHttpError(code, what)
        }
    }

    private fun mapHttpError(code: Int, what: String): WebDavException =
        if (code == 401 || code == 403) {
            WebDavException.Auth("auth failed ($code): $what")
        } else {
            WebDavException.Http(code, "HTTP $code: $what")
        }

    /**
     * 相对路径逐段 percent-encode(空格→%20,非表单编码的 +)后解析到 baseUrl 上。
     * 尾斜杠保留:集合(目录)路径以 / 结尾。
     *
     * 用整串 toHttpUrl 静态解析而非 HttpUrl.Builder:Builder 的 encodedPath/addPathSegment
     * 会规范化掉结尾空段(尾斜杠丢失);字符串解析对尾斜杠原生保留。
     */
    private fun HttpUrl.resolveEncoded(path: String): HttpUrl {
        val encoded = path.trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment -> encodePathSegment(segment) }
        val suffix = if (encoded.isNotEmpty()) "/$encoded" else ""
        val trailing = if (path.endsWith("/")) "/" else ""
        // baseUrl 已在构造时归一化为 / 结尾,这里可安全 trimEnd 后拼接
        val base = toString().trimEnd('/')
        return "$base$suffix$trailing".toHttpUrl()
    }

    /**
     * RFC 3986 路径段编码:保留字符 = unreserved + 子定界符,空格与
     * 表单保留字(+、=、&)均按 %XX 编码,与 HttpUrl 自身的 canonical 化一致。
     */
    private fun encodePathSegment(segment: String): String = buildString {
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            when (val c = b.toChar()) {
                in 'a'..'z', in 'A'..'Z', in '0'..'9',
                '-', '_', '.', '~',
                '!', '$', '\'', '(', ')', '*', ',', ';',
                -> append(c)
                else -> append('%').append(b.toString(16).padStart(2, '0').uppercase())
            }
        }
    }

    private fun ensureTrailingSlash(path: String): String =
        if (path.endsWith("/")) path else "$path/"

    private fun Int.in2xx(): Boolean = this in 200..299

    private companion object {
        // RFC 4918 规定 PROPFIND 可带空 body;显式全量 propfind body 兼容个别严格服务器
        const val PROPFIND_DEPTH0_BODY = """<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/></D:prop></D:propfind>"""
    }
}
