package com.kranti.doc.scanner.scan

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.media.MediaActionSound
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import com.kranti.doc.scanner.processor.Corners
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanPresenter constructor(private val context: Context, private val iView: IScanView.Proxy, private val pictureTakenCallback: Callback)
    : SurfaceHolder.Callback, Camera.PictureCallback, Camera.PreviewCallback {

    private val TAG: String = "ScanPresenter"
    private var camera: Camera? = null
    private val mSurfaceHolder: SurfaceHolder = iView.getSurfaceView().holder
    private val executor: ExecutorService
    private val proxySchedule: Scheduler
    private var busy: Boolean = false

    init {
        mSurfaceHolder.addCallback(this)
        executor = Executors.newSingleThreadExecutor()
        proxySchedule = Schedulers.from(executor)
    }

    fun start() {
        camera?.startPreview() ?: Log.i(TAG, "camera null")
    }

    fun stop() {
        camera?.stopPreview() ?: Log.i(TAG, "camera null")
    }

    fun shut() {
        busy = true
        Log.i(TAG, "try to focus")
        camera?.autoFocus { b, _ ->
            Log.i(TAG, "focus result: " + b)
            camera?.takePicture(null, null, this)
            MediaActionSound().play(MediaActionSound.SHUTTER_CLICK)
        }
    }

    fun updateCamera() {
        if (null == camera) {
            return
        }
        camera?.stopPreview()
        try {
            camera?.setPreviewDisplay(mSurfaceHolder)
        } catch (e: IOException) {
            e.printStackTrace()
            return
        }
        camera?.setPreviewCallback(this)
        camera?.startPreview()
    }

    fun initCamera() {
        try {
            camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK)
        } catch (e: RuntimeException) {
            e.stackTrace
            Toast.makeText(context, "cannot open camera, please grant camera", Toast.LENGTH_SHORT).show()
            return
        }

        val param = camera?.parameters

        val size = getMaxResolution()
        param?.setPreviewSize(size?.width ?: 1920, size?.height ?: 1080)
        val display = iView.getDisplay()
        val point = Point()
        display.getRealSize(point)
        val displayWidth = minOf(point.x, point.y)
        val displayHeight = maxOf(point.x, point.y)
        val displayRatio = displayWidth.div(displayHeight.toFloat())
        val previewRatio = size?.height?.toFloat()?.div(size.width.toFloat()) ?: displayRatio
        if (displayRatio > previewRatio) {
            val surfaceParams = iView.getSurfaceView().layoutParams
            surfaceParams.height = (displayHeight / displayRatio * previewRatio).toInt()
            iView.getSurfaceView().layoutParams = surfaceParams
        }

        val supportPicSize = camera?.parameters?.supportedPictureSizes
        supportPicSize?.sortByDescending { it.width.times(it.height) }
        var pictureSize = supportPicSize?.find { it.height.toFloat().div(it.width.toFloat()) - previewRatio < 0.01 }

        if (null == pictureSize) {
            pictureSize = supportPicSize?.get(0)
        }

        if (null == pictureSize) {
            Log.e(TAG, "can not get picture size")
        } else {
            param?.setPictureSize(pictureSize.width, pictureSize.height)
        }
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS)) {
            param?.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            Log.d(TAG, "enabling autofocus")
        } else {
            Log.d(TAG, "autofocus not available")
        }
        param?.flashMode = Camera.Parameters.FLASH_MODE_AUTO

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
    override fun onPictureTaken(p0: ByteArray?, p1: Camera?) {
        Log.i(TAG, "on picture taken")
        Observable.just(p0)
                .subscribeOn(proxySchedule)
                .subscribe {
                    val pictureSize = p1?.parameters?.pictureSize
                    Log.i(TAG, "picture size: " + pictureSize?.toString())
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
    override fun onPreviewFrame(p0: ByteArray?, p1: Camera?) {
        if (busy) {
            return
        }
        Log.i(TAG, "on process start")
        busy = true
        Observable.just(p0)
                .observeOn(proxySchedule)
                .subscribe {
                    Log.i(TAG, "start prepare paper")
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
                        val corner = com.kranti.doc.scanner.processor.processPicture(img)
                        busy = false
                        if (null != corner) {
                            it.onNext(corner)
                        } else {
                            it.onError(Throwable("paper not detected"))
                        }
                    }.observeOn(AndroidSchedulers.mainThread())
                            .subscribe({
                                iView.getPaperRect().onCornersDetected(it)
                            }, {
                                iView.getPaperRect().onCornersNotDetected()
                            })
                }
    }

    fun flashOn()
    {
        val param = camera?.parameters
        Log.d("flash", "flash actiiviyy ON")
        param?.flashMode = Camera.Parameters.FLASH_MODE_ON
        camera?.parameters = param
    }

    fun flashOff()
    {
        val param = camera?.parameters
        Log.d("flash", "flash actiiviyy OFF")
        param?.flashMode = Camera.Parameters.FLASH_MODE_OFF
        camera?.parameters = param
    }

    private fun getMaxResolution(): Camera.Size? = camera?.parameters?.supportedPreviewSizes?.maxBy { it.width }

    fun initOpenCV(): Boolean {
        return OpenCVLoader.initDebug()
    }

    interface Callback {
        fun onPictureTaken(corners: Corners?, picture: Mat)
    }
}