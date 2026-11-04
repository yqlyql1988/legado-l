package io.legado.app.data.entities

/**
 * 目录更新时用于把已下载正文迁移到新章节的文件名。
 * 只投影文件名需要的字段，避免读取完整章节对象。
 */
data class ChapterCacheKey(
    val url: String,
    val title: String,
    val index: Int,
)
