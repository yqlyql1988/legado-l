package io.legado.app.ui.book.info.edit

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogBookTagEditBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.info.BookInfoViewModel
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class BookTagEditDialog() : BaseDialogFragment(R.layout.dialog_book_tag_edit) {

    constructor(bookUrl: String) : this() {
        arguments = Bundle().apply { putString("bookUrl", bookUrl) }
    }

    private val binding by viewBinding(DialogBookTagEditBinding::bind)
    private val viewModel by activityViewModels<BookInfoViewModel>()

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val book = viewModel.getBook(false)
        if (book == null || book.bookUrl != arguments?.getString("bookUrl") || !viewModel.inBookshelf) {
            dismiss()
            return
        }
        binding.toolBar.setBackgroundColor(primaryColor)
        val customTags = if (savedInstanceState?.containsKey("customTags") == true) {
            savedInstanceState.getString("customTags")
        } else {
            book.customTag
        }
        binding.tagEditor.collapseCommonTags = false
        binding.tagEditor.setBook(book, customTags)
        binding.tagEditor.loadCommonTags(viewLifecycleOwner)
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnOk.setOnClickListener {
            (activity as? Callback)?.onBookTagsEdited(book.bookUrl, binding.tagEditor.tags)
            dismiss()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("customTags", binding.tagEditor.tags)
        super.onSaveInstanceState(outState)
    }

    interface Callback {
        fun onBookTagsEdited(bookUrl: String, tags: String?)
    }
}
