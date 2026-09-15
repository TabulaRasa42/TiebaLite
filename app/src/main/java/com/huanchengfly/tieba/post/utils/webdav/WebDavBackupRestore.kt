package com.huanchengfly.tieba.post.utils.webdav

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.utils.backup.BackupMerge
import com.huanchengfly.tieba.post.utils.backup.BlockStore
import com.huanchengfly.tieba.post.utils.backup.HistoryStore
import com.huanchengfly.tieba.post.utils.backup.MergeResult

/**
 * 备份打包上传 / 下载恢复分发(02 号票):复用 [BackupJson] 打包、[BackupMerge] 判重合并
 * (与本地导入同一份实现)、DataStore 按键覆盖。
 *
 * 上传/下载经 [WebDavClient] 接口;测试用 fake client / 内存 DataStore,不强依赖 OkHttp 实现。
 *
 * 异常面:
 * - [BackupVersionException] 备份版本高于 [BackupJson.CURRENT_VERSION],整体拒绝、零写入;
 * - [BackupFormatException] 文件损坏或不是备份文件;
 * - [WebDavException] 四态(Network/Auth/Http/InvalidPath)直通,由 UI 层(05 号票)按类提示;
 * - 远端无备份文件抛 [WebDavException.Http](404),由上层提示"还没有备份"。
 *
 * DataStore 写入经 [preferencesDataStore] seam:生产绑定 Context.dataStore,
 * 测试注入内存实现(真文件实现在 Windows JVM 二次写必败,见 03 号票记录)。
 */
class WebDavBackupRestore(
    private val client: WebDavClient,
    private val historyStore: HistoryStore,
    private val blockStore: BlockStore,
    private val preferencesDataStore: DataStore<Preferences>,
) {

    /** 恢复结果:三部分各自数量;未勾选部分为 null(完全未触碰) */
    data class RestoreResult(
        val history: MergeResult? = null,
        val blockRules: MergeResult? = null,
        val preferencesOverwritten: Int? = null,
    )

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
     * 恢复分发:版本守卫在最前,version 高于本机支持版本整体抛 [BackupVersionException],
     * 零写入。勾选部分按语义写入:浏览记录/屏蔽规则判重合并(与本地导入一致),
     * 设置按键覆盖(本地独有键不动,排除清单键防御性再滤一遍)。
     *
     * 写入前先把所有勾选段完整解析:解析在版本守卫之后、任何写入之前完成,
     * 损坏段抛 [BackupFormatException] 时三部分零写入(与版本拒绝同一整体拒绝语义)。
     * 勾选段在备份中整体缺失(旧版本/被截断的备份)同样抛 [BackupFormatException]——
     * "备份里没有这一段"不是可静默的空恢复。
     */
    suspend fun restore(
        backup: BackupJson.ParsedBackup,
        restoreHistory: Boolean,
        restoreBlockRules: Boolean,
        restorePreferences: Boolean,
    ): RestoreResult {
        if (backup.version > BackupJson.CURRENT_VERSION) {
            throw BackupVersionException(backup.version, BackupJson.CURRENT_VERSION)
        }

        // 解析阶段:全部完成后才进入写入阶段(先解析后写入,避免半途解析失败留下部分写入)
        val historyRecords = if (restoreHistory) {
            if (!backup.hasHistory) {
                throw BackupFormatException("backup file has no history section")
            }
            backup.historyRecords()
        } else {
            emptyList()
        }
        val blockRules = if (restoreBlockRules) {
            if (!backup.hasBlockRules) {
                throw BackupFormatException("backup file has no blockRules section")
            }
            backup.blockRules()
        } else {
            emptyList()
        }
        val preferenceEntries = if (restorePreferences) {
            if (!backup.hasPreferences) {
                throw BackupFormatException("backup file has no preferences section")
            }
            backup.preferenceEntries()
        } else {
            emptyList()
        }

        // 写入阶段
        val history = if (restoreHistory) BackupMerge.mergeHistories(historyRecords, historyStore) else null
        val blockRulesResult = if (restoreBlockRules) BackupMerge.mergeBlockRules(blockRules, blockStore) else null
        var preferencesOverwritten: Int? = null
        if (restorePreferences) {
            preferencesDataStore.edit { prefs ->
                preferenceEntries.forEach { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    (key as Preferences.Key<Any>).let { prefs[it] = value }
                }
            }
            preferencesOverwritten = preferenceEntries.size
        }

        return RestoreResult(history, blockRulesResult, preferencesOverwritten)
    }

    /** 远程目录路径归一化:统一带尾斜杠(文件路径 = 目录 + 固定文件名) */
    private fun remoteDirectory(remotePath: String): String =
        if (remotePath.endsWith("/")) remotePath else "$remotePath/"
}
