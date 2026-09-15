# ADR-0001: WebDAV 备份采用自封 OkHttp 客户端,不引第三方库

日期:2026-09-13
状态:已接受

## 背景

TiebaLite 4.0 计划新增"WebDAV 备份与恢复"功能:用户配置自有 WebDAV 服务器(坚果云、Nextcloud、群晖 NAS 等),手动上传/恢复备份数据(浏览记录 + 屏蔽规则 + 软件设置)。

Android 生态中最常用的 WebDAV 客户端库是 sardine-android。选型核实结论(2026-09-13 经 GitHub API 与 Maven Central 直接核实;核实过程记录在 `.scratch/webdav-backup/research-webdav.md`,该目录不入版本库,关键事实以内联形式保留于此):

- sardine-android 最新 release 为 v0.9,发布于 2024-02-15,至核实日约 31 个月无更新(仓库未归档,19 个 open issues);
- 其依赖为 OkHttp **4.12**(项目用 5.3.2,存在版本仲裁冲突风险)与 simple-xml(2013-07-08 最后发布,无维护);
- 备份场景实际只需要 PUT / GET / MKCOL / PROPFIND 四个标准 HTTP 方法 + Basic Auth。

## 决策

自封轻量 WebDAV 客户端,基于项目现有 OkHttp 5.3.2,零新增依赖:

- 方法面最小化:PUT(上传)、GET(下载)、MKCOL(建目录,405 视为已存在)、PROPFIND(Depth: 0 查存在性);
- Basic Auth 预授权(预填 Authorization header,省一次 401 往返);
- 兼容性要点:目录路径以 `/` 结尾;MKCOL 对已存在目录返回 405 需容错;坚果云必须用应用密码(而非账号密码)。

## 后果

- 优点:零依赖、无版本仲裁风险、方法面小几乎免维护。
- 代价:需要自己处理少量 WebDAV 兼容性细节(已列入实现要点);将来若需要完整 WebDAV 能力(如 LOCK、移动、配额查询),需自行扩展。实际实现(`OkHttpWebDavClient`,约 220 行)落在预估的 150-250 行区间内。
- 放弃的方案:引入 sardine-android(维护停滞 + 依赖冲突 + API 面大于所需)。
