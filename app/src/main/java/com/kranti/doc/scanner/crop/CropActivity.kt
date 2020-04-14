package com.kranti.doc.scanner.crop

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.kranti.doc.scanner.R
import com.kranti.doc.scanner.SourceManager
import com.kranti.doc.scanner.view.PaperRectangle
import kotlinx.android.synthetic.main.activity_crop.*

class CropActivity : AppCompatActivity(), ICropView.Proxy {

    private lateinit var mPresenter: CropPresenter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        mPresenter = CropPresenter(this, SourceManager.pic, SourceManager.corners, this)

        crop.setOnClickListener { mPresenter.crop() }
        enhance.setOnClickListener { mPresenter.enhance() }
        save.setOnClickListener { mPresenter.save() }
    }

    override fun getPaper(): ImageView = paper

    override fun getPaperRect(): PaperRectangle = paper_rect

    override fun getCroppedPaper(): ImageView = picture_cropped
}