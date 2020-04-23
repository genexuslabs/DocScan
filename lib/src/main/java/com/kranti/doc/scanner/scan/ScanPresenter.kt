package com.kranti.doc.scanner.scan

import android.content.Context
import android.os.Build
import com.kranti.doc.scanner.processor.Corners
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat

interface ScanPresenter {
    fun shut()
    fun start()
    fun stop()
    fun flashOn()
    fun flashOff()
    fun onPermissionGranted()
    val worksOnlyPortrait: Boolean
    var autoDetectionEnable: Boolean
    var autoDetectionRatio: Double

    interface Callback {
        fun onPictureTaken(corners: Corners?, picture: Mat)
    }

    companion object {
        const val TAG: String = "ScanPresenter"

        fun new(context: Context, iView: IScanView, pictureTakenCallback: Callback): ScanPresenter {
            return if (Build.VERSION.SDK_INT >= 21)
                ScanPresenterApi21(context, iView, pictureTakenCallback)
            else
                ScanPresenterDeprecated(context, iView, pictureTakenCallback)
        }

        fun initOpenCV(): Boolean {
            return OpenCVLoader.initDebug()
        }
    }
}