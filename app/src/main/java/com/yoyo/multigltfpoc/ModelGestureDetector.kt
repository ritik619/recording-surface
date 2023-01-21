package com.yoyo.multigltfpoc

import android.view.MotionEvent
import android.view.View
import com.google.android.filament.utils.Float2
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.distance
import com.google.android.filament.utils.mix

/**
 * Responds to Android touch events and manages a camera manipulator.
 * Supports one-touch orbit, and pinch-to-zoom.
 */
class ModelGestureDetector(private val view: View, private val manipulator: Manipulator) {
    private enum class Gesture { NONE, ORBIT, PAN, ZOOM }

    // Simplified memento of MotionEvent, minimal but sufficient for our purposes.
    private data class TouchPair(var pt0: Float2, var pt1: Float2, var count: Int) {
        constructor() : this(Float2(0f), Float2(0f), 0)
        constructor(me: MotionEvent, height: Int) : this() {
            if (me.pointerCount >= 1) {
                this.pt0 = Float2(me.getX(0), height - me.getY(0))
                this.pt1 = this.pt0
                this.count++
            }
            if (me.pointerCount >= 2) {
                this.pt1 = Float2(me.getX(1), height - me.getY(1))
                this.count++
            }
        }

        val separation get() = distance(pt0, pt1)
        val midpoint get() = mix(pt0, pt1, 0.5f)
        val x: Int get() = midpoint.x.toInt()
        val y: Int get() = midpoint.y.toInt()
    }

    private var currentGesture = Gesture.NONE
    private var previousTouch = TouchPair()
    private val tentativeOrbitEvents = ArrayList<TouchPair>()
    private val tentativeZoomEvents = ArrayList<TouchPair>()

    private val kGestureConfidenceCount = 2
    private val kZoomConfidenceDistance = 10
    private val kZoomSpeed = 1f / 10f

    fun onTouchEvent(event: MotionEvent) {
        val touch = TouchPair(event, view.height)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {

                // CANCEL GESTURE DUE TO UNEXPECTED POINTER COUNT

                if ((event.pointerCount != 1 && currentGesture == Gesture.ORBIT) ||
                    (event.pointerCount != 2 && currentGesture == Gesture.ZOOM)
                ) {
                    endGesture()
                    return
                }

                // UPDATE EXISTING GESTURE

                if (currentGesture == Gesture.ZOOM) {
                    val d0 = previousTouch.separation
                    val d1 = touch.separation
                    manipulator.scroll(touch.x, touch.y, (d0 - d1) * kZoomSpeed)
                    previousTouch = touch
                    return
                }

                if (currentGesture != Gesture.NONE) {
                    manipulator.grabUpdate(touch.x, touch.y)
                    return
                }

                // DETECT NEW GESTURE

                if (event.pointerCount == 1) {
                    tentativeOrbitEvents.add(touch)
                }

                if (event.pointerCount == 2) {
                    tentativeZoomEvents.add(touch)
                }

                if (isOrbitGesture()) {
                    manipulator.grabBegin(touch.x, touch.y, false)
                    currentGesture = Gesture.ORBIT
                    return
                }

                if (isZoomGesture()) {
                    currentGesture = Gesture.ZOOM
                    previousTouch = touch
                    return
                }

            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                endGesture()
            }
        }
    }

    private fun endGesture() {
        tentativeOrbitEvents.clear()
        tentativeZoomEvents.clear()
        currentGesture = Gesture.NONE
        manipulator.grabEnd()
        manipulator.jumpToBookmark(manipulator.homeBookmark)
    }

    private fun isOrbitGesture(): Boolean {
        return tentativeOrbitEvents.size > kGestureConfidenceCount
    }


    private fun isZoomGesture(): Boolean {
        if (tentativeZoomEvents.size <= kGestureConfidenceCount) {
            return false
        }
        val oldest = tentativeZoomEvents.first().separation
        val newest = tentativeZoomEvents.last().separation
        return kotlin.math.abs(newest - oldest) > kZoomConfidenceDistance
    }
}
