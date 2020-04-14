package com.kranti.doc.scanner.scan

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

@RequiresApi(23) // Needs 23 for the flash, even though camera2 is available in 21
class ScanPresenterApi23(private val context: Context, private val iView: IScanView, private val pictureTakenCallback: ScanPresenter.Callback)
    : ScanPresenter {

    private val surfaceHolder = iView.surfaceView.holder
    private val cornersBuffer = CornersBuffer()
    private var initialFlashMode = CameraMetadata.CONTROL_MODE_AUTO

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSessions: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var imageDimension: Size? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

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
        takePicture()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Log.e(ScanPresenter.TAG, "is camera open")
        try {
            val cameraId = manager.cameraIdList[0] // Usually front camera is at 0 position.
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            imageDimension = map.getOutputSizes(SurfaceHolder::class.java)?.get(0)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.e(ScanPresenter.TAG, "onOpened");
                    cameraDevice = camera;
                    createCameraPreview();
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice?.close();
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraDevice?.close();
                    cameraDevice = null;
                }
            }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Toast.makeText(context, "cannot open camera, please grant camera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun takePicture() {
        if (null == cameraDevice) {
            Log.e(ScanPresenter.TAG, "cameraDevice is null")
            return
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = manager.getCameraCharacteristics(cameraDevice!!.id)
            val jpegSizes = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(ImageFormat.JPEG)
            var width = 640
            var height = 480
            if (jpegSizes != null && jpegSizes.isNotEmpty()) {
                width = jpegSizes[0].width
                height = jpegSizes[0].height
            }
            val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
            val outputSurfaces = ArrayList<Surface>(2)
            outputSurfaces.add(reader.surface)
            outputSurfaces.add(surfaceHolder.surface)
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder?.addTarget(reader.surface)
            captureBuilder?.set(CaptureRequest.CONTROL_MODE, initialFlashMode)
            // Orientation
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val rotation = windowManager.defaultDisplay.rotation
            captureBuilder?.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS[rotation])
            val readerListener = ImageReader.OnImageAvailableListener { reader ->
                reader.acquireLatestImage().use {
                    val buffer = it.planes[0].buffer
                    val bytes = ByteArray(buffer.capacity())
                    buffer.get(bytes)
                    val mat = Mat(org.opencv.core.Size(((imageDimension?.width ?: 1920) *
                            (imageDimension?.height ?: 1080)).toDouble(), 1.0), CvType.CV_8U)
                    mat.put(0, 0, bytes)
                    val pic = Imgcodecs.imdecode(mat, Imgcodecs.IMREAD_UNCHANGED)
                    mat.release()
                    val corners = com.kranti.doc.scanner.processor.processPicture(pic)
                    Imgproc.cvtColor(pic, pic, Imgproc.COLOR_RGB2BGRA)
                    pictureTakenCallback.onPictureTaken(corners, pic)
                }
            }
            reader.setOnImageAvailableListener(readerListener, backgroundHandler)
            val captureListener = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    createCameraPreview()
                }
            }
            cameraDevice?.createCaptureSession(outputSurfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        session.capture(captureBuilder?.build()!!, captureListener, backgroundHandler)
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) { }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera Background")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper)
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

    private fun createCameraPreview() {
        try {
            val surface = surfaceHolder.surface
            surfaceHolder.setFixedSize(imageDimension?.width ?: 1080, imageDimension?.height ?: 1920)
            captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder?.addTarget(surface)
            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    //The camera is already closed
                    if (null == cameraDevice) {
                        return
                    }
                    // When the session is ready, we start displaying the preview.
                    cameraCaptureSessions = cameraCaptureSession
                    updatePreview()
                }

                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    Toast.makeText(context, "Configuration change", Toast.LENGTH_SHORT).show()
                }
            }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updatePreview() {
        if (null == cameraDevice) {
            Log.e(ScanPresenter.TAG, "updatePreview error, return")
        }
        captureRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        try {
            cameraCaptureSessions?.setRepeatingRequest(captureRequestBuilder?.build()!!, null, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
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
        initialFlashMode = CameraMetadata.CONTROL_AE_MODE_ON
        Log.d("flash", "flash active ON")
        turnFlash(true)
    }

    override fun flashOff() {
        initialFlashMode = CameraMetadata.CONTROL_MODE_OFF
        Log.d("flash", "flash active OFF")
        turnFlash(false)
    }

    private fun turnFlash(enabled: Boolean) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList[0] // Usually front camera is at 0 position.
        manager.setTorchMode(cameraId, enabled)
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
