package io.legado.app.ui.book.toc

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceBook
import io.legado.app.databinding.ItemChapterListBinding
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.NaturalProgressSnapshot
import io.legado.app.help.book.naturalProgressSnapshot
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.ThemeUtils
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.AudioCacheKey
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class ChapterListAdapter(context: Context, val callback: Callback) :
    DiffRecyclerAdapter<TocListItem, ItemChapterListBinding>(context) {

    val cacheFileNames = hashSetOf<String>()
    val audioCacheKeys = hashSetOf<AudioCacheKey>()
    @Volatile
    private var displayTitleMap = ConcurrentHashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())
    private val baseStartPadding = 12.dpToPx()
    private val depthIndent = 10.dpToPx()
    private var released = false
    private var naturalProgressBookUrl: String? = null
    private var naturalProgressRanges: String? = null
    private var naturalProgressChapters: String? = null
    private var naturalProgressInitialized = false
    private var naturalProgressSnapshot: NaturalProgressSnapshot? = null

    override val diffItemCallback: DiffUtil.ItemCallback<TocListItem>
        get() = object : DiffUtil.ItemCallback<TocListItem>() {

            override fun areItemsTheSame(
                oldItem: TocListItem,
                newItem: TocListItem,
            ): Boolean {
                return oldItem.key == newItem.key
            }

            override fun areContentsTheSame(
                oldItem: TocListItem,
                newItem: TocListItem,
            ): Boolean {
                if (oldItem::class != newItem::class || oldItem.depth != newItem.depth) {
                    return false
                }
                if (!sameChapterContent(oldItem.chapter, newItem.chapter) ||
                    oldItem.readingChapter?.index != newItem.readingChapter?.index) return false
                return when {
                    oldItem is TocListItem.Volume && newItem is TocListItem.Volume ->
                        oldItem.collapsed == newItem.collapsed &&
                                oldItem.chapterCount == newItem.chapterCount &&
                                oldItem.matchedCount == newItem.matchedCount &&
                                oldItem.matchedSelf == newItem.matchedSelf &&
                                oldItem.containsCurrentChapter == newItem.containsCurrentChapter

                    oldItem is TocListItem.Chapter && newItem is TocListItem.Chapter ->
                        oldItem.parentVolumeIndex == newItem.parentVolumeIndex

                    else -> false
                }
            }
        }

    private fun sameChapterContent(oldItem: BookChapter, newItem: BookChapter): Boolean {
        return oldItem.bookUrl == newItem.bookUrl &&
                oldItem.url == newItem.url &&
                oldItem.isVip == newItem.isVip &&
                oldItem.isPay == newItem.isPay &&
                oldItem.title == newItem.title &&
                oldItem.tag == newItem.tag &&
                oldItem.wordCount == newItem.wordCount &&
                oldItem.isVolume == newItem.isVolume
    }

    private var upDisplayTitleJob: Coroutine<*>? = null

    override fun onCurrentListChanged() {
        super.onCurrentListChanged()
        if (released) return
        callback.onListChanged()
        callback.onItemsUpdated()
    }

    fun attach() {
        released = false
    }

    fun release() {
        released = true
        upDisplayTitleJob?.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    fun clearDisplayTitle() {
        upDisplayTitleJob?.cancel()
        displayTitleMap = ConcurrentHashMap()
    }

    fun upDisplayTitles(startIndex: Int) {
        if (released) return
        upDisplayTitleJob?.cancel()
        val displayTitleMap = displayTitleMap
        upDisplayTitleJob = Coroutine.async(callback.scope) {
            val book = callback.book ?: return@async
            val items = getItems()
            if (items.isEmpty()) return@async
            val safeStartIndex = startIndex.coerceIn(0, items.lastIndex)
            val replaceRules = ContentProcessor.get(book.name, book.origin).getTitleReplaceRules()
            val replaceBook = book.toReplaceBook()
            val useReplace = AppConfig.tocUiUseReplace && book.getUseReplaceRule()
            launch {
                for (i in safeStartIndex until items.size) {
                    updateDisplayTitle(
                        items, i, replaceRules, useReplace, replaceBook, displayTitleMap
                    )
                }
            }
            launch {
                for (i in safeStartIndex - 1 downTo 0) {
                    updateDisplayTitle(
                        items, i, replaceRules, useReplace, replaceBook, displayTitleMap
                    )
                }
            }
        }
    }

    private suspend fun updateDisplayTitle(
        items: List<TocListItem>,
        index: Int,
        replaceRules: List<io.legado.app.data.entities.ReplaceRule>,
        useReplace: Boolean,
        replaceBook: ReplaceBook,
        displayTitleMap: ConcurrentHashMap<String, String>,
    ) {
        val item = items[index]
        val chapter = item.readingChapter?.let { reading ->
            if (reading.index == item.chapter.index) item.chapter
            else item.chapter.copy(index = reading.index)
        } ?: item.chapter
        if (displayTitleMap[item.key] != null) return
        currentCoroutineContext().ensureActive()
        val displayTitle = chapter.getDisplayTitle(
            replaceRules,
            useReplace,
            replaceBook = replaceBook,
        )
        currentCoroutineContext().ensureActive()
        displayTitleMap[item.key] = displayTitle
        handler.post {
            if (displayTitleMap === this.displayTitleMap &&
                !released && getItem(index)?.key == item.key
            ) {
                notifyItemChanged(index, true)
            }
        }
    }

    private fun getDisplayTitle(item: TocListItem): String {
        return displayTitleMap[item.key] ?: item.chapter.title
    }

    private fun naturalProgress(book: Book?): NaturalProgressSnapshot? {
        val config = book?.readConfig
        if (book == null || config == null) return null
        if (book.bookUrl != naturalProgressBookUrl ||
            config.naturalProgressCompletedRanges !== naturalProgressRanges ||
            config.naturalProgressPartialChapters !== naturalProgressChapters ||
            config.naturalProgressInitialized != naturalProgressInitialized
        ) {
            naturalProgressBookUrl = book.bookUrl
            naturalProgressRanges = config.naturalProgressCompletedRanges
            naturalProgressChapters = config.naturalProgressPartialChapters
            naturalProgressInitialized = config.naturalProgressInitialized
            naturalProgressSnapshot = book.naturalProgressSnapshot()
        }
        return naturalProgressSnapshot
    }

    private fun chapterProgress(book: Book?, chapterIndex: Int): Float {
        return naturalProgress(book)?.chapterProgress(chapterIndex) ?: 0f
    }

    private fun volumeProgress(book: Book?, volumeIndex: Int): Float {
        val chapterIndexes = callback.chapterIndexesInVolume(volumeIndex)
        if (chapterIndexes.isEmpty()) return 0f
        var total = 0f
        chapterIndexes.forEach { total += chapterProgress(book, it) }
        return (total / chapterIndexes.size).coerceIn(0f, 1f)
    }

    override fun getViewBinding(parent: ViewGroup): ItemChapterListBinding {
        return ItemChapterListBinding.inflate(inflater, parent, false).apply {
            tvChapterItem.foreground = ChapterProgressDrawable(
                color = context.accentColor,
                horizontalInset = 12.dpToPx(),
                lineHeight = 2.dpToPx(),
            )
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemChapterListBinding,
        item: TocListItem,
        payloads: MutableList<Any>,
    ) {
        binding.run {
            val chapter = item.chapter
            val isVolume = item is TocListItem.Volume
            val isCurrentChapter = callback.durChapterIndex() == item.readingChapter?.index
            val cached = callback.isLocalBook || isVolume ||
                    if (callback.isAudioBook) {
                        !callback.isAudioCacheStateReady ||
                                audioCacheKeys.contains(AudioCacheKey.from(chapter))
                    } else {
                        cacheFileNames.contains(chapter.getFileName())
                    }
            tvChapterName.text = getDisplayTitle(item)
            val progress = if (isVolume) {
                volumeProgress(callback.progressBook, chapter.index)
            } else {
                item.readingChapter?.index
                    ?.takeUnless { it < 0 }
                    ?.let { chapterProgress(callback.progressBook, it) }
                    ?: 0f
            }
            (tvChapterItem.foreground as? ChapterProgressDrawable)?.setFraction(progress)
            val progressText = if (isVolume || progress <= 0f) {
                null
            } else {
                "${(progress * 100).roundToInt().coerceAtLeast(1)}%"
            }
            if (progressText != null) {
                tvNaturalProgress.text = progressText
                tvNaturalProgress.visible()
            } else {
                tvNaturalProgress.gone()
            }

            if (payloads.isEmpty()) {
                val isCurrentGroup = isCurrentChapter ||
                        (item is TocListItem.Volume && item.containsCurrentChapter)
                tvChapterItem.updatePaddingRelative(
                    start = baseStartPadding + item.depth * depthIndent
                )
                if (isCurrentGroup) {
                    tvChapterName.setTextColor(context.accentColor)
                } else {
                    tvChapterName.setTextColor(context.getCompatColor(R.color.primaryText))
                }
                if (isVolume) {
                    tvChapterItem.setBackgroundColor(context.getCompatColor(R.color.btn_bg_press))
                } else {
                    tvChapterItem.background =
                        ThemeUtils.resolveDrawable(context, android.R.attr.selectableItemBackground)
                }

                if (item is TocListItem.Volume) {
                    tvWordCount.gone()
                    tvTag.text = volumeSummary(item)
                    tvTag.visible()
                } else {
                    if (!chapter.tag.isNullOrEmpty()) {
                        tvTag.text = chapter.tag
                        tvTag.visible()
                    } else {
                        tvTag.gone()
                    }
                    if (AppConfig.tocCountWords && !chapter.wordCount.isNullOrEmpty()) {
                        tvWordCount.text = chapter.wordCount
                        tvWordCount.visible()
                    } else {
                        tvWordCount.gone()
                    }
                }

                if (chapter.isVip && !chapter.isPay && !isVolume) {
                    ivLocked.visible()
                } else {
                    ivLocked.gone()
                }

                if (item is TocListItem.Volume) {
                    ivChecked.gone()
                    if (item.canToggle) {
                        ivVolumeArrow.setImageResource(
                            if (item.collapsed) R.drawable.ic_arrow_right
                            else R.drawable.ic_expand_more
                        )
                        ivVolumeArrow.visible()
                        endActions.contentDescription = context.getString(
                            if (item.collapsed) R.string.toc_expand_volume
                            else R.string.toc_collapse_volume
                        )
                        endActions.isFocusable = true
                        endActions.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    } else {
                        ivVolumeArrow.gone()
                        endActions.contentDescription = null
                        endActions.isFocusable = false
                        endActions.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                } else {
                    ivVolumeArrow.gone()
                    endActions.contentDescription = null
                    endActions.isFocusable = false
                    endActions.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                expandEndActionTouchTarget(binding)
            }
            if (!isVolume) upHasCache(binding, isCurrentChapter, cached)
        }
    }

    private fun volumeSummary(item: TocListItem.Volume): String {
        val summary = if (item.matchedCount == null) {
            context.getString(R.string.all_chapter_num, item.chapterCount)
        } else if (item.matchedSelf && item.matchedCount == 0) {
            context.getString(R.string.toc_volume_title_match)
        } else {
            context.getString(
                R.string.toc_search_match_count,
                item.matchedCount,
                item.chapterCount,
            )
        }
        return item.chapter.tag
            ?.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.toc_volume_tag_summary, it, summary) }
            ?: summary
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemChapterListBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let { item ->
                val readingChapter = item.readingChapter
                if (readingChapter != null) callback.openChapter(readingChapter)
                else if (item is TocListItem.Volume && item.canToggle) callback.onVolumeToggled(item.chapter.index)
            }
        }
        binding.endActions.setOnClickListener {
            getItem(holder.layoutPosition)?.let { item ->
                if (item is TocListItem.Volume && item.canToggle) {
                    callback.onVolumeToggled(item.chapter.index)
                } else {
                    item.readingChapter?.let(callback::openChapter)
                }
            }
        }
        holder.itemView.setOnLongClickListener {
            getItem(holder.layoutPosition)?.let { item ->
                context.longToastOnUi(getDisplayTitle(item))
            }
            true
        }
    }

    private fun expandEndActionTouchTarget(binding: ItemChapterListBinding) {
        binding.tvChapterItem.post {
            val bounds = Rect()
            binding.endActions.getHitRect(bounds)
            val inset = 12.dpToPx()
            bounds.inset(-inset, -inset)
            binding.tvChapterItem.touchDelegate = TouchDelegate(bounds, binding.endActions)
        }
    }

    fun findVisiblePositionByChapterIndex(chapterIndex: Int): Int {
        return getItems().indexOfFirst {
            it is TocListItem.Chapter && it.readingChapter?.index == chapterIndex
        }
    }

    fun findVisiblePositionByAudioCacheKey(key: AudioCacheKey): Int {
        return getItems().indexOfFirst {
            it is TocListItem.Chapter && AudioCacheKey.from(it.chapter) == key
        }
    }

    fun findVisiblePositionByItemKey(key: String): Int {
        return getItems().indexOfFirst { it.key == key }
    }

    private fun upHasCache(binding: ItemChapterListBinding, isCurrent: Boolean, cached: Boolean) =
        binding.apply {
            ivChecked.setImageResource(R.drawable.ic_outline_cloud_24)
            ivChecked.visible(!cached)
            if (isCurrent) {
                ivChecked.setImageResource(R.drawable.ic_check)
                ivChecked.visible()
            }
        }

    interface Callback {
        val scope: CoroutineScope
        val book: Book?
        /** 含最近一次自然翻页数据的书籍；阅读器打开目录时用它渲染进度线。 */
        val progressBook: Book?
            get() = book
        val isLocalBook: Boolean
        val isAudioBook: Boolean
        val isAudioCacheStateReady: Boolean
        fun openChapter(bookChapter: BookChapter)
        fun durChapterIndex(): Int
        fun onListChanged()
        fun onVolumeToggled(volumeIndex: Int)
        fun onItemsUpdated()
        fun chapterIndexesInVolume(volumeIndex: Int): List<Int>
    }
}
