package com.huanchengfly.tieba.post.utils.backup

import com.google.gson.annotations.SerializedName
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.Block.Companion.getKeywords
import com.huanchengfly.tieba.post.models.database.History

/**
 * 备份文件的条目数据类型(03 号票自 BlockRuleTransfer.kt 迁入,旧格式独立导出的
 * Transfer/Serializer 与旧 schema 文件类已删):
 * - 浏览记录条目,三段备份 JSON 的 history 段条目;
 * - 屏蔽规则条目,三段备份 JSON 的 blockRules 段条目。
 *
 * 打包/解析([com.huanchengfly.tieba.post.utils.webdav.BackupJson])与判重合并
 * ([BackupMerge])共用同一份条目类型,本地备份与 WebDAV 备份文件逐条同构。
 *
 * 字段一律标注 [SerializedName]:Gson 按反射字段名读写 JSON,release 构建开了
 * R8 混淆(minifyEnabled=true),未注解的字段名会被重命名——自产自解析虽在同一
 * APK 内自洽,但跨版本/换机恢复旧备份时字段名对不上,备份 JSON 不再符合本文件
 * 记载的 schema。注解固定 JSON 键名,与混淆无关。
 */
data class BlockRuleData(
    @SerializedName("category") val category: Int = Block.CATEGORY_BLACK_LIST,
    @SerializedName("type") val type: Int = Block.TYPE_KEYWORD,
    @SerializedName("keywords") val keywords: List<String> = emptyList(),
    @SerializedName("username") val username: String? = null,
    @SerializedName("uid") val uid: String? = null,
    @SerializedName("isRegex") val isRegex: Boolean = false,
)

data class HistoryRecordData(
    @SerializedName("title") val title: String,
    @SerializedName("data") val data: String,
    @SerializedName("type") val type: Int,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("count") val count: Int,
    @SerializedName("extras") val extras: String? = null,
    @SerializedName("avatar") val avatar: String? = null,
    @SerializedName("username") val username: String? = null,
)

/** 浏览记录条目 → History 实体的映射(恢复合并入库用,字段一一对应) */
internal fun HistoryRecordData.toHistory(): History = History(
    title = title,
    data = data,
    type = type,
    timestamp = timestamp,
    count = count,
    extras = extras,
    avatar = avatar,
    username = username,
)

/** History 实体 → 浏览记录条目的映射(打包导出用,字段一一对应) */
internal fun History.toHistoryRecordData(): HistoryRecordData = HistoryRecordData(
    title = title,
    data = data,
    type = type,
    timestamp = timestamp,
    count = count,
    extras = extras,
    avatar = avatar,
    username = username,
)

/** Block 实体 → 屏蔽规则条目的映射(打包导出用,keywords 还原为列表) */
internal fun Block.toBlockRuleData(): BlockRuleData = BlockRuleData(
    category = category,
    type = type,
    keywords = getKeywords(),
    username = username,
    uid = uid,
    isRegex = isRegex,
)
