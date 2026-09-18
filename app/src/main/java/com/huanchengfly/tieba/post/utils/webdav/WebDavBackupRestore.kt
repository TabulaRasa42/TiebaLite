package com.huanchengfly.tieba.post.utils.webdav

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.utils.backup.BackupMerge
import com.huanchengfly.tieba.post.utils.backup.BackupRestore
import com.huanchengfly.tieba.post.utils.backup.BlockStore
import com.huanchengfly.tieba.post.utils.backup.HistoryStore

/**
 * 备份打包上传 / 下载恢复分发(02 号票):复用 [BackupJson] 打包、[BackupMerge] 判重合并
 * (与本地导入同一份实现)、DataStore 按键覆盖。
 *
 * 上传/下载经 [WebDavClient] 接口;测试用 fake client / 内存 DataStore,不强依赖 OkHttp 实现。
 *
 * 恢复编排(版本守卫、先解析后写入、合并/覆盖、结果汇总)01 号票上提到
 * [BackupRestore],本类按介质只保留"下载 → 交编排"的 WebDAV 职责,恢复语义与本地侧单一来源。
 *
 * 异常面:
 * - [BackupVersionException] 备份版本高于 [BackupJson.CURRENT_VERSION],整体拒绝、零写入;
 * - [BackupFormatException] 文件损坏或不是备份文件;
 * - [WebDavException] 四态(Network/Auth/Http/InvalidPath)直通,由 UI 层(05 号票)按类提示;
 * - 远端无备份文件抛 [WebDavException.Http](404),由上层提示"还没有备份"。
 *
 * DataStore 写入经注入的 DataStore<Preferences> seam:生产绑定 Context.dataStore,
 * 测试注入内存实现(真文件实现在 Windows JVM 二次写必败,见 03 号票记录)。
 */
class WebDavBackupRestore(
    private val client: WebDavClient,
    private val historyStore: HistoryStore,
    private val blockStore: BlockStore,
    private val preferencesDataStore: DataStore<Preferences>,
) {

    /**
     * 打包三部分数据并上传到远程路径下的固定文件名(整体覆盖)。
     * 远程目录不存在则先建([WebDavClient.mkcol] 已存在视为成功,幂等)。
     *
     * @param remotePath 远程目录路径(如 /tblite/,以 / 结尾或不带均可,mkcol 自行处理)
     * @return 上传的条目统计(设置部分为过滤排除清单后的键数)
     */
    suspend fun upload(
        remotePath: String,
        histories: List<History>,
        blocks: List<Block>,
        preferences: Preferences,
    ): UploadResult {
        val backup = BackupJson.serialize(histories, blocks, preferences)
        client.mkcol(remotePath)
        client.put(remoteDirectory(remotePath) + BackupJson.FILE_NAME, backup.json.toByteArray(Charsets.UTF_8))
        return UploadResult(
            historyCount = histories.size,
            blockRuleCount = blocks.size,
            preferenceKeyCount = backup.preferenceKeyCount,
        )
    }

    data class UploadResult(
        val historyCount: Int,
        val blockRuleCount: Int,
        val preferenceKeyCount: Int,
    )

    /** 下载备份文件并解析(仅结构守卫,版本判断在 [restore])。 */
    suspend fun download(remotePath: String): BackupJson.ParsedBackup {
        val bytes = client.get(remoteDirectory(remotePath) + BackupJson.FILE_NAME)
        return BackupJson.parse(bytes.toString(Charsets.UTF_8))
    }

    /**
     * 恢复分发:委托共享编排 [BackupRestore.restore](01 号票上提)。
     * WebDAV 侧只补充下载得到的 [BackupJson.ParsedBackup] 与本类持有的 store/DataStore,
     * 版本守卫、先解析后写入、判重合并/按键覆盖的语义全部在共享层单一来源。
     */
    suspend fun restore(
        backup: BackupJson.ParsedBackup,
        restoreHistory: Boolean,
        restoreBlockRules: Boolean,
        restorePreferences: Boolean,
    ): BackupRestore.RestoreResult =
        BackupRestore.restore(
            backup,
            restoreHistory,
            restoreBlockRules,
            restorePreferences,
            historyStore,
            blockStore,
            preferencesDataStore,
        )

    /** 远程目录路径归一化:统一带尾斜杠(文件路径 = 目录 + 固定文件名) */
    private fun remoteDirectory(remotePath: String): String =
        if (remotePath.endsWith("/")) remotePath else "$remotePath/"
}
