package com.huanchengfly.tieba.post.utils.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.huanchengfly.tieba.post.utils.webdav.BackupFormatException
import com.huanchengfly.tieba.post.utils.webdav.BackupJson
import com.huanchengfly.tieba.post.utils.webdav.BackupVersionException

/**
 * 恢复编排(01 号票从 WebDavBackupRestore 上提):"解析后备份 → 按勾选恢复"的单一来源,
 * 版本守卫在前、先解析全部再写入、合并/覆盖、结果汇总。WebDAV 侧与本地侧(流式入参)
 * 共用同一份编排,保证恢复语义永不漂移(spec 决策)。
 *
 * 与介质的唯一耦合是 [DataStore]:设置写入经 DataStore edit(自身原子);
 * 浏览记录/屏蔽规则写入经 [BackupMerge] 的 store seam,生产绑定 Room,测试注入内存实现。
 */
object BackupRestore {

    /** 恢复结果:三部分各自数量;未勾选部分为 null(完全未触碰) */
    data class RestoreResult(
        val history: MergeResult? = null,
        val blockRules: MergeResult? = null,
        val preferencesOverwritten: Int? = null,
    )

    /**
     * 恢复分发:版本守卫在最前,version 高于本机支持版本整体抛 [BackupVersionException],
     * 零写入。勾选部分按语义写入:浏览记录/屏蔽规则判重合并(与本地导入一致),
     * 设置按键覆盖(本地独有键不动,排除清单键防御性再滤一遍)。
     *
     * 写入前先把所有勾选段完整解析:解析在版本守卫之后、任何写入之前完成,
     * 损坏段抛 [BackupFormatException] 时三部分零写入(与版本拒绝同一整体拒绝语义)。
     * 勾选段在备份中整体缺失(旧版本/被截断的备份)同样抛 [BackupFormatException]——
     * "备份里没有这一段"不是可静默的空恢复。
     *
     * 写入阶段各部分独立成段:浏览记录/屏蔽规则各自在自己的合并事务内原子(06 号票),
     * 设置经 DataStore 自身原子;跨部分无整体事务,后者失败时已提交部分保留——
     * 判重合并幂等,重跑恢复即可补齐,失败提示如实报错。
     */
    suspend fun restore(
        backup: BackupJson.ParsedBackup,
        restoreHistory: Boolean,
        restoreBlockRules: Boolean,
        restorePreferences: Boolean,
        historyStore: HistoryStore,
        blockStore: BlockStore,
        preferencesDataStore: DataStore<Preferences>,
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
}
