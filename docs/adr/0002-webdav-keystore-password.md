# ADR-0002: WebDAV 密码用 Android Keystore AES 加密后存 DataStore

日期:2026-09-13
状态:已接受

## 背景

WebDAV 凭据(服务器地址、用户名、密码、远程路径)需在本地持久化。项目现有配置全部存 DataStore preferences(明文)。

核实事实(2026-09-13,androidx/androidx 仓库 androidx-main 分支源码实测):Google 的 androidx.security:security-crypto(EncryptedSharedPreferences)已在源码中标记 `@Deprecated`,官方注释"Use android.content.SharedPreferences instead"——即官方不再提供"开箱即用的加密偏好"方案,需自行处理。

## 决策

- 服务器地址、用户名、远程路径:DataStore 明文存储(非敏感)。
- **密码**:Android Keystore 生成/保管的 AES key 加密后,以密文(Base64)存 DataStore。密钥不出安全硬件(在支持的设备上),应用数据目录被导出时密码仍是密文。
- 不引入已弃用的 security-crypto,也不引入 Tink 等第三方加密库(Keystore + javax.crypto 即可)。

## 后果

- 优点:无新增依赖;密文落盘,保护等级高于纯明文;不依赖弃用库。
- 代价:加密辅助代码实际约 110 行(`KeystorePasswordCipher`,含密文格式版本字节 + IV 拼装、全部异常收敛为 `CipherUnavailableException`、防并发密钥覆盖的锁)——此前的 30-50 行估算漏掉了异常面与并发加固,后续同类决策(如给其他敏感数据上 Keystore)应按 100+ 行量级评估。Keystore 行为在厂商 ROM 上偶有差异,需 try/catch 降级(如 Keystore 异常时提示用户重新输入密码,而不是崩溃)。
- 放弃的方案:明文存 DataStore(最简,但备份凭据泄露后果偏重);EncryptedSharedPreferences(已弃用);引入 Tink(功能过剩,多一个依赖)。
