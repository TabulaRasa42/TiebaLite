package com.huanchengfly.tieba.post.utils.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.utils.webdav.BackupJson
import com.huanchengfly.tieba.post.utils.webdav.BackupVersionException
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
 * 导入拆两步(用户流程:选文件 → 校验 → 勾选数据项 → 恢复):
 * ① [readBackup] 读取 + 结构/版本守卫(坏文件/高版本当场整体拒绝,零写入);
 * ② 恢复阶段把校验通过的 ParsedBackup 交给共享编排 [BackupRestore.restore]
 * (先解析全部再写入、判重合并/按键覆盖,与 WebDAV 恢复同一编排)。
 *
 * 异常面:非 JSON/缺 version 抛 [com.huanchengfly.tieba.post.utils.webdav.BackupFormatException],
 * version 超上界抛 [com.huanchengfly.tieba.post.utils.webdav.BackupVersionException],
 * 均整体拒绝零写入;IO 失败 IOException 直通,由 UI 层提示。
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
     * 读取 [inputStream] 并校验为**本机可恢复的**备份文件:结构守卫([BackupJson.parse])
     * 与版本守卫(高于 [BackupJson.CURRENT_VERSION] 拒绝),不解析各段、不写入存储。
     * 坏文件/高版本在此整体拒绝,勾选数据项之前就拿到明确失败——用户不必先勾选
     * 再被告知文件不可用(编排 [BackupRestore.restore] 内的版本守卫保留:共享编排
     * 仍是恢复语义的单一来源,此处仅是本地流程的前置快捷路径,双重检查幂等)。
     * 返回的 ParsedBackup 交给 [BackupRestore.restore] 完成恢复。
     * 不关闭流(由调用方 use 管理)。
     */
    suspend fun readBackup(inputStream: InputStream): BackupJson.ParsedBackup =
        withContext(Dispatchers.IO) {
            val json = inputStream.readBytes().toString(Charsets.UTF_8)
            val backup = BackupJson.parse(json)
            if (backup.version > BackupJson.CURRENT_VERSION) {
                throw BackupVersionException(backup.version, BackupJson.CURRENT_VERSION)
            }
            backup
        }
}
