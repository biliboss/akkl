package com.cdp.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val SERVER_PORT = 9223
    }

    private lateinit var status: TextView
    private lateinit var url: TextView
    private lateinit var log: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        url = findViewById(R.id.url)
        log = findViewById(R.id.log)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        btnStart.setOnClickListener { requestScreenCapture() }
        btnStop.setOnClickListener { stopService() }

        CdpService.logCallback = { msg ->
            runOnUiThread {
                log.append("$msg\n")
            }
        }

        updateUI()
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, CdpService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            startForegroundService(serviceIntent)
            updateUI(running = true)
        }
    }

    private fun stopService() {
        stopService(Intent(this, CdpService::class.java))
        updateUI(running = false)
    }

    private fun updateUI(running: Boolean = CdpService.isRunning) {
        if (running) {
            status.text = "Running"
            status.setTextColor(0xFF2ecc71.toInt())
            url.text = "http://${getLocalIp()}:$SERVER_PORT"
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        } else {
            status.text = "Stopped"
            status.setTextColor(0xFF888888.toInt())
            url.text = ""
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
    }

    @Suppress("DEPRECATION")
    private fun getLocalIp(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
}
