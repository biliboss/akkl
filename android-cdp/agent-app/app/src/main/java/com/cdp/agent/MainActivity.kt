package com.cdp.agent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val REQUEST_PERMISSIONS = 1002
        const val SERVER_PORT = 9223
    }

    private lateinit var status: TextView
    private lateinit var url: TextView
    private lateinit var relayStatus: TextView
    private lateinit var log: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        url = findViewById(R.id.url)
        relayStatus = findViewById(R.id.relayStatus)
        log = findViewById(R.id.log)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        btnStart.setOnClickListener { checkPermissionsAndStart() }
        btnStop.setOnClickListener { stopService() }

        CdpService.logCallback = { msg ->
            runOnUiThread {
                log.append("$msg\n")
                if (msg.startsWith("Relay: connected")) {
                    relayStatus.text = "Relay: connected"
                    relayStatus.setTextColor(0xFF2ecc71.toInt())
                } else if (msg.startsWith("Relay: disconnected") || msg.startsWith("Relay: connection failed")) {
                    relayStatus.text = "Relay: disconnected"
                    relayStatus.setTextColor(0xFFe74c3c.toInt())
                } else if (msg.startsWith("Relay: reconnecting")) {
                    relayStatus.text = "Relay: reconnecting..."
                    relayStatus.setTextColor(0xFFf39c12.toInt())
                } else if (msg.contains("viewer(s)")) {
                    relayStatus.text = msg
                    relayStatus.setTextColor(0xFF2ecc71.toInt())
                }
            }
        }

        updateUI()
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            requestScreenCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            // Proceed even if camera/mic denied — screen capture still works
            requestScreenCapture()
        }
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
            relayStatus.text = "Relay: connecting..."
            relayStatus.setTextColor(0xFFe0e0e0.toInt())
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        } else {
            status.text = "Stopped"
            status.setTextColor(0xFF888888.toInt())
            url.text = ""
            relayStatus.text = ""
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
