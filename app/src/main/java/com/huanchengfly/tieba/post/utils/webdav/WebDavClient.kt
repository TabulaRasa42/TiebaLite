package com.huanchengfly.tieba.post.utils.webdav

/**
 * WebDAV 客户端(ADR-0001):自封 OkHttp,零新增依赖。
 *
 * 仅四个方法:PUT(上传)、GET(下载)、MKCOL(建目录)、PROPFIND(Depth: 0 查存在性),
 * Basic Auth 预授权(每请求自带 Authorization 头,省一次 401 往返)。
 * 兼容性细节(目录尾斜杠、MKCOL 405 视为已存在、坚果云应用密码)全部藏在实现内。
 *
 * 远程路径相对 baseUrl 解析(如 baseUrl = https://dav.jianguoyun.com/dav/),
 * 以尾斜杠结尾的路径表示目录,不带尾斜杠表示文件。
 *
 * 异常面:所有方法失败一律抛 [WebDavException](含 [WebDavException.InvalidPath]
 * ——空路径或含 "." / ".." 相对段的路径在请求发出前即拒绝)。
 */
interface WebDavClient {

    /** 上传内容到远程路径。2xx 成功,否则抛 [WebDavException]。 */
    suspend fun put(path: String, content: ByteArray)

    /** 下载远程路径内容。2xx 返回字节,否则抛 [WebDavException]。 */
    suspend fun get(path: String): ByteArray

    /** 确保目录存在:逐级 MKCOL,已存在(405)视为成功。多级远程路径(如 /backup/tblite/)自动补齐缺失父级。 */
    suspend fun mkcol(path: String)

    /** 远程路径是否存在:PROPFIND Depth: 0。2xx = 存在,404 = 不存在,其他错误抛 [WebDavException]。 */
    suspend fun exists(path: String): Boolean
}

/**
 * WebDAV 操作失败的统一异常,按上层提示需要分四类:
 * - [Network]:连接失败/超时(检查网络或服务器地址)
 * - [Auth]:401/403 认证失败(检查用户名/应用密码)
 * - [Http]:其余 HTTP 错误(带状态码)
 * - [InvalidPath]:路径非法(空路径、含 "." / ".." 相对段)——请求未发出
 */
sealed class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(message: String, cause: Throwable? = null) : WebDavException(message, cause)
    class Auth(message: String) : WebDavException(message)
    class Http(val code: Int, message: String) : WebDavException(message)
    class InvalidPath(message: String) : WebDavException(message)
}
