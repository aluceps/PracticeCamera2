package me.aluceps.practicecamera2

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView
import android.view.View

class CameraView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    fun setAspectRatio(width: Int, height: Int) {
        if (width < 0 || height < 0) {
            throw IllegalArgumentException("Size cannot be nagative.")
        }
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = View.MeasureSpec.getSize(heightMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(w, h)
        } else {
            if (w < h * ratioWidth / ratioHeight) {
                setMeasuredDimension(w, w * ratioHeight / ratioWidth)
            } else {
                setMeasuredDimension(h * ratioWidth / ratioHeight, h)
            }
        }
    }
}