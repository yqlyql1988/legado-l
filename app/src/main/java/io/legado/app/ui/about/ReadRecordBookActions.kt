package io.legado.app.ui.about

import android.content.Context
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatDrawable
import io.legado.app.utils.getPrefString
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Both history and statistics continue from the bookshelf's current saved position. */
internal fun BaseActivity<*>.openRecordedBook(record: ReadRecordShow) {
    lifecycleScope.launch {
        val book = withContext(IO) {
            appDb.bookDao.findByName(record.bookName)
                .filter { it.author == record.author }
                .maxByOrNull { it.durChapterTime }
        }
        if (book == null) SearchActivity.start(this@openRecordedBook, record.bookName)
        else startActivityForBook(book)
    }
}

internal fun Context.loadReadRecordCover(image: ImageView, record: ReadRecordShow, book: Book?) {
    val placeholder = getCompatDrawable(R.drawable.read_record_cover_placeholder)
    val fallback = getPrefString(
        if (AppConfig.isNightTheme) PreferKey.readRecordCoverDark else PreferKey.readRecordCover,
    )
    val fallbackRequest = ImageLoader.load(this, fallback)
        .error(placeholder)
        .transform(CenterCrop(), RoundedCorners(4.dpToPx()))
    val cover = book?.getDisplayCover()?.takeIf { it.isNotBlank() } ?: record.coverUrl
    var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, AppConfig.loadCoverOnlyWifi)
    book?.getCoverSourceOrigin()?.let { options = options.set(OkHttpModelLoader.sourceOriginOption, it) }
    image.contentDescription = record.bookName
    ImageLoader.load(this, cover)
        .apply(options)
        .placeholder(placeholder)
        .error(ImageLoader.load(this, record.coverUrl)
            .apply(options)
            .error(fallbackRequest)
            .transform(CenterCrop(), RoundedCorners(4.dpToPx())))
        .transform(CenterCrop(), RoundedCorners(4.dpToPx()))
        .into(image)
}
