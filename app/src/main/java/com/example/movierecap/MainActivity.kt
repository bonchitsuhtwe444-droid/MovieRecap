package com.example.movierecap

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private var videoUri: Uri? = null

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            videoUri = uri
            findViewById<TextView>(R.id.tvVideo).text = "ရွေးထားတာ: $uri"
            findViewById<Button>(R.id.btnExtract).isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnPick = findViewById<Button>(R.id.btnPick)
        val btnExtract = findViewById<Button>(R.id.btnExtract)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnPick.setOnClickListener { pickVideo.launch("video/*") }

        btnExtract.setOnClickListener {
            val uri = videoUri ?: return@setOnClickListener
            val outFile = File(filesDir, "audio_16k.wav")

            btnExtract.isEnabled = false
            progress.visibility = View.VISIBLE
            progress.progress = 0
            tvStatus.text = "Audio ထုတ်နေပါတယ်..."

            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        AudioExtractor.extractToWav(this@MainActivity, uri, outFile) { p ->
                            runOnUiThread { progress.progress = p }
                        }
                    }
                    val mb = outFile.length() / 1024.0 / 1024.0
                    tvStatus.text = "✅ ပြီးပါပြီ\n${outFile.absolutePath}\nအရွယ်အစား: %.1f MB".format(mb)
                } catch (e: Exception) {
                    tvStatus.text = "❌ Error: ${e.message}"
                } finally {
                    btnExtract.isEnabled = true
                    progress.visibility = View.GONE
                }
            }
        }
    }
}
