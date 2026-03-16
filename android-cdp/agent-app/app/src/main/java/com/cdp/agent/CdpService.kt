package com.cdp.agent

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference

class CdpService : Service() {

    companion object {
        var isRunning = false
        var logCallback: ((String) -> Unit)? = null
        private const val CHANNEL_ID = "cdp_channel"
        private const val NOTIFICATION_ID = 1
        private const val PORT = 9223
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var serverSocket: ServerSocket? = null
    private val latestFrame = AtomicReference<ByteArray>(ByteArray(0))
    private var screenWidth = 720
    private var screenHeight = 1560
    private var screenDpi = 320

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Get screen metrics
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDpi = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        startScreenCapture()
        startHttpServer()

        isRunning = true
        log("CDP Agent started on port $PORT")
        return START_STICKY
    }

    private fun startScreenCapture() {
        // Use smaller size for performance
        val captureWidth = screenWidth / 2
        val captureHeight = screenHeight / 2

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * captureWidth

                    val bitmap = Bitmap.createBitmap(
                        captureWidth + rowPadding / pixelStride,
                        captureHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    // Crop to actual size and compress to JPEG
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, captureWidth, captureHeight)
                    val baos = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                    latestFrame.set(baos.toByteArray())

                    if (cropped !== bitmap) cropped.recycle()
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                // ignore frame errors
            } finally {
                image?.close()
            }
        }, null)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "CDPCapture",
            captureWidth, captureHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        log("Screen capture started: ${captureWidth}x${captureHeight}")
    }

    private fun startHttpServer() {
        Thread {
            try {
                serverSocket = ServerSocket(PORT)
                log("HTTP server listening on port $PORT")
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        Thread { handleClient(client) }.start()
                    } catch (e: Exception) {
                        if (isRunning) log("Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                log("Server error: ${e.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10000
            val input = socket.getInputStream().bufferedReader()
            val out = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            val path = parts.getOrElse(1) { "/" }

            // Consume headers
            var line = input.readLine()
            while (line != null && line.isNotEmpty()) { line = input.readLine() }

            val route = path.substringBefore("?")
            val query = path.substringAfter("?", "")
            val params = query.split("&").filter { it.contains("=") }
                .associate { it.substringBefore("=") to it.substringAfter("=") }

            when (route) {
                "/" -> sendHtml(out)
                "/screenshot" -> {
                    val frame = latestFrame.get()
                    if (frame.isNotEmpty()) {
                        sendResponse(out, 200, "image/jpeg", frame)
                    } else {
                        sendResponse(out, 204, "text/plain", ByteArray(0))
                    }
                }
                "/tap" -> {
                    val x = params["x"]?.toIntOrNull() ?: 0
                    val y = params["y"]?.toIntOrNull() ?: 0
                    execCmd("input tap $x $y")
                    sendJson(out, """{"ok":true}""")
                }
                "/swipe" -> {
                    val x1 = params["x1"]?.toIntOrNull() ?: 0
                    val y1 = params["y1"]?.toIntOrNull() ?: 0
                    val x2 = params["x2"]?.toIntOrNull() ?: 0
                    val y2 = params["y2"]?.toIntOrNull() ?: 0
                    val ms = params["ms"]?.toIntOrNull() ?: 300
                    execCmd("input swipe $x1 $y1 $x2 $y2 $ms")
                    sendJson(out, """{"ok":true}""")
                }
                "/key" -> {
                    val code = params["code"]?.toIntOrNull() ?: 0
                    execCmd("input keyevent $code")
                    sendJson(out, """{"ok":true}""")
                }
                "/text" -> {
                    val value = URLDecoder.decode(params["value"] ?: "", "UTF-8")
                    execCmd("input text '${value.replace("'", "'\\''")}'")
                    sendJson(out, """{"ok":true}""")
                }
                "/info" -> {
                    sendJson(out, """{"model":"${Build.MODEL}","android":"${Build.VERSION.RELEASE}","sdk":${Build.VERSION.SDK_INT},"screen":"${screenWidth}x${screenHeight}"}""")
                }
                else -> sendResponse(out, 404, "text/plain", "not found".toByteArray())
            }
        } catch (e: Exception) {
            // client disconnected
        } finally {
            socket.close()
        }
    }

    private fun execCmd(cmd: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val result = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            result.trim()
        } catch (e: Exception) {
            e.message ?: "error"
        }
    }

    private fun sendResponse(out: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val statusText = when (status) { 200 -> "OK"; 204 -> "No Content"; 404 -> "Not Found"; else -> "Error" }
        val header = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun sendJson(out: OutputStream, json: String) =
        sendResponse(out, 200, "application/json", json.toByteArray())

    private fun sendHtml(out: OutputStream) {
        val html = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CDP Agent</title>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { background: #1a1a2e; color: #e0e0e0; font-family: monospace; display: flex; flex-direction: column; align-items: center; min-height: 100vh; padding: 16px; }
h1 { color: #e94560; margin-bottom: 8px; }
#info { color: #888; font-size: 12px; margin-bottom: 16px; }
#screen { cursor: crosshair; border: 2px solid #0f3460; border-radius: 8px; max-width: 90vw; max-height: 70vh; touch-action: none; }
.controls { display: flex; gap: 8px; flex-wrap: wrap; justify-content: center; margin: 16px 0; }
.btn { background: #0f3460; color: #e0e0e0; border: none; padding: 10px 16px; border-radius: 6px; cursor: pointer; font-size: 14px; }
.btn:hover, .btn:active { background: #e94560; }
#log { background: #0a0a1a; border: 1px solid #0f3460; border-radius: 6px; padding: 8px; font-size: 11px; width: 100%; max-width: 600px; height: 100px; overflow-y: auto; margin-top: 16px; }
</style>
</head>
<body>
<h1>CDP Agent</h1>
<div id="info">connecting...</div>
<img id="screen" src="/screenshot">
<div class="controls">
  <button class="btn" onclick="key(4)">Back</button>
  <button class="btn" onclick="key(3)">Home</button>
  <button class="btn" onclick="key(187)">Recents</button>
  <button class="btn" onclick="key(24)">Vol+</button>
  <button class="btn" onclick="key(25)">Vol-</button>
</div>
<div id="log"></div>
<script>
const screen = document.getElementById('screen');
const W = ${screenWidth}, H = ${screenHeight};
function addLog(m) { const l = document.getElementById('log'); l.textContent += m + '\n'; l.scrollTop = l.scrollHeight; }
function capture() { screen.src = '/screenshot?t=' + Date.now(); }
setInterval(capture, 500);
let ds = null;
screen.onpointerdown = (e) => { e.preventDefault(); const r = screen.getBoundingClientRect(); ds = { x: (e.clientX-r.left)/r.width*W|0, y: (e.clientY-r.top)/r.height*H|0, t: Date.now() }; };
screen.onpointerup = async (e) => { if (!ds) return; const r = screen.getBoundingClientRect(); const ex = (e.clientX-r.left)/r.width*W|0, ey = (e.clientY-r.top)/r.height*H|0; const d = Math.hypot(ex-ds.x, ey-ds.y); if (d < 10) { addLog('tap '+ds.x+','+ds.y); await fetch('/tap?x='+ds.x+'&y='+ds.y); } else { const ms = Math.max(100, Date.now()-ds.t); addLog('swipe'); await fetch('/swipe?x1='+ds.x+'&y1='+ds.y+'&x2='+ex+'&y2='+ey+'&ms='+ms); } ds = null; setTimeout(capture, 200); };
screen.ondragstart = (e) => e.preventDefault();
async function key(c) { addLog('key '+c); await fetch('/key?code='+c); setTimeout(capture, 300); }
fetch('/info').then(r=>r.json()).then(i => { document.getElementById('info').textContent = i.model + ' | Android ' + i.android; });
addLog('ready');
</script>
</body>
</html>
""".trimIndent()
        sendResponse(out, 200, "text/html; charset=utf-8", html.toByteArray())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "CDP Agent", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("CDP Agent")
            .setContentText("Screen capture active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun log(msg: String) {
        logCallback?.invoke(msg)
    }

    override fun onDestroy() {
        isRunning = false
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        serverSocket?.close()
        log("CDP Agent stopped")
        super.onDestroy()
    }
}
