package com.kranti.doc.scanner.scan

import android.view.Display
import android.view.SurfaceView
import com.kranti.doc.scanner.view.PaperRectangle

interface IScanView {
    val display: Display
    val surfaceView: SurfaceView
    val paperRect: PaperRectangle
    fun exit()
}