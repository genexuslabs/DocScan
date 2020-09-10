package com.kranti.doc.scannerapp.scan

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Display
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kranti.doc.scanner.SourceManager
import com.kranti.doc.scanner.processor.Corners
import com.kranti.doc.scanner.scan.IScanView
import com.kranti.doc.scanner.scan.ScanPresenter
import com.kranti.doc.scanner.view.PaperRectangle
import com.kranti.doc.scannerapp.R
import com.kranti.doc.scannerapp.crop.CropActivity
import kotlinx.android.synthetic.main.activity_scan.*
import org.opencv.core.Mat

class ScanActivity : AppCompatActivity(), IScanView {

    private lateinit var presenter: ScanPresenter
    private var latestBackPressTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        initPresenter()
        prepare()
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun initPresenter() {
        val scanCallback = object: ScanPresenter.Callback {
            override fun onPictureTaken(corners: Corners?, picture: Mat) {
                SourceManager.corners = corners
                SourceManager.pic = picture
                startActivity(Intent(this@ScanActivity, CropActivity::class.java))
            }
        }

        presenter = ScanPresenter.new(this, this, scanCallback)
        presenter.flashOff()
        if (presenter.worksOnlyPortrait)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun prepare() {
        if (!ScanPresenter.initOpenCV()) {
            Log.i(TAG, "loading opencv error, exit")
            finish()
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), Companion.REQUEST_CAMERA_PERMISSION)
        }

        shut.setOnClickListener {
            presenter.shut()
        }
        latestBackPressTime = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        presenter.start()
    }

    override fun onStop() {
        super.onStop()
        presenter.stop()
    }

    override fun exit() {
        finish()
    }

    override fun onBackPressed() {
        if (System.currentTimeMillis().minus(latestBackPressTime) > Companion.EXIT_TIME) {
            Toast.makeText(this, R.string.press_again_logout, Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
        latestBackPressTime = System.currentTimeMillis()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == Companion.REQUEST_CAMERA_PERMISSION
                && (grantResults[permissions.indexOf(android.Manifest.permission.CAMERA)] == PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, R.string.camera_grant, Toast.LENGTH_SHORT).show()
            presenter.onPermissionGranted()
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override val display: Display get() = windowManager.defaultDisplay

    override val surfaceView: SurfaceView get() = surface

    override val paperRect: PaperRectangle get() = paper_rect

    @Suppress("DEPRECATION")
    fun imagePreview(@Suppress("UNUSED_PARAMETER") view: View) {
        Toast.makeText(this, "this is the camera preview button you clicked", Toast.LENGTH_LONG).show()
        Log.d("IMAGE", "IMAGE PREVIEW ACTIVITY")
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        val uri = Uri.parse(Environment.getExternalStorageDirectory().path + "/smart_scanner/")
        intent.setDataAndType(uri, "*/*")
        startActivity(Intent.createChooser(intent, "Open folder"))
    }

    fun flashOn(@Suppress("UNUSED_PARAMETER") view: View)
    {
        flashOn.visibility = View.INVISIBLE
        flashOff.visibility = View.VISIBLE
        presenter.flashOn()
    }

    fun flashOff(@Suppress("UNUSED_PARAMETER") view: View)
    {
        flashOff.visibility = View.INVISIBLE
        flashOn.visibility = View.VISIBLE
        presenter.flashOff()
    }

    companion object {
        private const val TAG = "ScanActivity"
        private const val REQUEST_CAMERA_PERMISSION = 0
        private const val EXIT_TIME = 2000
    }
}