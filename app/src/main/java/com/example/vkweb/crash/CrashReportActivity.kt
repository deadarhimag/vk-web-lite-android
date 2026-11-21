package com.example.vkweb.crash

import android.content.ClipData
import com.example.vkweb.ui.Notice
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.vkweb.MainActivity
import com.example.vkweb.R
import com.example.vkweb.diag.FileShare
import java.io.IOException

/**
 * Отчёт о прошлом сбое. Отдельный простой экран без WebView: открывается до главного,
 * поэтому его видно, даже если главный экран снова падает при запуске.
 */
class CrashReportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)
        val report = CrashLog.read(this)
        findViewById<TextView>(R.id.crash_text).text = report

        // Отчёт бывает длинным (стек, события, дамп потоков) — отправляем файлом: текстом он обрезается.
        findViewById<Button>(R.id.crash_share).setOnClickListener {
            val file = FileShare.newFile(this, CRASH_FILE)
            try {
                file.writeText(report)
                FileShare.share(this, file, "text/plain", getString(R.string.crash_share))
            } catch (e: IOException) {
                Notice.show(this, R.string.pref_log_failed)
            }
        }
        findViewById<Button>(R.id.crash_copy).setOnClickListener {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("crash", report))
            Notice.show(this, R.string.crash_copied)
        }
        findViewById<Button>(R.id.crash_continue).setOnClickListener {
            CrashLog.clear(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private companion object {
        const val CRASH_FILE = "vkweb_crash.txt"
    }
}
