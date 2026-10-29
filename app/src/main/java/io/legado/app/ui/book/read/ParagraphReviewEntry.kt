package io.legado.app.ui.book.read

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.util.Base64

/**
 * 段落评论入口解析。
 *
 * 网页段评书源通常把段评地址放在章节 URL 的数据部分，或者在正文段尾插入一个点击脚本。
 * 阅读选中任意段落后，需要把书源自己的段落号换成选中段落，再交给书源脚本打开评论页。
 */
object ParagraphReviewEntry {

    private val reviewClickPattern = Regex(
        """(?i)\b(?:show\w*(?:cmt|comment|review)|showreadingbrowser|showdp|getdp)\s*\("""
    )

    private val clickNumberPattern = Regex("""(["'])(\d{1,6})\1""")

    private val clickCallPattern = Regex(
        """(?i)\b(?:show\w*(?:cmt|comment|review)|showreadingbrowser|showdp|getdp)\s*\(([^)]*)\)"""
    )

    private val clickArgumentPattern = Regex("""("[^"]*"|'[^']*'|[^,]+)""")

    private val paragraphIdPattern = Regex("""([?&])paragraph_id=[^&]*""")
    private val paraPattern = Regex("""([?&])para=[^&]*""")
    private val chapterTypePattern = Regex("""([?&])type=chapter(&?)""")

    /**
     * 是否是段评图标的点击脚本。
     */
    fun isReviewClick(click: String): Boolean {
        return reviewClickPattern.containsMatchIn(click)
    }

    /**
     * 把段评图标脚本中的书源段落号换成目标段落。
     *
     * 部分书源的正文段落从 `0` 开始，部分是 `1` 开始，因此同时匹配
     * [sourceParagraphNum] 和 `sourceParagraphNum - 1`，并按实际匹配值计算偏移。
     *
     * @param sourceParagraphNum 图标所在段落在阅读中的段落号
     * @param targetParagraphNum 目标段落在阅读中的段落号
     * @return 改写后的脚本，找不到段落号时返回 null
     */
    fun retargetClick(
        click: String,
        sourceParagraphNum: Int,
        targetParagraphNum: Int
    ): String? {
        if (!isReviewClick(click)) return null
        retargetPositionArgument(click, sourceParagraphNum, targetParagraphNum)?.let { return it }
        val matches = clickNumberPattern.findAll(click).toList()
        val match = matches.asReversed().firstOrNull {
            val value = it.groupValues[2].toIntOrNull()
            value == sourceParagraphNum || value == sourceParagraphNum - 1
        } ?: return null
        val sourceValue = match.groupValues[2].toInt()
        val targetValue = targetParagraphNum + (sourceValue - sourceParagraphNum)
        if (targetValue < 0) return null
        return click.substring(0, match.range.first) +
                "\"$targetValue\"" +
                click.substring(match.range.last + 1)
    }

    /**
     * 网页段评的点击脚本通常为 `showXxxCmt(bid, cid, para, date)`，第三个参数是书源段落号。
     * 书源段落号与阅读段落号的偏移会随标题、空行变化，按实际参数计算，不能固定减一。
     */
    private fun retargetPositionArgument(
        click: String,
        sourceParagraphNum: Int,
        targetParagraphNum: Int
    ): String? {
        val call = clickCallPattern.find(click) ?: return null
        val arguments = clickArgumentPattern.findAll(call.groupValues[1]).toList()
        if (arguments.size < 3) return null
        val paragraphArgument = arguments[2]
        val sourceValue = paragraphArgument.value.trim().trim('"', '\'').toIntOrNull()
            ?: return null
        val targetValue = targetParagraphNum + (sourceValue - sourceParagraphNum)
        if (targetValue < 0) return null
        val argumentStart = call.groups[1]!!.range.first + paragraphArgument.range.first
        val argumentEnd = call.groups[1]!!.range.first + paragraphArgument.range.last
        return click.substring(0, argumentStart) +
                "\"$targetValue\"" +
                click.substring(argumentEnd + 1)
    }

    /**
     * 在已排版的正文中查找可复用的段评点击脚本。
     *
     * @param candidates 每项是图标所在段落的段落号和点击脚本
     */
    fun findClickTemplate(
        candidates: Sequence<Pair<Int, String>>,
        targetParagraphNum: Int
    ): String? {
        if (targetParagraphNum <= 1) return null
        for ((sourceParagraphNum, click) in candidates) {
            if (sourceParagraphNum <= 1) continue
            retargetClick(click, sourceParagraphNum, targetParagraphNum)?.let { return it }
        }
        return null
    }

    /**
     * 从章节 URL 中读取书源的整章段评地址，并补上目标段落。
     *
     * 支持的章节 URL 形如 `data:chapterUrl;base64,<json>,{...}`，JSON 中保存
     * `commentUrl` 的书源可直接使用；其他书源返回 null。
     *
     * @param paragraphIndex 书源使用的段落序号（通常从 0 或 1 开始）
     */
    fun buildCommentUrl(chapterUrl: String, paragraphIndex: Int): String? {
        if (paragraphIndex < 0) return null
        val commentUrl = readCommentUrl(chapterUrl) ?: return null
        return withParagraphIndex(commentUrl, paragraphIndex)
    }

    internal fun readCommentUrl(chapterUrl: String): String? {
        val prefix = "data:chapterUrl;base64,"
        if (!chapterUrl.startsWith(prefix, ignoreCase = true)) return null
        val start = prefix.length
        val end = chapterUrl.indexOf(',', start)
        if (end <= start) return null
        val encoded = chapterUrl.substring(start, end)
        val json = runCatching {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val element = GSON.fromJsonObject<com.google.gson.JsonObject>(json).getOrNull()
            ?.get("commentUrl")
            ?: return null
        if (!element.isJsonPrimitive) return null
        return element.asString
            .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    internal fun withParagraphIndex(commentUrl: String, paragraphIndex: Int): String {
        val value = paragraphIndex.toString()
        if (paragraphIdPattern.containsMatchIn(commentUrl)) {
            return paragraphIdPattern.replace(commentUrl) {
                "${it.groupValues[1]}paragraph_id=$value"
            }
        }
        if (paraPattern.containsMatchIn(commentUrl)) {
            return paraPattern.replace(commentUrl) { "${it.groupValues[1]}para=$value" }
        }
        val withoutChapterType = chapterTypePattern.replace(commentUrl) { match ->
            if (match.groupValues[1] == "?") "?" else ""
        }
        if (withoutChapterType != commentUrl) {
            return appendQuery(withoutChapterType, "paragraph_id", value)
        }
        return appendQuery(commentUrl, "para", value)
    }

    private fun appendQuery(url: String, name: String, value: String): String {
        val separator = when {
            url.endsWith("?") || url.endsWith("&") -> ""
            url.contains('?') -> "&"
            else -> "?"
        }
        return "$url$separator$name=$value"
    }
}
