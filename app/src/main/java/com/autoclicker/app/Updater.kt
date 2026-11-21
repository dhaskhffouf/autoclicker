package com.autoclicker.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Cập nhật OTA qua GitHub Releases (repo public).
 * Quy ước: mỗi release có tag dạng v1.2.3 và đính kèm 1 file .apk
 * (tuỳ chọn: file <tên-apk>.sha256 để kiểm tra toàn vẹn).
 */
object Updater {
    private const val UA = "AutoClicker-Updater"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val shaUrl: String?
    )

    fun check(onResult: (Result<Release?>) -> Unit) {
        io.execute {
            val r = runCatching { fetchLatest() }
            main.post { onResult(r) }
        }
    }

    private fun fetchLatest(): Release? {
        val repo = BuildConfig.GITHUB_REPO
        val json = JSONObject(httpGet("https://api.github.com/repos/$repo/releases/latest", "application/vnd.github+json"))
        val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
        if (tag.isEmpty()) return null
        val assets = json.optJSONArray("assets") ?: return null
        var apk: String? = null
        var apkName: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apk = a.optString("browser_download_url")
                apkName = name
                break
            }
        }
        if (apk == null || apkName == null) return null
        var sha: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == "$apkName.sha256") sha = a.optString("browser_download_url")
        }
        return Release(tag, json.optString("body"), apk, sha)
    }

    /** So sánh số phiên bản dạng 1.2.3 (bỏ phần hậu tố nếu có). */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun download(
        ctx: Context,
        release: Release,
        onProgress: (Int) -> Unit,
        onDone: (Result<File>) -> Unit
    ) {
        val app = ctx.applicationContext
        io.execute {
            val res = runCatching {
                require(release.apkUrl.startsWith("https://")) { "Liên kết tải không an toàn" }
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "update.apk")

                val c = URL(release.apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", UA)
                try {
                    if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                    val total = c.contentLengthLong
                    var done = 0L
                    var last = -1
                    c.inputStream.use { input ->
                        out.outputStream().use { os ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                os.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != last) {
                                        last = pct
                                        main.post { onProgress(pct) }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }

                if (release.shaUrl != null) {
                    val expected = httpGet(release.shaUrl, null).trim().split(Regex("\\s+")).first().lowercase()
                    val actual = sha256(out)
                    if (expected != actual) {
                        out.delete()
                        error("Sai mã SHA-256, đã huỷ cập nhật")
                    }
                }
                out
            }
            main.post { onDone(res) }
        }
    }

    /** Trả về false nếu cần người dùng cấp quyền "cài ứng dụng không rõ nguồn" trước. */
    fun install(ctx: Context, file: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    private fun httpGet(url: String, accept: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", UA)
        if (accept != null) c.setRequestProperty("Accept", accept)
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
