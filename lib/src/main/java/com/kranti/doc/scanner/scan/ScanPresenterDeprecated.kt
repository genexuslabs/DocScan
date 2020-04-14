package com.kranti.doc.scanner.scan

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaActionSound
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import com.kranti.doc.scanner.processor.Corners
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Suppress("DEPRECATION")
class ScanPresenterDeprecated(private val context: Context, private val iView: IScanView, private val pictureTakenCallback: ScanPresenter.Callback)
    : ScanPresenter, SurfaceHolder.Callback, android.hardware.Camera.PictureCallback, android.hardware.Camera.PreviewCallback {

    private var camera: android.hardware.Camera? = null
    private val surfaceHolder = iView.surfaceView.holder
    private val executor: ExecutorService
    private val proxySchedule: Scheduler
    private var busy: Boolean = false
    private val cornersBuffer = CornersBuffer()
    private var initialFlashMode = android.hardware.Camera.Parameters.FLASH_MODE_AUTO

    init {
        surfaceHolder.addCallback(this)
        executor = Executors.newSingleThreadExecutor()
        proxySchedule = Schedulers.from(executor)
    }

    override fun start() {
        camera?.startPreview() ?: Log.i(ScanPresenter.TAG, "camera null")
    }

    override fun stop() {
        camera?.stopPreview() ?: Log.i(ScanPresenter.TAG, "camera null")
    }

    override fun shut() {
        busy = true
        Log.i(ScanPresenter.TAG, "try to focus")
        camera?.autoFocus { b, _ ->
            Log.i(ScanPresenter.TAG, "focus result: " + b)
            try {
                camera?.takePicture(null, null, this)
                MediaActionSound().play(MediaActionSound.SHUTTER_CLICK)
            } catch (ex: RuntimeException) {
                Log.e(ScanPresenter.TAG, ex.toString())
            }
        }
    }

    private fun updateCamera() {
        if (null == camera) {
            return
        }
        camera?.stopPreview()
        try {
            camera?.setPreviewDisplay(surfaceHolder)
        } catch (e: IOException) {
            e.printStackTrace()
            return
        }
        camera?.setPreviewCallback(this)
        camera?.startPreview()
    }

    private fun initCamera() {
        try {
            camera = android.hardware.Camera.open(android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK)
        } catch (e: RuntimeException) {
            e.stackTrace
            Toast.makeText(context, "cannot open camera, please grant camera", Toast.LENGTH_SHORT).show()
            return
        }

        val param = camera?.parameters

        val size = getMaxResolution()
        param?.setPreviewSize(size?.width ?: 1920, size?.height ?: 1080)
        val display = iView.display
        val point = Point()
        display.getRealSize(point)
        val displayWidth = minOf(point.x, point.y)
        val displayHeight = maxOf(point.x, point.y)
        val displayRatio = displayWidth.div(displayHeight.toFloat())
        val previewRatio = size?.height?.toFloat()?.div(size.width.toFloat()) ?: displayRatio
        if (displayRatio > previewRatio) {
            val surfaceParams = iView.surfaceView.layoutParams
            surfaceParams.height = (displayHeight / displayRatio * previewRatio).toInt()
            iView.surfaceView.layoutParams = surfaceParams
        }

        val supportPicSize = camera?.parameters?.supportedPictureSizes
        supportPicSize?.sortByDescending { it.width.times(it.height) }
        var pictureSize = supportPicSize?.find { it.height.toFloat().div(it.width.toFloat()) - previewRatio < 0.01 }

        if (null == pictureSize) {
            pictureSize = supportPicSize?.get(0)
        }

        if (null == pictureSize) {
            Log.e(ScanPresenter.TAG, "can not get picture size")
        } else {
            param?.setPictureSize(pictureSize.width, pictureSize.height)
        }
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS)) {
            param?.focusMode = android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            Log.d(ScanPresenter.TAG, "enabling autofocus")
        } else {
            Log.d(ScanPresenter.TAG, "autofocus not available")
        }
        param?.flashMode = initialFlashMode

        camera?.parameters = param
        camera?.setDisplayOrientation(90)
    }

    override fun surfaceCreated(p0: SurfaceHolder?) {
        initCamera()
    }

    override fun surfaceChanged(p0: SurfaceHolder?, p1: Int, p2: Int, p3: Int) {
        updateCamera()
    }

    override fun surfaceDestroyed(p0: SurfaceHolder?) {
        synchronized(this) {
            camera?.stopPreview()
            camera?.setPreviewCallback(null)
            camera?.release()
            camera = null
        }
    }

    @SuppressLint("CheckResult")
    override fun onPictureTaken(p0: ByteArray?, p1: android.hardware.Camera?) {
        Log.i(ScanPresenter.TAG, "on picture taken")
        Observable.just(p0)
                .subscribeOn(proxySchedule)
                .subscribe {
                    val pictureSize = p1?.parameters?.pictureSize
                    Log.i(ScanPresenter.TAG, "picture size: " + pictureSize?.toString())
                    val mat = Mat(Size(((pictureSize?.width ?: 1920) * (pictureSize?.height ?: 1080)).toDouble(), 1.0), CvType.CV_8U)
                    mat.put(0, 0, p0)
                    val pic = Imgcodecs.imdecode(mat, Imgcodecs.IMREAD_UNCHANGED)
                    Core.rotate(pic, pic, Core.ROTATE_90_CLOCKWISE)
                    mat.release()
                    val corners = com.kranti.doc.scanner.processor.processPicture(pic)
                    Imgproc.cvtColor(pic, pic, Imgproc.COLOR_RGB2BGRA)
                    pictureTakenCallback.onPictureTaken(corners, pic)
                    busy = false
                }
    }


    @SuppressLint("CheckResult")
    override fun onPreviewFrame(p0: ByteArray?, p1: android.hardware.Camera?) {
        if (busy) {
            return
        }
        Log.i(ScanPresenter.TAG, "on process start")
        busy = true
        Observable.just(p0)
                .observeOn(proxySchedule)
                .subscribe {
                    Log.i(ScanPresenter.TAG, "start prepare paper")
                    val parameters = p1?.parameters
                    val width = parameters?.previewSize?.width
                    val height = parameters?.previewSize?.height
                    val yuv = YuvImage(p0, parameters?.previewFormat ?: 0, width ?: 1080, height
                            ?: 1920, null)
                    val out = ByteArrayOutputStream()
                    yuv.compressToJpeg(Rect(0, 0, width ?: 1080, height ?: 1920), 100, out)
                    val bytes = out.toByteArray()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                    val img = Mat()
                    Utils.bitmapToMat(bitmap, img)
                    bitmap.recycle()
                    Core.rotate(img, img, Core.ROTATE_90_CLOCKWISE)
                    try {
                        out.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                    Observable.create<Corners> {
                        val corners = com.kranti.doc.scanner.processor.processPicture(img)
                        busy = false
                        if (null != corners) {
                            it.onNext(corners)
                        } else {
                            it.onError(Throwable("paper not detected"))
                        }
                    }.observeOn(AndroidSchedulers.mainThread())
                            .subscribe({
                                iView.paperRect.onCornersDetected(it)
                                if (cornersBuffer.onCornersDetected(it))
                                    shut()
                            }, {
                                iView.paperRect.onCornersNotDetected()
                                cornersBuffer.onCornersNotDetected()
                            })
                }
    }

    override var autoDetectionEnable
        get() = cornersBuffer.autoDetectionEnable
        set(value) { cornersBuffer.autoDetectionEnable = value }

    override var autoDetectionRatio
        get() = cornersBuffer.autoDetectionRatio
        set(value) { cornersBuffer.autoDetectionRatio = value }

    override fun flashOn()
    {
        initialFlashMode = android.hardware.Camera.Parameters.FLASH_MODE_ON
        val param = camera?.parameters
        Log.d("flash", "flash active ON")
        param?.flashMode = initialFlashMode
        camera?.parameters = param
    }

    override fun flashOff()
    {
        initialFlashMode = android.hardware.Camera.Parameters.FLASH_MODE_OFF
        val param = camera?.parameters
        Log.d("flash", "flash active OFF")
        param?.flashMode = initialFlashMode
        camera?.parameters = param
    }

    override fun onPermissionGranted() {
        initCamera()
        updateCamera()
    }

    private fun getMaxResolution(): android.hardware.Camera.Size? = camera?.parameters?.supportedPreviewSizes?.maxBy { it.width }
}