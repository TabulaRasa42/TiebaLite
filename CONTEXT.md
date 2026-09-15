# CONTEXT.md

## 术语表

### 备份与恢复(WebDAV Backup)

**WebDAV 备份**:把用户数据从本机上传到用户自有 WebDAV 服务器(坚果云、Nextcloud、群晖 NAS 等)的手动操作。与既有的"本地导出/导入"(SAF 文件方式,见 `HistoryTransfer`/`BlockRuleTransfer`)是两个并列功能,共用同一套序列化数据格式。

**备份文件(WebDAV 备份产生的 JSON 文件)**:一个固定文件名的 JSON 文件(`tblite_backup.json`),包含三部分:浏览记录、屏蔽规则、软件设置。带 `version` 字段以支持格式演进。每次备份整体覆盖远端同名文件,依赖网盘自身的历史版本能力回溯。

**软件设置(DataStore 设置)**:存在 DataStore `app_preferences` 里的全部键值对(布尔/字符串/整型/浮点)。备份时**排除**设备特定状态键(如电池优化弹窗已展示标记、运行时时间戳等,维护一份短排除清单);恢复时**覆盖**本地对应键。不含账号凭据(账号存于 Room `Account` 表,不进备份)。

**恢复**:从 WebDAV 下载备份文件写入本机。三部分可勾选(浏览记录/屏蔽规则/软件设置,默认全选)。浏览记录与屏蔽规则按既有导入语义**合并+判重**;软件设置**覆盖**。

### WebDAV 客户端(自封 OkHttp)

**WebDAV 客户端**:项目内置的轻量实现(基于 OkHttp 5.3.2,零新增依赖),仅四个方法:PUT(上传)、GET(下载)、MKCOL(建目录,405 视为已存在)、PROPFIND(Depth: 0 查存在性)+ Basic Auth 预授权。不引入第三方 WebDAV 库(sardine-android 已停止维护且依赖冲突)。

**测试连接**:配置页的验证操作,校验服务器可达、凭据有效、远程路径可用(不存在则 MKCOL 创建)。

**远程路径**:备份文件在 WebDAV 服务器上的目录(如 `/tblite/`),以 `/` 结尾,不存在时自动创建。

### 凭据

**WebDAV 凭据**:服务器地址、用户名、密码、远程路径。前三项中仅密码敏感:密码用 Android Keystore 派生的 AES key 加密后以密文存 DataStore;其余明文存 DataStore。不使用已弃用的 EncryptedSharedPreferences。
