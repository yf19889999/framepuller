package com.example.framepuller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {

    private var videoUri: Uri? = null
    private lateinit var tvInfo: TextView
    private lateinit var etStart: EditText
    private lateinit var etEnd: EditText
    private lateinit var etFps: EditText
    private lateinit var tvStatus: TextView
    private lateinit var ivPreview: ImageView

    private val outputDir: File by lazy {
        File(getExternalFilesDir(null), "frames").apply {
            if (!exists()) mkdirs()
        }
    }

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            videoUri = result.data?.data
            tvInfo.text = "已选择：${videoUri?.lastPathSegment ?: "视频"}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvInfo = findViewById(R.id.tvInfo)
        etStart = findViewById(R.id.etStart)
        etEnd = findViewById(R.id.etEnd)
        etFps = findViewById(R.id.etFps)
        tvStatus = findViewById(R.id.tvStatus)
        ivPreview = findViewById(R.id.ivPreview)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            pickVideo.launch(intent)
        }

        findViewById<Button>(R.id.btnExtract).setOnClickListener {
            if (videoUri == null) {
                Toast.makeText(this, "先选视频", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startExtract()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveToGallery()
        }
    }

    private fun startExtract() {
        val start = etStart.text.toString().toFloatOrNull() ?: 0f
        val end = etEnd.text.toString().toFloatOrNull() ?: 5f
        val fps = etFps.text.toString().toFloatOrNull() ?: 1f

        if (end <= start) {
            Toast.makeText(this, "结束时间要大于起始时间", Toast.LENGTH_SHORT).show()
            return
        }

        outputDir.listFiles()?.forEach { it.delete() }

        val path = getVideoPath(videoUri!!)
        if (path == null) {
            Toast.makeText(this, "读取视频失败", Toast.LENGTH_SHORT).show()
            return
        }

        val cmd = "-y -ss $start -to $end -i \"$path\" -vf fps=$fps ${outputDir.absolutePath}/frame_%04d.jpg"

        tvStatus.text = "正在抽帧..."
        findViewById<Button>(R.id.btnExtract).isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            val rc = FFmpegKit.execute(cmd)
            withContext(Dispatchers.Main) {
                findViewById<Button>(R.id.btnExtract).isEnabled = true
                if (ReturnCode.isSuccess(rc)) {
                    val files = outputDir.listFiles()
                        ?.filter { it.extension == "jpg" }
                        ?.sortedBy { it.name }
                    if (files.isNullOrEmpty()) {
                        tvStatus.text = "没有生成帧"
                        return@withContext
                    }
                    tvStatus.text = "完成！共 ${files.size} 张"
                    ivPreview.setImageURI(Uri.fromFile(files.first()))
                    Toast.makeText(this@MainActivity, "抽了 ${files.size} 张", Toast.LENGTH_LONG).show()
                } else {
                    tvStatus.text = "失败，返回码：$rc"
                }
            }
        }
    }

    private fun saveToGallery() {
        val files = outputDir.listFiles()?.filter { it.extension == "jpg" } ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, "没有可保存的", Toast.LENGTH_SHORT).show()
            return
        }

        var count = 0
        files.forEach { file ->
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VideoFrames")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                count++
            }
        }
        Toast.makeText(this, "已保存 $count 张到相册", Toast.LENGTH_LONG).show()
    }

    private fun getVideoPath(uri: Uri): String? {
        val proj = arrayOf(MediaStore.Video.Media.DATA)
        contentResolver.query(uri, proj, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                return c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA))
            }
        }
        return uri.path
    }
}
