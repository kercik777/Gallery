package com.premiumlab.galleryx.ui.view;

import android.content.Context;
import android.util.AttributeSet;

import androidx.cardview.widget.CardView;

/**
 * CardView, которая всегда квадратная: высота = ширине.
 * Так ячейки сетки остаются ровными квадратами при любом числе колонок.
 */
public class SquareCardView extends CardView {

    public SquareCardView(Context context) {
        super(context);
    }

    public SquareCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SquareCardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int square = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        super.onMeasure(square, square);
    }
}
