package io.legado.app.ui.highlight

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.HighlightTag
import io.legado.app.databinding.DialogHighlightTagEditBinding
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.book.read.UnderlineEditDialog
import io.legado.app.ui.highlight.edit.HighlightRuleEditDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class HighlightTagEditDialog : BaseDialogFragment(R.layout.dialog_highlight_tag_edit),
    ColorPickerDialogListener, UnderlineEditDialog.Callback {
    interface Callback {
        fun saveTag(tag: HighlightTag): Boolean
        fun deleteTag(id: String)
    }

    private val binding by viewBinding(DialogHighlightTagEditBinding::bind)
    private var tag = HighlightTag()
    private var style = HighlightStyle(underline = HighlightStyle.Underline())
    private val underline get() = style.underline ?: HighlightStyle.Underline()

    override fun onStart() {
        super.onStart()
        setLayout(0.95f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        tag = GSON.fromJsonObject<HighlightTag>(
            savedInstanceState?.getString("tag") ?: arguments?.getString("tag")
        ).getOrNull() ?: HighlightTag()
        style = tag.styleObj()
        binding.tvTitle.setText(if (arguments?.getBoolean("new") == true)
            R.string.highlight_tag_add else R.string.highlight_tag_edit)
        binding.etName.setText(tag.name)
        binding.spKind.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            HighlightStyle.Kind.entries.map { getString(it.labelRes()) })
        binding.spKind.setSelection(underline.kind.ordinal)
        binding.spKind.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, item: View?, position: Int, id: Long) {
                style = style.copy(underline = underline.copy(kind = HighlightStyle.Kind.entries[position]))
                preview()
            }
        }
        binding.btnColor.setOnClickListener {
            HighlightRuleEditDialog.createColorPickerDialog(
                COLOR_ID, underline.color, false, this
            ).show(childFragmentManager, COLOR_TAG)
        }
        (childFragmentManager.findFragmentByTag(COLOR_TAG) as? ColorPickerDialog)
            ?.setColorPickerDialogListener(this)
        binding.btnWidth.setOnClickListener { UnderlineEditDialog.show(childFragmentManager, underline) }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnOk.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            val updated = tag.copy(name = name, style = GSON.toJson(style.normalized()))
            if (name.isEmpty() || (activity as? Callback)?.saveTag(updated) != true) {
                binding.etName.error = getString(R.string.highlight_tag_duplicate)
            } else dismiss()
        }
        binding.btnDelete.isVisible = arguments?.getBoolean("new") != true
        binding.btnDelete.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.highlight_tag_delete_title)
                .setMessage(getString(R.string.highlight_tag_delete_hint, tag.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    (activity as? Callback)?.deleteTag(tag.id)
                    dismiss()
                }.show()
        }
        preview()
    }

    private fun preview() {
        binding.tvPreview.highlightStyle = style
        binding.btnColor.setTextColor(underline.color.takeIf { it != 0 } ?: binding.tvPreview.currentTextColor)
        binding.btnWidth.text = getString(R.string.highlight_tag_style_description,
            getString(underline.kind.labelRes()), underline.width, underline.distance)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tag", GSON.toJson(tag.copy(
            name = binding.etName.text.toString(), style = GSON.toJson(style))))
        super.onSaveInstanceState(outState)
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        style = style.copy(underline = underline.copy(color = color))
        preview()
    }

    override fun onDialogDismissed(dialogId: Int) = Unit

    override fun onUnderlineChanged(underline: HighlightStyle.Underline) {
        style = style.copy(underline = underline)
        preview()
    }

    companion object {
        private const val COLOR_ID = 8401
        private const val COLOR_TAG = "highlight-tag-color"
        fun create(tag: HighlightTag, isNew: Boolean) = HighlightTagEditDialog().apply {
            arguments = Bundle().apply {
                putString("tag", GSON.toJson(tag))
                putBoolean("new", isNew)
            }
        }
    }
}
