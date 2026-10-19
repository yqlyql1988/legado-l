package io.legado.app.ui.highlight

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Menu
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.HighlightTag
import io.legado.app.databinding.DialogHighlightTagEditBinding
import io.legado.app.help.HighlightStyle
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.book.read.HighlightStyleDialog
import io.legado.app.ui.highlight.edit.HighlightRuleEditDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class HighlightTagEditDialog : BaseDialogFragment(R.layout.dialog_highlight_tag_edit),
    ColorPickerDialogListener, HighlightStyleDialog.StyleHost {
    interface Callback {
        fun saveTag(tag: HighlightTag): Boolean
        fun deleteTag(id: String)
    }

    private val binding by viewBinding(DialogHighlightTagEditBinding::bind)
    private var tag = HighlightTag()
    private var style = HighlightStyle(underline = HighlightStyle.Underline())
    private val callback get() = parentFragment as? Callback ?: activity as? Callback

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        tag = GSON.fromJsonObject<HighlightTag>(
            savedInstanceState?.getString("tag") ?: arguments?.getString("tag")
        ).getOrNull() ?: HighlightTag()
        style = tag.styleObj()
        binding.root.setBackgroundColor(requireContext().backgroundColor)
        binding.titleBar.title = getString(if (arguments?.getBoolean("new") == true)
            R.string.highlight_tag_add else R.string.highlight_tag_edit)
        binding.titleBar.toolbar.apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { dismiss() }
        }
        binding.titleBar.menu.add(Menu.NONE, R.id.btn_ok, Menu.NONE, R.string.action_save).apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                val name = binding.etName.text.toString().trim()
                val updated = tag.copy(name = name, note = binding.etNote.text.toString().trim(),
                    style = GSON.toJson(style.normalized()))
                if (name.isEmpty() || callback?.saveTag(updated) != true)
                    binding.etName.error = getString(R.string.highlight_tag_duplicate)
                else dismiss()
                true
            }
        }
        binding.etName.setText(tag.name)
        val bookmarkMode = arguments?.getBoolean("bookmarkMode") == true
        binding.btnStyle.isVisible = !bookmarkMode
        binding.tvPreview.isVisible = !bookmarkMode
        binding.etNote.setText(tag.note)
        binding.btnStyle.setOnClickListener {
            HighlightStyleDialog().show(childFragmentManager, STYLE_TAG)
        }
        (childFragmentManager.findFragmentByTag(COLOR_TAG) as? ColorPickerDialog)
            ?.setColorPickerDialogListener(this)
        binding.btnDelete.isVisible = arguments?.getBoolean("new") != true &&
            arguments?.getBoolean("removeFromBook") == true
        binding.btnDelete.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.highlight_tag_remove_book)
                .setMessage(getString(R.string.highlight_tag_remove_book_hint, tag.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.highlight_tag_remove_book) { _, _ ->
                    callback?.deleteTag(tag.id)
                    dismiss()
                }.show()
        }
        preview()
    }

    private fun preview() {
        binding.tvPreview.highlightStyle = style
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tag", GSON.toJson(tag.copy(
            name = binding.etName.text.toString(), note = binding.etNote.text.toString(),
            style = GSON.toJson(style))))
        super.onSaveInstanceState(outState)
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        style = HighlightStyleDialog.applyChannelColor(style, dialogId, color)
        (childFragmentManager.findFragmentByTag(STYLE_TAG) as? HighlightStyleDialog)?.refresh()
        preview()
    }

    override fun onDialogDismissed(dialogId: Int) = Unit

    override fun currentHighlightStyle(): HighlightStyle = style

    override fun onHighlightStyleChanged(style: HighlightStyle) {
        this.style = style.normalized()
        preview()
    }

    override fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean) {
        HighlightRuleEditDialog.createColorPickerDialog(dialogId, initial, withAlpha, this)
            .show(childFragmentManager, COLOR_TAG)
    }

    companion object {
        private const val STYLE_TAG = "highlight-tag-style"
        private const val COLOR_TAG = "highlight-tag-color"
        fun create(tag: HighlightTag, isNew: Boolean, bookUrl: String = "", removeFromBook: Boolean = false,
                   bookmarkMode: Boolean = false) = HighlightTagEditDialog().apply {
            arguments = Bundle().apply {
                putString("tag", GSON.toJson(tag))
                putBoolean("new", isNew)
                putString("bookUrl", bookUrl)
                putBoolean("removeFromBook", removeFromBook)
                putBoolean("bookmarkMode", bookmarkMode)
            }
        }
    }
}
