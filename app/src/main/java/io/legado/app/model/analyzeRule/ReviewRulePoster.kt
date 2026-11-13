package io.legado.app.model.analyzeRule

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlin.coroutines.coroutineContext

/**
 * 常规书源（非 JS 书源）的段评/章评发送。
 *
 * 规则说明：
 * - [io.legado.app.data.entities.rule.ReviewRule.postReviewUrl] 发送新的段评或章评；
 * - [io.legado.app.data.entities.rule.ReviewRule.postQuoteUrl] 回复已有段评；
 * - 两条规则都返回完整请求地址，可以用 `,{"method":"POST","body":"..."}` 附带请求体和请求头；
 * - JS 规则中可直接使用 `content`、`paraIndex`、`paraData`、`reviewId`、`page`。
 */
object ReviewRulePoster {

    suspend fun postReview(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
        paragraphIndex: Int,
        paragraphData: String,
        content: String,
    ): Boolean {
        return post(
            source = source,
            book = book,
            chapter = chapter,
            paragraphIndex = paragraphIndex,
            paragraphData = paragraphData,
            content = content,
            reviewId = null,
        )
    }

    private suspend fun post(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
        paragraphIndex: Int,
        paragraphData: String,
        content: String,
        reviewId: String?,
    ): Boolean {
        val rule = source.ruleReview ?: throw NoStackTraceException("段评规则未配置")
        val urlRule = if (reviewId.isNullOrBlank()) {
            rule.postReviewUrl
        } else {
            rule.postQuoteUrl
        }?.takeIf { it.isNotBlank() } ?: throw NoStackTraceException(
            if (reviewId.isNullOrBlank()) "段评发送规则未配置" else "段评回复规则未配置"
        )
        val analyzeUrl = AnalyzeUrl(
            mUrl = urlRule,
            baseUrl = chapter.url,
            source = source,
            ruleData = book,
            chapter = chapter,
            coroutineContext = coroutineContext,
            extraParams = mapOf(
                "content" to content,
                "paraIndex" to paragraphIndex.toString(),
                "paraData" to paragraphData,
                "reviewId" to reviewId.orEmpty(),
                "page" to "1",
            ),
        )
        val body = analyzeUrl.getStrResponseAwait(useWebView = true).body.orEmpty()
        if (body.isBlank()) {
            throw NoStackTraceException("发送失败：接口没有返回结果")
        }
        // 起点等站点在未登录或被拦截时返回网页，不能当成发送成功。
        if (body.trimStart().startsWith("<")) {
            throw NoStackTraceException("发送失败：接口返回网页而不是结果，请确认已登录")
        }
        // 返回 JSON 且带 code 的接口按 code 判断；其他书源返回纯文本或无 code 时保持原判断，避免误伤。
        val json = GSON.fromJsonObject<Map<String, Any?>>(body).getOrNull() ?: return true
        val code = (json["code"] as? Number)?.toInt() ?: return true
        if (code == 0) return true
        val message = json["msg"]?.toString().orEmpty()
        throw NoStackTraceException(
            message.ifBlank { "发送失败（code=$code）" }
        )
    }

}
