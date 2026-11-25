package com.joel.litesail.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import android.widget.Button
import android.widget.FrameLayout
import com.joel.litesail.R
import com.joel.litesail.databinding.ViewBottomSheetBinding

internal open class BottomSheetDialog(
    context: Context,
    private val titleRes: Int,
    private val centerActions: Boolean = false
) : Dialog(context, R.style.Theme_LiteSail_BottomSheet) {
    private val binding by lazy { ViewBottomSheetBinding.inflate(layoutInflater) }
    private val hostWindow = (context as? Activity)?.window

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(titleRes)
        binding.sheetTitle.setText(titleRes)
        binding.closeSheet.setOnClickListener { dismiss() }
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(MATCH_PARENT, WRAP_CONTENT)
        }
    }

    override fun onStart() {
        super.onStart()
        hostWindow?.let { window?.setFlags(it.attributes.flags and FLAG_SECURE, FLAG_SECURE) }
    }

    protected fun setHeaderForeground(colors: ColorStateList) {
        binding.sheetTitle.setTextColor(colors.defaultColor)
        binding.closeSheet.imageTintList = colors
    }

    fun setBody(view: View) = with(binding.sheetBody) {
        removeAllViews()
        addView(view, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        visibility = View.VISIBLE
    }

    fun setHeaderDetail(text: CharSequence) = with(binding.headerDetail) {
        this.text = text
        visibility = View.VISIBLE
    }

    fun addAction(label: Int, dismissOnClick: Boolean = true, onClick: () -> Unit = {}) {
        val actions = binding.sheetActions
        if (centerActions) actions.gravity = Gravity.CENTER
        val button = layoutInflater.inflate(R.layout.item_bottom_sheet_action, actions, false) as Button
        button.setText(label)
        button.setOnClickListener {
            if (dismissOnClick) dismiss()
            onClick()
        }
        actions.addView(button)
        actions.visibility = View.VISIBLE
    }
}
