package com.cdp.agent

import android.os.Build
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RelayClient(
    private val relayUrl: String,
    private val deviceId: String,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val latestFrame: AtomicReference<ByteArray>,
    private val latestCameraFrame: AtomicReference<ByteArray>,
    private val audioCapturer: AudioCapturer,
    private val onStartCamera: (Boolean) -> Unit,
    private val onStopCamera: () -> Unit,
    private val onSwitchCamera: () -> Unit,
    private val onStartAudio: () -> Unit,
    private val onStopAudio: () -> Unit,
    private val execCmd: (String) -> String,
    private val logCallback: (String) -> Unit
) {
    companion object {
        private const val TAG = "RelayClient"
        const val DEFAULT_RELAY_URL = "wss://9fbc-2804-7f0-bfc0-f4a9-4832-f3ca-fdc6-1a29.ngrok-free.app/agent"

        // Binary frame type prefixes
        const val FRAME_SCREEN: Byte = 0x01
        const val FRAME_CAMERA: Byte = 0x02
        const val FRAME_AUDIO: Byte = 0x03
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for WebSocket
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val running = AtomicBoolean(false)
    private val viewerCount = AtomicInteger(0)
    private var frameThread: Thread? = null
    private var reconnectDelay = 1000L
    private val cameraStreamActive = AtomicBoolean(false)
    private val audioStreamActive = AtomicBoolean(false)

    fun start() {
        if (running.getAndSet(true)) return
        connect()
    }

    fun stop() {
        running.set(false)
        viewerCount.set(0)
        stopStreams()
        frameThread?.interrupt()
        webSocket?.close(1000, "stopping")
        webSocket = null
    }

    private fun stopStreams() {
        if (cameraStreamActive.getAndSet(false)) onStopCamera()
        if (audioStreamActive.getAndSet(false)) onStopAudio()
    }

    private fun connect() {
        if (!running.get()) return

        val url = relayUrl.ifEmpty { DEFAULT_RELAY_URL }
        log("Relay: connecting to $url")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                reconnectDelay = 1000L
                log("Relay: connected")

                val reg = JSONObject().apply {
                    put("type", "register")
                    put("deviceId", deviceId)
                    put("model", Build.MODEL)
                    put("android", Build.VERSION.RELEASE)
                    put("screen", "${screenWidth}x${screenHeight}")
                    put("capabilities", JSONArray().put("screen").put("camera").put("audio"))
                }
                ws.send(reg.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "viewers" -> {
                            val count = msg.optInt("count", 0)
                            val prev = viewerCount.getAndSet(count)
                            log("Relay: $count viewer(s)")
                            if (count > 0 && prev == 0) startFrameLoop()
                            if (count == 0) {
                                stopFrameLoop()
                                stopStreams()
                            }
                        }
                        "tap" -> {
                            val x = msg.optInt("x", 0)
                            val y = msg.optInt("y", 0)
                            execCmd("input tap $x $y")
                        }
                        "swipe" -> {
                            val x1 = msg.optInt("x1", 0)
                            val y1 = msg.optInt("y1", 0)
                            val x2 = msg.optInt("x2", 0)
                            val y2 = msg.optInt("y2", 0)
                            val ms = msg.optInt("ms", 300)
                            execCmd("input swipe $x1 $y1 $x2 $y2 $ms")
                        }
                        "key" -> {
                            val code = msg.optInt("code", 0)
                            execCmd("input keyevent $code")
                        }
                        "text" -> {
                            val value = msg.optString("value", "")
                            execCmd("input text '${value.replace("'", "'\\''")}'")
                        }
                        "launch" -> {
                            val pkg = msg.optString("package", "")
                            if (pkg.isNotEmpty()) {
                                execCmd("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                            }
                        }
                        "query" -> {
                            val reqId = msg.optInt("reqId", 0)
                            val cmd = msg.optString("cmd", "")
                            handleQuery(ws, reqId, cmd)
                        }
                        "startCamera" -> {
                            val front = msg.optString("facing", "back") == "front"
                            cameraStreamActive.set(true)
                            onStartCamera(front)
                            log("Relay: camera started (${if (front) "front" else "back"})")
                        }
                        "stopCamera" -> {
                            cameraStreamActive.set(false)
                            onStopCamera()
                            log("Relay: camera stopped")
                        }
                        "switchCamera" -> {
                            onSwitchCamera()
                            log("Relay: camera switched")
                        }
                        "startAudio" -> {
                            audioStreamActive.set(true)
                            onStartAudio()
                            log("Relay: audio started")
                        }
                        "stopAudio" -> {
                            audioStreamActive.set(false)
                            onStopAudio()
                            log("Relay: audio stopped")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling message", e)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                log("Relay: connection failed - ${t.message}")
                stopStreams()
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                log("Relay: disconnected ($code)")
                stopFrameLoop()
                stopStreams()
                scheduleReconnect()
            }
        })
    }

    private fun handleQuery(ws: WebSocket, reqId: Int, cmd: String) {
        Thread {
            try {
                val data: Any = when (cmd) {
                    "current" -> {
                        val result = execCmd("dumpsys activity activities | grep mResumedActivity | head -1")
                        val app = Regex("\\{[^ ]+ [^ ]+ ([^/]+)").find(result)?.groupValues?.get(1) ?: "unknown"
                        JSONObject().put("app", app)
                    }
                    "recents" -> {
                        val result = execCmd("dumpsys activity recents | grep 'Recent #' | head -10")
                        val arr = JSONArray()
                        Regex("A=\\d+:([^\\s}]+)").findAll(result).forEach { m ->
                            arr.put(JSONObject().put("package", m.groupValues[1]))
                        }
                        arr
                    }
                    "apps" -> {
                        val result = execCmd("pm list packages -3")
                        val arr = JSONArray()
                        result.lines().filter { it.startsWith("package:") }.sorted().forEach { line ->
                            arr.put(line.removePrefix("package:"))
                        }
                        arr
                    }
                    else -> JSONObject()
                }
                val response = JSONObject().apply {
                    put("type", "response")
                    put("reqId", reqId)
                    put("data", data)
                }
                ws.send(response.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Query error: $cmd", e)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun sendPrefixedFrame(ws: WebSocket, prefix: Byte, payload: ByteArray) {
        val prefixed = ByteArray(1 + payload.size)
        prefixed[0] = prefix
        System.arraycopy(payload, 0, prefixed, 1, payload.size)
        ws.send(ByteString.of(*prefixed))
    }

    private fun startFrameLoop() {
        stopFrameLoop()
        frameThread = Thread {
            log("Relay: streaming frames")
            try {
                while (running.get() && viewerCount.get() > 0) {
                    val ws = webSocket ?: break

                    // Screen frames — only when camera is NOT active
                    if (!cameraStreamActive.get()) {
                        val frame = latestFrame.get()
                        if (frame.isNotEmpty()) {
                            sendPrefixedFrame(ws, FRAME_SCREEN, frame)
                        }
                    }

                    // Camera frames — only when camera IS active
                    if (cameraStreamActive.get()) {
                        val camFrame = latestCameraFrame.get()
                        if (camFrame.isNotEmpty()) {
                            sendPrefixedFrame(ws, FRAME_CAMERA, camFrame)
                        }
                    }

                    // Audio chunks — independent of screen/camera
                    if (audioStreamActive.get()) {
                        val chunks = audioCapturer.drainEncodedChunks()
                        for (chunk in chunks) {
                            sendPrefixedFrame(ws, FRAME_AUDIO, chunk)
                        }
                    }

                    Thread.sleep(100) // ~10 fps
                }
            } catch (_: InterruptedException) {
                // stopped
            }
            log("Relay: stopped streaming")
        }.apply { isDaemon = true; start() }
    }

    private fun stopFrameLoop() {
        frameThread?.interrupt()
        frameThread = null
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val delay = reconnectDelay
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(30000L)
        log("Relay: reconnecting in ${delay}ms")
        Thread {
            try {
                Thread.sleep(delay)
                connect()
            } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        logCallback(msg)
    }
}
