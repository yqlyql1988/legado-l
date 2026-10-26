package io.legado.app.ui.book.info.edit

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookTagStat
import io.legado.app.data.entities.aggregateBookTagStats
import io.legado.app.data.entities.bookTags
import io.legado.app.data.entities.normalizeBookTags
import io.legado.app.data.entities.sourceTags
import io.legado.app.data.entities.userTags
import io.legado.app.databinding.ActivityBookInfoEditBinding
import io.legado.app.help.book.addType
import io.legado.app.help.book.hasEditedNetworkCover
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BookMetadataSync
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.LabelStyle
import io.legado.app.ui.widget.StyledLabel
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.readUri
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.views.bottomPadding
import java.io.FileOutputStream
import java.util.Locale

class BookInfoEditActivity :
    VMBaseActivity<ActivityBookInfoEditBinding, BookInfoEditViewModel>(),
    ChangeCoverDialog.CallBack {

    private val selectCover = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            coverChangeTo(uri)
        }
    }

    override val binding by viewBinding(ActivityBookInfoEditBinding::inflate)
    override val viewModel by viewModels<BookInfoEditViewModel>()
    private var commonUserTags = emptyList<BookTagStat>()
    private var currentSourceTags = emptyList<String>()
    private var commonTagsExpanded = false
    private var commonTagsRenderVersion = 0

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.bookData.observe(this) { upView(it) }
        if (viewModel.bookData.value == null) {
            intent.getStringExtra("bookUrl")?.let {
                viewModel.loadBook(it)
            }
        }
        initView()
        initEvent()
        loadCommonTags()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_info_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> saveData()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        binding.root.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            val insets = windowInsets.getInsets(typeMask)
            view.bottomPadding = insets.bottom
            windowInsets
        }
    }

    private fun initEvent() = binding.run {
        tieCommonTagSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                commonTagsExpanded = false
                renderCommonTags(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        tvCommonTagAdd.setOnClickListener {
            binding.tieCommonTagSearch.text?.toString()?.bookTags()?.singleOrNull()?.let {
                appendTag(it)
                binding.tieCommonTagSearch.text?.clear()
            }
        }
        tvCommonTagExpand.setOnClickListener {
            commonTagsExpanded = !commonTagsExpanded
            renderCommonTags()
        }
        tvChangeCover.setOnClickListener {
            viewModel.bookData.value?.let {
                showDialogFragment(
                    ChangeCoverDialog(it.name, it.author)
                )
            }
        }
        tvSelectCover.setOnClickListener {
            selectCover.launch {
                mode = HandleFileContract.IMAGE
            }
        }
        tvRefreshCover.setOnClickListener {
            viewModel.book?.customCoverUrl = tieCoverUrl.text?.toString()
            viewModel.book?.persistedCoverUrl = null
            upCover()
        }
    }

    private fun loadCommonTags() {
        lifecycleScope.launch {
            commonUserTags = withContext(IO) {
                aggregateBookTagStats(
                    appDb.bookDao.flowShelfTagBooks().first(),
                    sourceTags = false,
                )
            }
            renderCommonTags()
        }
    }

    private fun renderCommonTags(query: String = binding.tieCommonTagSearch.text?.toString().orEmpty()) {
        val labels = (
            currentSourceTags.map { StyledLabel(it, LabelStyle.FILLED) } +
                commonUserTags.map { StyledLabel(it.name, LabelStyle.TONAL) }
            )
            .distinctBy { it.text.lowercase(Locale.ROOT) }
            .filter { query.isBlank() || it.text.contains(query.trim(), ignoreCase = true) }
        val newTag = query.bookTags().singleOrNull()
        binding.tvCommonTagAdd.apply {
            isVisible = newTag != null && labels.none {
                it.text.equals(newTag, ignoreCase = true)
            }
            text = newTag?.let { getString(R.string.book_tag_existing_add, it) }
        }

        val renderVersion = ++commonTagsRenderVersion
        binding.lbCommonTag.maxLine = -1
        binding.lbCommonTag.setStyledLabels(labels, onClick = {
            appendTag(it)
            binding.tieCommonTagSearch.text?.clear()
        })
        binding.lbCommonTag.doOnNextLayout {
            if (renderVersion != commonTagsRenderVersion) return@doOnNextLayout
            val visibleTagCount = binding.lbCommonTag.flexLines
                .take(3)
                .sumOf { it.itemCount }
            val hiddenTagCount = (labels.size - visibleTagCount).coerceAtLeast(0)
            if (hiddenTagCount == 0) commonTagsExpanded = false
            binding.tvCommonTagExpand.apply {
                isVisible = hiddenTagCount > 0
                text = if (commonTagsExpanded) {
                    getString(R.string.book_tag_existing_collapse)
                } else {
                    getString(R.string.book_tag_existing_expand, hiddenTagCount)
                }
            }
            binding.lbCommonTag.maxLine = if (commonTagsExpanded) -1 else 3
        }
    }

    private fun appendTag(tag: String) {
        binding.tieCustomTag.run {
            val tags = text?.toString().bookTags()
            if (tags.any { it.equals(tag, ignoreCase = true) }) return
            setText(normalizeBookTags(tags + tag))
            setSelection(text?.length ?: 0)
        }
    }

    private fun upView(book: Book) = binding.run {
        currentSourceTags = book.sourceTags()
        renderCommonTags()
        tieBookName.setText(book.name)
        tieBookAuthor.setText(book.author)
        spType.setSelection(
            when {
                book.isVideo -> 4
                book.isImage -> 2
                book.isAudio -> 1
                else -> 0
            }
        )
        tieCustomTag.setText(book.userTags().joinToString(","))
        tieCoverUrl.setText(book.customCoverUrl?.takeIf { it.isNotEmpty() } ?: book.coverUrl)
        tieBookIntro.setText(book.getDisplayIntro())
        upCover()
    }

    private fun upCover() {
        viewModel.book?.let {
            binding.ivCover.load(it, false)
        }
    }

    private fun saveData() = binding.run {
        if (viewModel.isSaving) return@run
        val oldBook = viewModel.book ?: return@run
        val book = oldBook.copy()
        book.name = tieBookName.text?.toString() ?: ""
        book.author = tieBookAuthor.text?.toString() ?: ""
        val local = if (book.isLocal) BookType.local else 0
        val bookType = when (spType.selectedItemPosition) {
            4 -> BookType.video or local
            2 -> BookType.image or local
            1 -> BookType.audio or local
            else -> BookType.text or local
        }
        book.removeType(BookType.video, BookType.local, BookType.image, BookType.audio, BookType.text)
        book.addType(bookType)
        val customCoverUrl = tieCoverUrl.text?.toString()?.takeIf { it.isNotEmpty() }
        if (hasEditedNetworkCover(customCoverUrl, book.customCoverUrl, book.coverUrl)) {
            book.persistedCoverUrl = null
        }
        book.customCoverUrl = if (customCoverUrl == book.coverUrl) null else customCoverUrl
        val customIntro = tieBookIntro.text?.toString()
        book.customIntro = if (customIntro == book.intro) null else customIntro
        book.customTag = normalizeBookTags(tieCustomTag.text?.toString())
        val metadataChanged = oldBook.name != book.name || oldBook.author != book.author
        if (!metadataChanged) {
            saveBook(book, false)
        } else {
            when (AppConfig.bookMetadataSync) {
                BookMetadataSync.SYNC -> saveBook(book, true)
                BookMetadataSync.NEVER -> saveBook(book, false)
                BookMetadataSync.ASK -> alert(
                    R.string.book_metadata_sync_title,
                    R.string.book_metadata_sync_prompt,
                ) {
                    positiveButton(R.string.book_metadata_sync_yes) { saveBook(book, true) }
                    negativeButton(R.string.book_metadata_sync_no) { saveBook(book, false) }
                    neutralButton(R.string.cancel)
                }
            }
        }
    }

    private fun saveBook(book: Book, syncMetadata: Boolean) {
        viewModel.saveBook(book, syncMetadata) {
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun coverChangeTo(coverUrl: String) {
        viewModel.book?.customCoverUrl = coverUrl
        viewModel.book?.persistedCoverUrl = null
        binding.tieCoverUrl.setText(coverUrl)
        upCover()
    }

    private fun coverChangeTo(uri: Uri) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            coverChangeTo(uri.toString())
            return
        }
        readUri(uri) { fileDoc, inputStream ->
            runCatching {
                inputStream.use {
                    var file = this.externalFiles
                    val suffix = if (fileDoc.name.contains(".9.png", true)) {
                        ".9.png"
                    } else {
                        "." + fileDoc.name.substringAfterLast(".")
                    }
                    val fileName = uri.inputStream(this).getOrThrow().use {
                        MD5Utils.md5Encode(it) + suffix
                    }
                    file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    coverChangeTo(file.absolutePath)
                }
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

}
