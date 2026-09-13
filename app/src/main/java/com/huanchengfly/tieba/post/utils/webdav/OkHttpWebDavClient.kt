package com.huanchengfly.tieba.post.utils.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/**
 * [WebDavClient] 的 OkHttp 实现(ADR-0001)。
 *
 * Basic Auth 预授权:RFC 7617 要求 user:password 按 UTF-8 编码(坚果云用户名即邮箱,
 * 含非 ASCII 时 UTF-8 是唯一正确选择)。目录集合路径带尾斜杠(坚果云要求)。
 */
class OkHttpWebDavClient(
    baseUrl: String,
    username: String,
    password: String,
    private val okHttpClient: OkHttpClient,
) : WebDavClient {

    private val baseUrl: HttpUrl = baseUrl.toHttpUrl()

    // RFC 7617 Basic:UTF-8 的 user:password。预授权,每请求直接带 Authorization 头。
    private val authorization: String =
        okhttp3.Credentials.basic(username, password, charset = Charsets.UTF_8)

    // execute() 读取的响应体(消费时机与响应同生命周期,见 execute 的 use 块)
    private var responseBodyBytes: ByteArray = ByteArray(0)

    // ── WebDavClient ─────────────────────────────────────────

    override suspend fun put(path: String, content: ByteArray) {
        val body = content.toRequestBody("application/octet-stream".toMediaType())
        val request = baseRequest(path).put(body).build()
        requireSuccess(execute(request), "PUT $path")
    }

    override suspend fun get(path: String): ByteArray {
        val request = baseRequest(path).get().build()
        val code = execute(request)
        requireSuccess(code, "GET $path")
        return responseBodyBytes
    }

    override suspend fun mkcol(path: String) {
        val collectionPath = ensureTrailingSlash(path)
        // 逐级补齐缺失父级:直接 MKCOL /backup/tblite/ 时,若 /backup/ 不存在,
        // 部分服务器(标准行为)返回 409,先逐级创建避免。
        var current = ""
        for (segment in collectionPath.trim('/').split('/').filter { it.isNotEmpty() }) {
            current += "/$segment"
            val request = baseRequest("$current/").method("MKCOL", null).build()
            val code = execute(request)
            // 405 = 目录已存在(坚果云等),201/200 = 创建成功,两者都算"目录已就绪"
            if (code != 405) {
                requireSuccess(code, "MKCOL $current/")
            }
        }
    }

    override suspend fun exists(path: String): Boolean {
        val request = baseRequest(ensureTrailingSlash(path))
            .method("PROPFIND", PROPFIND_DEPTH0_BODY.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "0")
            .build()
        val code = execute(request)
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
     * 执行请求,返回状态码由调用方按各方法语义判断(如 MKCOL 405、PROPFIND 404 是合法状态)。
     * 网络层错误映射:IOException → Network。
     */
    private suspend fun execute(request: Request): Int = withContext(Dispatchers.IO) {
        try {
            okHttpClient.newCall(request).execute().use { response ->
                responseBodyBytes = response.body.bytes()
                response.code
            }
        } catch (e: IOException) {
            throw WebDavException.Network("connection failed", e)
        }
    }

    /** 2xx 返回,401/403 → Auth,其余 → Http(code)。 */
    private fun requireSuccess(code: Int, what: String) {
        when {
            code.in2xx() -> Unit
            code == 401 || code == 403 -> throw WebDavException.Auth("auth failed ($code): $what")
            else -> throw mapHttpError(code, what)
        }
    }

    private fun mapHttpError(code: Int, what: String): WebDavException =
        if (code == 401 || code == 403) {
            WebDavException.Auth("auth failed ($code): $what")
        } else {
            WebDavException.Http(code, "HTTP $code: $what")
        }

    /**
     * 相对路径编码后解析到 baseUrl 上。路径各段仅做 percent-encode
     * (远端路径可能含中文/空格),保留 '/' 与 '%'。
     */
    private fun HttpUrl.resolveEncoded(path: String): HttpUrl {
        val encoded = path.trim('/').split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8") }
        val suffix = if (path.endsWith("/")) "/" else ""
        val encodedPath = encoded.ifEmpty { "." } + suffix
        return resolve(encodedPath) ?: throw IllegalArgumentException("invalid path: $path")
    }

    private fun ensureTrailingSlash(path: String): String =
        if (path.endsWith("/")) path else "$path/"

    private fun Int.in2xx(): Boolean = this in 200..299

    private companion object {
        // RFC 4918 规定 PROPFIND 可带空 body;显式全量 propfind body 兼容个别严格服务器
        const val PROPFIND_DEPTH0_BODY = """<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/></D:prop></D:propfind>"""
    }
}
