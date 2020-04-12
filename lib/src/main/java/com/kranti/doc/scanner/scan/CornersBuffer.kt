package com.kranti.doc.scanner.scan

import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import com.kranti.doc.scanner.processor.Corners
import com.kranti.doc.scanner.view.PaperRectangle
import org.opencv.core.Point
import org.opencv.core.Size
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class CornersBuffer {
    var lastCorners: Corners? = null
    var documentDetected = 0
    var autoDetectionEnable = true
    var autoDetectionRatio = .0

    fun onCornersDetected(corners: Corners): Boolean {
        lastCorners = matchLastCorners(corners)
        if (lastCorners == null || !matchRatio(corners)) {
            documentDetected = 0
        } else if (documentDetected != -1 && ++documentDetected >= AUTO_SCAN_THRESHOLD) {
            documentDetected = -1 // don't detect the same shape twice
            return true
        }
        return false
    }

    fun onCornersNotDetected() {
        documentDetected = 0
    }

    fun getFinalCorners(corners: Corners?, paper: View): Corners? {
        if (corners?.corners != null) {
            val ratioX = corners.size.width.div(paper.measuredWidth)
            val ratioY = corners.size.height.div(paper.measuredHeight)
            lastCorners?.corners?.let {
                lastCorners = Corners(it.map { pt -> pt?.let { Point(pt.x * ratioX, pt.y * ratioY )} }, corners.size)
            }
        }
        return corners?.let {
            matchLastCorners(it) ?: lastCorners
        } ?: lastCorners
    }

    private fun matchLastCorners(corners: Corners): Corners? {
        for (n in corners.corners.indices) {
            for (m in 1 until corners.corners.size) {
                if (corners.corners[n] == null || corners.corners[m] == null ||
                        m != n && corners.corners[n]!!.x == corners.corners[m]!!.x && corners.corners[n]!!.y == corners.corners[m]!!.y) {
                    // triangle
                    return null
                }
            }
        }

        if (lastCorners == null)
            return corners

        if (corners.corners.size != lastCorners?.corners?.size)
            return null

        for (n in corners.corners.indices) {
            val xDifference = corners.corners[n]!!.x - lastCorners!!.corners[n]!!.x
            val yDifference = corners.corners[n]!!.y - lastCorners!!.corners[n]!!.y
            val distanceSquare = xDifference * xDifference + yDifference * yDifference
            if (distanceSquare > MATCHING_THRESHOLD_SQUARED)
                return null
        }

        return corners
    }

    private fun matchRatio(corners: Corners): Boolean {
        if (autoDetectionRatio == .0)
            return true

        val tl: Point = corners.corners[0]!!
        val tr: Point = corners.corners[1]!!
        val br: Point = corners.corners[2]!!
        val bl: Point = corners.corners[3]!!

        val widthA = sqrt((br.x - bl.x).pow(2.0) + (br.y - bl.y).pow(2.0))
        val widthB = sqrt((tr.x - tl.x).pow(2.0) + (tr.y - tl.y).pow(2.0))
        val dw = max(widthA, widthB)

        val heightA = sqrt((tr.x - br.x).pow(2.0) + (tr.y - br.y).pow(2.0))
        val heightB = sqrt((tl.x - bl.x).pow(2.0) + (tl.y - bl.y).pow(2.0))
        val dh = max(heightA, heightB)

        val ratio = if (dh < dw) dh / dw else dw / dh
        Log.i("RATIO", ratio.toString())

        return ratio >= autoDetectionRatio * 0.9 && ratio <= autoDetectionRatio * 1.1
    }

    companion object {
        private const val MATCHING_THRESHOLD_SQUARED = 50.0 * 50.0
        private const val AUTO_SCAN_THRESHOLD = 3
    }
}