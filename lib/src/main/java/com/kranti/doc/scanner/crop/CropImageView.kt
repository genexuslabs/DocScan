package com.kranti.doc.scanner.crop

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import com.kranti.doc.scanner.view.PaperRectangle

class CropImageView  : ImageView {
    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle) { }
    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0) { }

    var paperRectangle: PaperRectangle? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paperRectangle?.onImageSizeChanged(w, h)
    }
}
