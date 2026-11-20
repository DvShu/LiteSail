package com.joel.litesail.search

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.joel.litesail.R

internal class ShortcutFlowLayout(context: Context, attrs: AttributeSet?) : ViewGroup(context, attrs) {
    private val gap = resources.getDimensionPixelSize(R.dimen.search_shortcut_gap)
    private val positions = mutableListOf<Rect>()

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (positions.size < childCount) positions.add(Rect())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val horizontalPadding = paddingLeft + paddingRight
        val availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (MeasureSpec.getSize(widthMeasureSpec) - horizontalPadding).coerceAtLeast(0)
        }

        var rowWidth = 0
        var rowHeight = 0
        var rowTop = paddingTop
        var widestRow = 0
        var childState = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (rowWidth > 0 && rowWidth + gap + childWidth > availableWidth) {
                rowTop += rowHeight
                rowWidth = 0
                rowHeight = 0
            }
            val start = if (rowWidth == 0) 0 else rowWidth + gap
            positions[index].set(start, rowTop, start + childWidth, rowTop + childHeight)
            rowWidth = start + childWidth
            rowHeight = maxOf(rowHeight, childHeight)
            widestRow = maxOf(widestRow, rowWidth)
            childState = combineMeasuredStates(childState, child.measuredState)
        }

        setMeasuredDimension(
            resolveSizeAndState(maxOf(suggestedMinimumWidth, widestRow + horizontalPadding), widthMeasureSpec, childState),
            resolveSizeAndState(
                maxOf(suggestedMinimumHeight, rowTop + rowHeight + paddingBottom),
                heightMeasureSpec, childState shl MEASURED_HEIGHT_STATE_SHIFT
            )
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val start = if (rtl) width - paddingStart else paddingStart
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val position = positions[index]
            val childLeft = if (rtl) start - position.right else start + position.left
            child.layout(childLeft, position.top, childLeft + child.measuredWidth, position.bottom)
        }
    }
}
