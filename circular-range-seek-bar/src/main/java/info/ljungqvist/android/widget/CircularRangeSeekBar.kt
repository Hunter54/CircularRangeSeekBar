/*
 * Copyright 2013 - 2018 Petter Ljungqvist
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("unused")

package info.ljungqvist.android.widget

import android.annotation.TargetApi
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.annotation.DrawableRes
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import mu.KLogging
import mu.KotlinLogging
import kotlin.math.*
import kotlin.properties.Delegates

class CircularRangeSeekBar : FrameLayout {

    constructor(context: Context)
            : super(context)

    @JvmOverloads
    constructor(context: Context, attributeSet: AttributeSet, defStyleAttr: Int = 0)
            : super(context, attributeSet, defStyleAttr)

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    constructor(context: Context, attributeSet: AttributeSet, defStyleAttr: Int, defStyleRes: Int)
            : super(context, attributeSet, defStyleAttr, defStyleRes)

    init {
        isClickable = true
    }

    var seekBarChangeListener: OnSeekChangeListener? = null

    // The color of the progress ring
    private val arcPaint: Paint = Paint()
        .apply {
            color = Color.parseColor("#ff33b5e5")
            isAntiAlias = true
            strokeWidth = 5f
            style = Paint.Style.STROKE
        }

    // The progress circle ring background
    private val circlePaint: Paint = Paint()
        .apply {
            color = Color.GRAY
            isAntiAlias = true
            strokeWidth = 30f
            style = Paint.Style.STROKE
        }

    private val thumb1: Thumb = Thumb(context) { x, y -> thumbTouch(true, x, y) }
    private val thumb2: Thumb = Thumb(context) { x, y -> thumbTouch(false, x, y) }
    private var thumbActive: Thumb? = null

    private var progress1 = 0
    private var progress2 = 0

    //used for setting how close the two thumbs can get to each other
    var minThumbDifference = 1

    // used to disable the second thumb selector
    var useOneThumb = false
        set(value) {
            field = value
            if (value)
                minThumbDifference = 0
        }

    var startAngle by Delegates.observable(125.0) { _, old, new ->
        if (old != new) {
            setProgressInternal(progress1, progress2, fromUser = false, forceChange = true)
        }
    }

    private var arcSpan = 360.0

    var endAngle = startAngle
        set(value) {
            field = value
            arcSpan = if (endAngle == startAngle) {
                360.0
            } else {
                (360 + endAngle - startAngle) % 360
            }
        }

    private var angle1 = startAngle
    private var angle2 = startAngle

    var maxProgress by uiProperty(100)

    private var size = -1
    private var thumbSize = ThumbSizes(-1, -1)
    private val arcRect = RectF()        // The rectangle containing our circles and arcs

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setImageResource(R.drawable.ic_rectangle_45)
    }

    private val ripple: NonChangingBoundsRippleDrawable? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            NonChangingBoundsRippleDrawable(
                ColorStateList(
                    arrayOf(intArrayOf()),
                    intArrayOf(Color.LTGRAY)
                ), null, null
            )
                .also { background = it }
        } else {
            null
        }

    fun setImageResource(@DrawableRes resId: Int) {
        thumb1.setImageResource(resId)
        thumb2.setImageResource(resId)

        thumb1.let {
            thumbSize = ThumbSizes(it.drawable.intrinsicWidth, it.drawable.intrinsicHeight)
        }
        updateRect()
        post { invalidate() }
    }

    fun setProgress(progress1: Int, progress2: Int = maxProgress-1) {
        setProgressInternal(progress1, progress2, fromUser = false, forceChange = false)
        thumb1.rotation = getThumbRotationAngle(progress1).toFloat()
        thumb2.rotation = getThumbRotationAngle(progress2).toFloat()
    }

    override fun onAttachedToWindow() {
        addView(thumb1)
        addView(thumb2)
        if (useOneThumb) {
            thumb2.visibility = View.GONE
        }
        super.onAttachedToWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {

        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val width: Int =
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                max(widthSize, heightSize)
            } else {
                widthSize
            }

        val height: Int =
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                max(widthSize, heightSize)
            } else {
                heightSize
            }

        val newSize = min(width, height)
        if (newSize != size) {
            size = newSize
            updateRect()
            invalidate()
        }
        val spec = MeasureSpec.makeMeasureSpec(newSize, MeasureSpec.EXACTLY)
        //MUST CALL THIS
        super.onMeasure(spec, spec)
    }

    override fun onDraw(canvas: Canvas) {
        val mid = size.toFloat() / 2
        val radius = mid - (thumbSize.height.toFloat() / 2)
        //Check endAngle, if endAngle is not equal with startAngle, draw an Arc
        if (arcSpan == 360.0) {
            canvas.drawCircle(mid, mid, radius, circlePaint)
        } else {
            canvas.drawArc(
                arcRect, startAngle.toFloat(),
                arcSpan.toFloat(), false, circlePaint
            )
        }
        if (!useOneThumb) {
            canvas.drawArc(
                arcRect,
                angle1.toFloat(),
                (angle2 - angle1).inDegrees().toFloat(),
                false,
                arcPaint
            )
        }

        setThumbCoordinates(thumb1, angle1)
        setThumbCoordinates(thumb2, angle2)

        super.onDraw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val mid = size.toFloat() / 2.0
                val innerR = mid - thumbSize.height
                val dx = event.x - mid
                val dy = event.y - mid
                val rSq = dx * dx + dy * dy
                logger.debug { "r = ${sqrt(rSq)}, outer = $mid, inner = $innerR" }
                if (rSq < mid * mid && rSq > innerR * innerR) {
                    isPressed = true
                    //Check which thumb we should move
                    if (sqDist(event.x, event.y, thumb1) <= sqDist(event.x, event.y, thumb2)) {
                        thumb1
                    } else {
                        if (useOneThumb) {
                            thumb1
                        } else {
                            thumb2
                        }
                    }
                        .also { thumbActive = it }
                        .internalOnTouchEvent(event)
                } else {
                    false
                }
            }
            MotionEvent.ACTION_MOVE ->
                thumbActive
                    ?.internalOnTouchEvent(event)
                    ?: false
            else -> {
                isPressed = false
                thumbActive
                    ?.also { thumbActive = null }
                    ?.internalOnTouchEvent(event)
                    ?: false
            }
        }

    private fun sqDist(x: Float, y: Float, thumb: Thumb): Float {
        val dx = thumb.x + thumbSize.width / 2 - x
        val dy = thumb.y + thumbSize.height / 2 - y
        return dx * dx + dy * dy
    }

    private fun setThumbCoordinates(thumb: Thumb, angle: Double) {
        val midX = (size - thumbSize.width).toFloat() / 2
        val midY = (size - thumbSize.height).toFloat() / 2
        val coordinateX = midX + (cos(Math.toRadians(angle)) * midX).toFloat()
        val coordinateY = midY + (sin(Math.toRadians(angle)) * midY).toFloat()
        thumb.setCoordinates(coordinateX, coordinateY, thumbSize)
    }

    private fun updateRect() {
        val upperLeft = thumbSize.height.toFloat() / 2
        val lowerRight = size.toFloat() - upperLeft
        arcRect.set(upperLeft, upperLeft, lowerRight, lowerRight)
    }


    private fun setProgressInternal(
        progress1: Int,
        progress2: Int,
        fromUser: Boolean,
        forceChange: Boolean
    ) {
        var changed = forceChange

        progress1
            .limitProgress()
            .takeUnless { it == this.progress1 }
            ?.let {
                this.progress1 = it
                changed = true
            }
        progress2
            .limitProgress()
            .takeUnless { it == this.progress2 }
            ?.let {
                this.progress2 = it
                changed = true
            }

        if (changed) {
            angle1 = getAngleFromProgress(progress1)
            angle2 = getAngleFromProgress(progress2)
            post {
                invalidate()
                seekBarChangeListener?.onProgressChange(this, progress1, progress2, fromUser)
            }
        }
    }

    private fun getAngleFromProgress(progress: Int): Double =
        (progress.toDouble() * arcSpan / maxProgress + startAngle).inDegrees()


    private tailrec fun Int.limitProgress(): Int = when {
        this < 0 -> (this + maxProgress).limitProgress()
        this >= maxProgress -> (this - maxProgress).limitProgress()
        else -> this
    }


    private tailrec fun Double.inDegrees(): Double = when {
        this < 0.0 -> (this + 360.0).inDegrees()
        this >= 360.0 -> (this - 360.0).inDegrees()
        else -> this
    }

    var indAngle = startAngle

    private fun getThumbRotationAngle(progress: Int): Double = getAngleFromProgress(progress) - 62

    private fun thumbTouch(isThumb1: Boolean, xIn: Float, yIn: Float) {
        val halfSize = size.toDouble() / 2.0
        val x = xIn.toDouble() - halfSize
        val y = yIn.toDouble() - halfSize

        val angle =
            (360.0 / 2.0 / Math.PI *
                    if (0.0 == x) {
                        if (y > 0) Math.PI / 2
                        else -Math.PI / 2
                    } else {
                        atan(y / x) + if (x >= 0) 0.0 else Math.PI
                    } -
                    startAngle)
                .inDegrees()

        //Stop pointer from going over missing ARC area
        if (angle > arcSpan) {
            return
        }

        val progress = (angle / arcSpan * maxProgress + .5).toInt()

        if (isThumb1) {
            if (progress > progress2 - minThumbDifference) {
                return
            }
            setProgressInternal(progress, progress2, fromUser = true, forceChange = false)
            thumb1.rotation = getThumbRotationAngle(progress).toFloat()
        } else {
            if (progress < progress1 + minThumbDifference || useOneThumb) {
                return
            }
            setProgressInternal(progress1, progress, fromUser = true, forceChange = false)
            thumb2.rotation = getThumbRotationAngle(progress).toFloat()
        }

//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//            val overflow = thumbSize / 2
//            val r = halfSize - thumbSize / 2
//            val a = (progress.toDouble() * arcSpan / progressMax + startAngle).inDegrees()
//            val activeX = (cos(Math.toRadians(a)) * r) + halfSize
//            val activeY = (sin(Math.toRadians(a)) * r) + halfSize
//            drawableHotspotChanged(activeX.toFloat(), activeY.toFloat())
//            ripple?.setBoundsInternal(
//                activeX.toInt() - overflow,
//                activeY.toInt() - overflow,
//                activeX.toInt() + overflow,
//                activeY.toInt() + overflow
//            )
//        }
    }

    private fun <T> uiProperty(value: T) = Delegates.observable(value) { _, old, new ->
        if (old != new) post { invalidate() }
    }

    companion object {

        @Suppress("FunctionName")
        fun OnSeekChangeListener(listener: (CircularRangeSeekBar, Int, Int, Boolean) -> Unit): OnSeekChangeListener =
            object : OnSeekChangeListener {
                override fun onProgressChange(
                    view: CircularRangeSeekBar,
                    progress1: Int,
                    progress2: Int,
                    fromUser: Boolean
                ) =
                    listener(view, progress1, progress2, fromUser)
            }

    }

    interface OnSeekChangeListener {
        fun onProgressChange(
            view: CircularRangeSeekBar,
            progress1: Int,
            progress2: Int,
            fromUser: Boolean
        )
    }

    private class Thumb(context: Context, val updateLocation: (x: Float, y: Float) -> Unit) :
        ImageView(context) {

        init {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            isClickable = true
            isFocusable = true
        }

        internal fun internalOnTouchEvent(event: MotionEvent): Boolean =
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    super.onTouchEvent(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    updateLocation(event.x, event.y)
                    true
                }
                else ->
                    super.onTouchEvent(event)
            }

        fun setCoordinates(left: Float, top: Float, thumbSize: ThumbSizes) {
            this.x = left
            this.y = top
        }


        override fun onTouchEvent(event: MotionEvent): Boolean = false

        private companion object : KLogging()

    }

    data class ThumbSizes(
        val width: Int,
        val height: Int
    )
}


private val logger = KotlinLogging.logger {}