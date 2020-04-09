package com.kranti.doc.scanner.scan

import com.kranti.doc.scanner.processor.Corners

private const val MATCHING_THRESHOLD_SQUARED = 50.0 * 50.0

class CornersBuffer {
    var lastCorners: Corners? = null

    fun onCornersDetected(corners: Corners) {
        matchLastCorners(corners)
    }

    fun onCornersNotDetected() {

    }

    private fun matchLastCorners(corners: Corners): Boolean {
        for (n in corners.corners.indices) {
            for (m in 1 until corners.corners.size) {
                if (corners.corners[n] == null || corners.corners[m] == null ||
                        m != n && corners.corners[n]!!.x == corners.corners[m]!!.x && corners.corners[n]!!.y == corners.corners[m]!!.y) {
                    // triangle
                    lastCorners = null
                    return false
                }
            }
        }
        if (lastCorners == null) {
            lastCorners = corners
            return true
        }
        if (corners.corners.size != lastCorners?.corners?.size) {
            lastCorners = null
            return false
        }
        for (n in corners.corners.indices) {
            val xDifference = corners.corners[n]!!.x - lastCorners!!.corners[n]!!.x
            val yDifference = corners.corners[n]!!.y - lastCorners!!.corners[n]!!.y
            val distanceSquare = xDifference * xDifference + yDifference * yDifference
            if (distanceSquare > MATCHING_THRESHOLD_SQUARED) {
                lastCorners = null
                return false
            }
        }
        lastCorners = corners
        return true
    }
}