package com.kranti.doc.scanner.scan

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.Display
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kranti.doc.scanner.R
import com.kranti.doc.scanner.base.BaseActivity
import com.kranti.doc.scanner.crop.CropActivity
import com.kranti.doc.scanner.view.PaperRectangle
import kotlinx.android.synthetic.main.activity_scan.*

class ScanActivity : BaseActivity(), IScanView.Proxy {
    private val REQUEST_CAMERA_PERMISSION = 0
    private val EXIT_TIME = 2000

    private val RESULT_LOAD_IMAGE = 1
    private lateinit var mPresenter: ScanPresenter
    private var latestBackPressTime: Long = 0

    override fun provideContentViewId(): Int = R.layout.activity_scan

    override fun initPresenter() {
        mPresenter = ScanPresenter(this, this, CropActivity::class.java)
    }

    override fun prepare() {
        if (!mPresenter.initOpenCV()) {
            Log.i(TAG, "loading opencv error, exit")
            finish()
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CAMERA_PERMISSION)
        } else if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        } else if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CAMERA_PERMISSION)
        }

        shut.setOnClickListener {
            mPresenter.shut()
        }
        latestBackPressTime = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        mPresenter.start()
    }

    override fun onStop() {
        super.onStop()
        mPresenter.stop()
    }

    override fun exit() {
        finish()
    }

    override fun onBackPressed() {
        if (System.currentTimeMillis().minus(latestBackPressTime) > EXIT_TIME) {
            showMessage(R.string.press_again_logout)
        } else {
            super.onBackPressed()
        }
        latestBackPressTime = System.currentTimeMillis()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CAMERA_PERMISSION
                && (grantResults[permissions.indexOf(android.Manifest.permission.CAMERA)] == PackageManager.PERMISSION_GRANTED)) {
            showMessage(R.string.camera_grant)
            mPresenter.initCamera()
            mPresenter.updateCamera()
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun getDisplay(): Display = windowManager.defaultDisplay

    override fun getSurfaceView(): SurfaceView = surface

    override fun getPaperRect(): PaperRectangle = paper_rect

    fun imagePreview(view: View) {
        Toast.makeText(this, "this is the camera preview button you clicked", Toast.LENGTH_LONG).show()
        Log.d("IMAGE", "IMAGE PREVIEW ACTIVITY")
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        val uri = Uri.parse(Environment.getExternalStorageDirectory().path + "/smart_scanner/")
        intent.setDataAndType(uri, "*/*")
        startActivity(Intent.createChooser(intent, "Open folder"))
    }

    fun flashOn(view: View)
    {
        flashOn.visibility = View.INVISIBLE
        flashOff.visibility = View.VISIBLE
        mPresenter.flashOn()
    }

    fun flashOff(view: View)
    {
        flashOff.visibility = View.INVISIBLE
        flashOn.visibility = View.VISIBLE
        mPresenter.flashOff()
    }
}