package com.huanchengfly.tieba.post.utils.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.utils.webdav.BackupJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * 本地备份传输(01 号票):以流为入参的导出/导入,SAF URI 在 UI 层解开成流,
 * 核心层不碰 Android SAF 类型,保持纯 JVM 可测。
 *
 * 导出:三段同构 JSON(与 WebDAV 备份同一份 [BackupJson] 数据结构),写入不可中断
 * (NonCancellable 语义由 UI 层 withContext(NonCancellable) 包裹,与 WebDAV 上传对齐;
 * 本层保证"序列化 → 单次 write → flush"连续完成,不产生半截 JSON 的应用侧窗口)。
 *
 * 导入:读取流 → [BackupJson.parse] → 委托共享编排 [BackupRestore.restore]
 * (版本守卫在前、先解析全部再写入、判重合并/按键覆盖)——与 WebDAV 恢复同一编排。
 *
 * 异常面:非 JSON/缺 version 抛 [com.huanchengfly.tieba.post.utils.webdav.BackupFormatException],
 * version 超上界抛 [com.huanchengfly.tieba.post.utils.webdav.BackupVersionException],
 * 均整体拒绝零写入(编排语义);IO 失败 IOException 直通,由 UI 层提示。
 */
object LocalBackupTransfer {

    /**
     * 导出三部分数据为备份 JSON 写入 [outputStream]。不关闭流(SAF 打开的流由调用方
     * use 管理生命周期)。
     *
     * @return 导出条目统计(设置部分为过滤排除清单后的键数)
     */
    suspend fun exportTo(
        outputStream: OutputStream,
        histories: List<History>,
        blocks: List<Block>,
        preferences: Preferences,
    ): ExportResult =
        withContext(Dispatchers.IO) {
            val backup = BackupJson.serialize(histories, blocks, preferences)
            // 序列化完成后单次 write:应用侧不存在"半截 JSON"窗口
            outputStream.write(backup.json.toByteArray(Charsets.UTF_8))
            outputStream.flush()
            ExportResult(
                historyCount = histories.size,
                blockRuleCount = blocks.size,
                preferenceKeyCount = backup.preferenceKeyCount,
            )
        }

    data class ExportResult(
        val historyCount: Int,
        val blockRuleCount: Int,
        val preferenceKeyCount: Int,
    )

    /**
     * 从 [inputStream] 读取备份 JSON 并按勾选恢复(委托 [BackupRestore.restore])。
     * 不关闭流(由调用方 use 管理)。
     */
    suspend fun importFrom(
        inputStream: InputStream,
        restoreHistory: Boolean,
        restoreBlockRules: Boolean,
        restorePreferences: Boolean,
        historyStore: HistoryStore,
        blockStore: BlockStore,
        preferencesDataStore: DataStore<Preferences>,
    ): BackupRestore.RestoreResult =
        withContext(Dispatchers.IO) {
            val json = inputStream.readBytes().toString(Charsets.UTF_8)
            val backup = BackupJson.parse(json)
            BackupRestore.restore(
                backup,
                restoreHistory,
                restoreBlockRules,
                restorePreferences,
                historyStore,
                blockStore,
                preferencesDataStore,
            )
        }
}
