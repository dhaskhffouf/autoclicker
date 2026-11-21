package com.autoclicker.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class   MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvSlots: TextView
    private lateinit var tvUpdate: TextView
    private lateinit var progress: ProgressBar
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvSlots = findViewById(R.id.tvSlots)
        tvUpdate = findViewById(R.id.tvUpdate)
        progress = findViewById(R.id.progress)
        findViewById<TextView>(R.id.tvVersion).text = "Phiên bản hiện tại: ${BuildConfig.VERSION_NAME}"

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnShow).setOnClickListener {
            val s = ClickService.instance
            if (s == null) {
                Toast.makeText(this, "Hãy bật dịch vụ trợ năng AutoClicker trước", Toast.LENGTH_LONG).show()
            } else {
                s.showOverlay()
                moveTaskToBack(true)
            }
        }
        findViewById<Button>(R.id.btnHide).setOnClickListener { ClickService.instance?.hideOverlay() }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { checkUpdate(manual = true) }

        checkUpdate(manual = false)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        tvStatus.text = if (ClickService.instance != null)
            "Dịch vụ trợ năng: ĐÃ BẬT ✔"
        else
            "Dịch vụ trợ năng: chưa bật. Vào Cài đặt > Trợ năng > AutoClicker để bật."

        val slots = Storage.loadSlots(this)
        tvSlots.text = slots.mapIndexed { i, s ->
            "Ô ${i + 1}: " + if (s == null) "(trống)" else "${s.name} · ${s.points.size} điểm"
        }.joinToString("\n") + "\n\n(Lưu/tải bản lưu trong bảng điều khiển nổi)"
    }

    // ---------------------------------------------------------------- OTA

    private fun checkUpdate(manual: Boolean) {
        if (BuildConfig.GITHUB_REPO.startsWith("OWNER/")) {
            if (manual) tvUpdate.text = "Chưa cấu hình GITHUB_REPO trong app/build.gradle.kts"
            return
        }
        if (manual) tvUpdate.text = "Đang kiểm tra..."
        Updater.check { res ->
            res.onSuccess { rel ->
                if (rel != null && Updater.isNewer(rel.version, BuildConfig.VERSION_NAME)) {
                    tvUpdate.text = "Có bản mới: ${rel.version}"
                    showUpdateDialog(rel)
                } else if (manual) {
                    tvUpdate.text = "Bạn đang dùng bản mới nhất."
                }
            }.onFailure {
                if (manual) tvUpdate.text = "Lỗi kiểm tra cập nhật: ${it.message}"
            }
        }
    }

    private fun showUpdateDialog(rel: Updater.Release) {
        if (isFinishing || downloading) return
        AlertDialog.Builder(this)
            .setTitle("Có phiên bản ${rel.version}")
            .setMessage(rel.notes.take(800).ifBlank { "Bản cập nhật mới." })
            .setPositiveButton("Cập nhật") { _, _ -> startDownload(rel) }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun startDownload(rel: Updater.Release) {
        downloading = true
        progress.progress = 0
        progress.visibility = View.VISIBLE
        tvUpdate.text = "Đang tải..."
        Updater.download(this, rel,
            onProgress = { pct ->
                progress.progress = pct
                tvUpdate.text = "Đang tải... $pct%"
            },
            onDone = { res ->
                downloading = false
                progress.visibility = View.GONE
                res.onSuccess { file ->
                    tvUpdate.text = "Tải xong, đang mở trình cài đặt..."
                    if (!Updater.install(this, file)) {
                        tvUpdate.text = "Hãy cho phép \"Cài ứng dụng không rõ nguồn\" cho AutoClicker, rồi bấm Kiểm tra cập nhật lại."
                    }
                }.onFailure {
                    tvUpdate.text = "Cập nhật thất bại: ${it.message}"
                }
            })
    }
}
