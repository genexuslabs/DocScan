package com.kranti.doc.scanner.scan

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.kranti.doc.scanner.processor.Corners
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

@RequiresApi(21)
class ScanPresenterApi21(private val context: Context, private val iView: IScanView, private val pictureTakenCallback: ScanPresenter.Callback)
    : ScanPresenter {

    private val surfaceHolder = iView.surfaceView.holder
    private val cornersBuffer = CornersBuffer()
    private var initialFlashMode = CameraMetadata.CONTROL_MODE_AUTO

    private var cameraDevice: CameraDevice? = null
    private var imageDimension: Size? = null
    private lateinit var imageReader: ImageReader
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var takePicture: Boolean = false

    init {
        surfaceHolder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(p0: SurfaceHolder?) {
                openCamera()
            }

            override fun surfaceChanged(p0: SurfaceHolder?, p1: Int, p2: Int, p3: Int) {
            }

            override fun surfaceDestroyed(p0: SurfaceHolder?) {
                closeCamera()
            }
        })
    }

    override fun start() {
        startBackgroundThread()
    }

    override fun stop() {
        stopBackgroundThread()
    }

    override fun shut() {
        takePicture = true
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Log.e(ScanPresenter.TAG, "is camera open")
        try {
            val cameraId = manager.cameraIdList[0] // Usually front camera is at 0 position.
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            imageDimension = map.getOutputSizes(SurfaceHolder::class.java)?.get(0)!!
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.e(ScanPresenter.TAG, "onOpened")
                    cameraDevice = camera
                    initializeCamera()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice?.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraDevice?.close()
                    cameraDevice = null
                }
            }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Toast.makeText(context, "cannot open camera, please grant camera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera Background")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper!!)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun initializeCamera() {
        try {
            val width = imageDimension?.width ?: 1080
            val height = imageDimension?.height ?: 1920
            surfaceHolder.setFixedSize(width, height)
            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
            cameraDevice?.createCaptureSession(listOf(surfaceHolder.surface, imageReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    //The camera is already closed
                    if (null == cameraDevice) {
                        return
                    }
                    // When the session is ready, we start displaying the preview.
                    startCameraPreview(cameraCaptureSession)
                    startCornerPreview(cameraCaptureSession)
                }

                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    Toast.makeText(context, "Configuration change", Toast.LENGTH_SHORT).show()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun startCameraPreview(cameraCaptureSession: CameraCaptureSession) {
        cameraDevice?.let {
            val captureRequestBuilder = it.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surfaceHolder.surface)
            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null)
        }
    }

    private fun buildCaptureRequest(device: CameraDevice, addFlash: Boolean): CaptureRequest {
        val captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureRequestBuilder.addTarget(imageReader.surface)

        // Orientation
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = windowManager.defaultDisplay.rotation
        captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS[rotation])

        if (addFlash)
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, initialFlashMode)

        return captureRequestBuilder.build()
    }

    private fun startCornerPreview(cameraCaptureSession: CameraCaptureSession) {
        cameraDevice?.let { device ->
            cameraCaptureSession.capture(buildCaptureRequest(device, false), null, null)

            imageReader.setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage().use {
                    val buffer = it.planes[0].buffer
                    val bytes = ByteArray(buffer.capacity())
                    buffer.get(bytes)
                    val mat = Mat(org.opencv.core.Size((it.width * it.height).toDouble(), 1.0), CvType.CV_8U)
                    mat.put(0, 0, bytes)
                    val pic = Imgcodecs.imdecode(mat, Imgcodecs.IMREAD_UNCHANGED)
                    mat.release()
                    val corners = com.kranti.doc.scanner.processor.processPicture(pic)
                    Imgproc.cvtColor(pic, pic, Imgproc.COLOR_RGB2BGRA)
                    if (takePicture && initialFlashMode == CameraMetadata.CONTROL_MODE_AUTO) {
                        // take one more with flash
                        cameraCaptureSession.capture(buildCaptureRequest(device,true), null, null)
                        initialFlashMode = CameraMetadata.CONTROL_MODE_OFF
                    } else {
                        if (!checkCorners(corners, pic))
                            cameraCaptureSession.capture(buildCaptureRequest(device,false), null, null)
                    }
                }
            }, backgroundHandler)
        }
    }

    private fun checkCorners(corners: Corners?, pic: Mat): Boolean {
        if (corners != null) {
            iView.paperRect.onCornersDetected(corners)
            if (cornersBuffer.onCornersDetected(corners) || takePicture) {
                pictureTakenCallback.onPictureTaken(corners, pic)
                return true
            }
        } else {
            iView.paperRect.onCornersNotDetected()
            cornersBuffer.onCornersNotDetected()
        }
        return false
    }

    private fun closeCamera() {
        synchronized(this) {
            if (null != cameraDevice) {
                cameraDevice?.close();
                cameraDevice = null;
            }
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
        initialFlashMode = CameraMetadata.CONTROL_MODE_AUTO
        Log.d("flash", "flash active ON")
    }

    override fun flashOff() {
        initialFlashMode = CameraMetadata.CONTROL_MODE_OFF
        Log.d("flash", "flash active OFF")
    }

    override fun onPermissionGranted() {
        start()
    }

    companion object {
        private val ORIENTATIONS = mapOf(
            Surface.ROTATION_0 to 90,
            Surface.ROTATION_90 to 0,
            Surface.ROTATION_180 to 270,
            Surface.ROTATION_270 to 180
        )
    }
}
