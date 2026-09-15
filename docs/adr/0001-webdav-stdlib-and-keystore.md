# ADR-0001: WebDAV 备份采用自封 OkHttp 客户端,不引第三方库

日期:2026-09-13
状态:已接受

## 背景

TiebaLite 4.0 计划新增"WebDAV 备份与恢复"功能:用户配置自有 WebDAV 服务器(坚果云、Nextcloud、群晖 NAS 等),手动上传/恢复备份数据(浏览记录 + 屏蔽规则 + 软件设置)。

Android 生态中最常用的 WebDAV 客户端库是 sardine-android。选型调研(`.scratch/webdav-backup/research-webdav.md`)核实到:

- sardine-android 最新 release v0.9(2024-02)后约 18 个月无更新;
- 其依赖为 OkHttp **4.12**(项目用 5.3.2,存在版本仲裁冲突风险)与 simple-xml(2013 年最后发布,无维护);
- 备份场景实际只需要 PUT / GET / MKCOL / PROPFIND 四个标准 HTTP 方法 + Basic Auth。

## 决策

自封轻量 WebDAV 客户端,基于项目现有 OkHttp 5.3.2,零新增依赖:

- 方法面最小化:PUT(上传)、GET(下载)、MKCOL(建目录,405 视为已存在)、PROPFIND(Depth: 0 查存在性);
- Basic Auth 预授权(预填 Authorization header,省一次 401 往返);
- 兼容性要点:目录路径以 `/` 结尾;MKCOL 对已存在目录返回 405 需容错;坚果云必须用应用密码(而非账号密码)。

## 后果

- 优点:零依赖、无版本仲裁风险、方法面小几乎免维护、代码量约 150-250 行可控。
- 代价:需要自己处理少量 WebDAV 兼容性细节(已列入实现要点);将来若需要完整 WebDAV 能力(如 LOCK、移动、配额查询),需自行扩展。
- 放弃的方案:引入 sardine-android(维护停滞 + 依赖冲突 + API 面大于所需)。

# ADR-0002: WebDAV 密码用 Android Keystore AES 加密后存 DataStore

日期:2026-09-13
状态:已接受

## 背景

WebDAV 凭据(服务器地址、用户名、密码、远程路径)需在本地持久化。项目现有配置全部存 DataStore preferences(明文)。

调研核实:Google 的 androidx.security:security-crypto(EncryptedSharedPreferences)已在源码中标记 `@Deprecated`,官方建议直接用普通 SharedPreferences——即官方不再提供"开箱即用的加密偏好"方案,需自行处理。

## 决策

- 服务器地址、用户名、远程路径:DataStore 明文存储(非敏感)。
- **密码**:Android Keystore 生成/保管的 AES key 加密后,以密文(Base64)存 DataStore。密钥不出安全硬件(在支持的设备上),应用数据目录被导出时密码仍是密文。
- 不引入已弃用的 security-crypto,也不引入 Tink 等第三方加密库(Keystore + javax.crypto 即可,约 30-50 行)。

## 后果

- 优点:无新增依赖;密文落盘,保护等级高于纯明文;不依赖弃用库。
- 代价:约 30-50 行加密辅助代码;Keystore 行为在厂商 ROM 上偶有差异,需 try/catch 降级(如 Keystore 异常时提示用户重新输入密码,而不是崩溃)。
- 放弃的方案:明文存 DataStore(最简,但备份凭据泄露后果偏重);EncryptedSharedPreferences(已弃用);引入 Tink(功能过剩,多一个依赖)。
