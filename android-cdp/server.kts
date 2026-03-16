#!/usr/bin/env kotlin

// Android CDP Dashboard - See all connected Android devices
// Run: kotlin server.kts
// Open: http://localhost:9222

import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

val ADB = "/Users/billiboss/android-sdk/platform-tools/adb"
val PORT = 9222

// ─── ADB helpers ───

fun adb(vararg args: String): String {
    val proc = ProcessBuilder(ADB, *args).redirectErrorStream(true).start()
    val out = proc.inputStream.bufferedReader().readText()
    proc.waitFor()
    return out.trim()
}

fun adbFor(serial: String, vararg args: String): String =
    adb("-s", serial, *args)

fun adbBytesFor(serial: String, vararg args: String): ByteArray {
    val proc = ProcessBuilder(ADB, "-s", serial, *args).redirectErrorStream(false).start()
    val bytes = proc.inputStream.readBytes()
    proc.waitFor()
    return bytes
}

// ─── Device discovery ───

data class DeviceInfo(
    val serial: String,
    val model: String,
    val android: String,
    val sdk: String,
    val screenW: Int,
    val screenH: Int,
    val ip: String
)

fun listDevices(): List<DeviceInfo> {
    val raw = adb("devices", "-l")
    return raw.lines().drop(1)
        .filter { it.contains("device") && !it.contains("offline") && !it.contains("unauthorized") }
        .mapNotNull { line ->
            val serial = line.split("\\s+".toRegex()).firstOrNull() ?: return@mapNotNull null
            try {
                val model = adbFor(serial, "shell", "getprop", "ro.product.model")
                val android = adbFor(serial, "shell", "getprop", "ro.build.version.release")
                val sdk = adbFor(serial, "shell", "getprop", "ro.build.version.sdk")
                val sizeRaw = adbFor(serial, "shell", "wm", "size")
                val match = Regex("""(\d+)x(\d+)""").find(sizeRaw)
                val w = match?.groupValues?.get(1)?.toInt() ?: 720
                val h = match?.groupValues?.get(2)?.toInt() ?: 1560
                val ipRaw = adbFor(serial, "shell", "ip", "addr", "show", "wlan0")
                val ipMatch = Regex("""inet (\d+\.\d+\.\d+\.\d+)""").find(ipRaw)
                val ip = ipMatch?.groupValues?.get(1) ?: "N/A"
                DeviceInfo(serial, model, android, sdk, w, h, ip)
            } catch (e: Exception) { null }
        }
}

fun getRecentApps(serial: String): List<Map<String, String>> {
    val raw = adbFor(serial, "shell", "dumpsys", "activity", "recents")
    val results = mutableListOf<Map<String, String>>()
    val regex = Regex("""Recent #(\d+).*?A=\d+:(\S+).*?StackId=(\d+)""")
    for (m in regex.findAll(raw)) {
        val pkg = m.groupValues[2]
        if (pkg.contains("launcher") || pkg.contains("FallbackHome") || pkg.contains("quickstep")) continue
        results.add(mapOf("index" to m.groupValues[1], "package" to pkg))
    }
    return results
}

fun getCurrentApp(serial: String): String {
    val raw = adbFor(serial, "shell", "dumpsys", "activity", "activities")
    val line = raw.lines().firstOrNull { it.contains("mResumedActivity") } ?: return "unknown"
    val match = Regex("""(\S+)/(\S+)""").find(line)
    return match?.groupValues?.get(1) ?: "unknown"
}

fun getInstalledApps(serial: String): List<String> {
    return adbFor(serial, "shell", "pm", "list", "packages", "-3")
        .lines().map { it.removePrefix("package:") }.filter { it.isNotBlank() }.sorted()
}

fun screenshot(serial: String): ByteArray = adbBytesFor(serial, "exec-out", "screencap", "-p")

fun tap(serial: String, x: Int, y: Int) = adbFor(serial, "shell", "input", "tap", "$x", "$y")
fun swipe(serial: String, x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) =
    adbFor(serial, "shell", "input", "swipe", "$x1", "$y1", "$x2", "$y2", "$ms")
fun keyEvent(serial: String, code: Int) = adbFor(serial, "shell", "input", "keyevent", "$code")
fun textInput(serial: String, text: String) = adbFor(serial, "shell", "input", "text", text.replace(" ", "%s"))
fun launchApp(serial: String, pkg: String) =
    adbFor(serial, "shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")

fun uiDump(serial: String): String {
    adbFor(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adbFor(serial, "shell", "cat", "/sdcard/ui.xml")
}

// ─── HTTP ───

fun sendResponse(out: OutputStream, status: Int, ct: String, body: ByteArray) {
    val st = when (status) { 200 -> "OK"; 204 -> "No Content"; else -> "Not Found" }
    val h = "HTTP/1.1 $status $st\r\nContent-Type: $ct\r\nContent-Length: ${body.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
    out.write(h.toByteArray()); out.write(body); out.flush()
}
fun sendJson(out: OutputStream, j: String) = sendResponse(out, 200, "application/json", j.toByteArray())
fun sendHtml(out: OutputStream, h: String) = sendResponse(out, 200, "text/html;charset=utf-8", h.toByteArray())
fun sendImg(out: OutputStream, b: ByteArray) {
    val ct = if (b.size > 2 && b[0] == 0xFF.toByte()) "image/jpeg" else "image/png"
    sendResponse(out, 200, ct, b)
}

// ─── Dashboard HTML ───

val DASHBOARD_HTML = """
<!DOCTYPE html><html><head><meta charset="utf-8">
<title>Android CDP Dashboard</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:#0a0a1a;color:#e0e0e0;font-family:'SF Mono',monospace;padding:20px}
h1{color:#e94560;margin-bottom:4px;font-size:22px}
.subtitle{color:#555;font-size:12px;margin-bottom:20px}
.devices{display:flex;gap:16px;flex-wrap:wrap}
.device-card{background:#16213e;border:2px solid #0f3460;border-radius:12px;padding:16px;width:320px;cursor:pointer;transition:all .2s}
.device-card:hover{border-color:#e94560;transform:translateY(-2px)}
.device-card.active{border-color:#2ecc71}
.device-card img{width:100%;border-radius:8px;margin-top:12px;background:#000;min-height:200px}
.device-name{font-size:16px;color:#e94560;font-weight:bold}
.device-meta{font-size:11px;color:#888;margin-top:4px}
.device-app{font-size:12px;color:#2ecc71;margin-top:6px}
.badge{display:inline-block;padding:2px 8px;border-radius:10px;font-size:10px;background:#0f3460;color:#ccc;margin:2px}
.badge.active{background:#2ecc71;color:#000}
#detail{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:#0a0a1a;z-index:100}
#detail .top-bar{display:flex;align-items:center;gap:12px;padding:12px 16px;background:#16213e;border-bottom:1px solid #0f3460}
#detail .top-bar h2{color:#e94560;font-size:16px;flex:1}
.back-btn{background:#0f3460;color:#e0e0e0;border:none;padding:8px 16px;border-radius:6px;cursor:pointer;font-family:inherit;font-size:13px}
.back-btn:hover{background:#e94560}
#detail .content{display:flex;height:calc(100vh - 50px)}
#detail .sidebar{width:300px;background:#16213e;padding:12px;overflow-y:auto;display:flex;flex-direction:column;gap:10px}
#detail .screen-area{flex:1;display:flex;align-items:center;justify-content:center;background:#000}
#detail .screen-area img{max-height:90vh;cursor:crosshair;border:2px solid #0f3460;border-radius:8px}
.section{background:#1a1a2e;border-radius:6px;padding:10px}
.section h3{font-size:12px;color:#0f3460;border-bottom:1px solid #0f3460;padding-bottom:4px;margin-bottom:8px}
.btn{background:#0f3460;color:#e0e0e0;border:none;padding:6px 10px;border-radius:4px;cursor:pointer;font-family:inherit;font-size:11px}
.btn:hover{background:#e94560}
.btn-row{display:flex;gap:4px;flex-wrap:wrap}
.app-list{max-height:200px;overflow-y:auto;font-size:11px}
.app-item{padding:4px 6px;cursor:pointer;border-radius:3px;display:flex;justify-content:space-between}
.app-item:hover{background:#0f3460}
.app-item.current{color:#2ecc71;font-weight:bold}
input[type=text]{background:#0a0a1a;border:1px solid #0f3460;color:#e0e0e0;padding:5px 8px;border-radius:4px;font-family:inherit;font-size:11px;width:100%}
#log{background:#0a0a1a;border:1px solid #0f3460;border-radius:4px;padding:6px;font-size:10px;height:100px;overflow-y:auto;white-space:pre-wrap}
#coords{position:fixed;bottom:8px;right:8px;background:#0f3460;padding:3px 8px;border-radius:4px;font-size:11px;z-index:200}
.refresh-info{font-size:10px;color:#555;text-align:center;margin-top:12px}
</style></head><body>
<h1>Android CDP Dashboard</h1>
<p class="subtitle">Connected devices auto-refresh every 5s</p>
<div class="devices" id="devices">Loading...</div>
<div class="refresh-info" id="refresh-info"></div>

<!-- Detail view -->
<div id="detail">
  <div class="top-bar">
    <button class="back-btn" onclick="closeDetail()">← Back</button>
    <h2 id="detail-title">Device</h2>
    <span id="detail-fps" style="color:#888;font-size:11px">-- fps</span>
  </div>
  <div class="content">
    <div class="sidebar">
      <div class="section">
        <h3>Controls</h3>
        <div class="btn-row">
          <button class="btn" onclick="dk(4)">Back</button>
          <button class="btn" onclick="dk(3)">Home</button>
          <button class="btn" onclick="dk(187)">Recents</button>
          <button class="btn" onclick="dk(26)">Power</button>
          <button class="btn" onclick="dk(24)">Vol+</button>
          <button class="btn" onclick="dk(25)">Vol-</button>
        </div>
        <input type="text" id="text-input" placeholder="Type and press Enter" style="margin-top:8px">
      </div>
      <div class="section">
        <h3>Running Apps</h3>
        <div class="app-list" id="recent-apps"></div>
      </div>
      <div class="section">
        <h3>All Apps</h3>
        <input type="text" id="app-search" placeholder="Search..." oninput="filterApps()">
        <div class="app-list" id="all-apps" style="margin-top:4px"></div>
      </div>
      <div class="section">
        <h3>UI Inspector</h3>
        <button class="btn" onclick="dumpUI()">Dump</button>
        <div id="ui-tree" style="max-height:150px;overflow:auto;font-size:10px;margin-top:4px"></div>
      </div>
      <div class="section">
        <h3>Log</h3>
        <div id="log"></div>
      </div>
    </div>
    <div class="screen-area">
      <img id="dscreen" src="" draggable="false">
    </div>
  </div>
</div>
<div id="coords" style="display:none">-</div>

<script>
let devices = [];
let currentSerial = null;
let autoTimer = null;
let frameCount = 0, lastFpsT = Date.now();

// Dashboard
async function refreshDevices() {
  const res = await fetch('/api/devices');
  devices = await res.json();
  const el = document.getElementById('devices');
  if (!devices.length) { el.innerHTML = '<p style="color:#888">No devices connected. Connect via USB or ADB WiFi.</p>'; return; }
  el.innerHTML = devices.map(d => `
    <div class="device-card" onclick="openDevice('` + d.serial + `')">
      <div class="device-name">` + d.model + `</div>
      <div class="device-meta">` + d.serial + ` · Android ` + d.android + ` · SDK ` + d.sdk + `</div>
      <div class="device-meta">` + d.screenW + `x` + d.screenH + ` · IP: ` + d.ip + `</div>
      <div class="device-app" id="cur-` + d.serial.replace(/[:.]/g,'_') + `">loading...</div>
      <img src="/api/screenshot?serial=` + encodeURIComponent(d.serial) + `&t=` + Date.now() + `" alt="screen">
    </div>`).join('');
  // Load current app for each
  devices.forEach(async d => {
    const r = await fetch('/api/current?serial=' + encodeURIComponent(d.serial));
    const data = await r.json();
    const el = document.getElementById('cur-' + d.serial.replace(/[:.]/g,'_'));
    if (el) el.textContent = '▶ ' + data.app;
  });
  document.getElementById('refresh-info').textContent = devices.length + ' device(s) · ' + new Date().toLocaleTimeString();
}

setInterval(refreshDevices, 5000);
refreshDevices();

// Detail view
async function openDevice(serial) {
  currentSerial = serial;
  const d = devices.find(x => x.serial === serial);
  document.getElementById('detail').style.display = 'block';
  document.getElementById('detail-title').textContent = d ? d.model + ' (' + serial + ')' : serial;
  window._DW = d ? d.screenW : 720;
  window._DH = d ? d.screenH : 1560;
  loadRecentApps();
  loadAllApps();
  startStream();
}

function closeDetail() {
  document.getElementById('detail').style.display = 'none';
  currentSerial = null;
  if (autoTimer) { clearInterval(autoTimer); autoTimer = null; }
}

function startStream() {
  if (autoTimer) clearInterval(autoTimer);
  frameCount = 0; lastFpsT = Date.now();
  autoTimer = setInterval(captureFrame, 600);
  captureFrame();
}

async function captureFrame() {
  if (!currentSerial) return;
  const img = document.getElementById('dscreen');
  const t = Date.now();
  try {
    const resp = await fetch('/api/screenshot?serial=' + encodeURIComponent(currentSerial) + '&t=' + t);
    const blob = await resp.blob();
    if (blob.size < 100) return;
    const url = URL.createObjectURL(blob);
    img.onload = () => { URL.revokeObjectURL(url); frameCount++; };
    img.src = url;
  } catch(e) {}
  // FPS
  const elapsed = (Date.now() - lastFpsT) / 1000;
  if (elapsed > 2) {
    document.getElementById('detail-fps').textContent = (frameCount / elapsed).toFixed(1) + ' fps';
    frameCount = 0; lastFpsT = Date.now();
  }
}

// Input
const dscreen = document.getElementById('dscreen');
let ds = null;
dscreen.addEventListener('mousedown', e => { e.preventDefault(); const r = dscreen.getBoundingClientRect(); ds = { x: (e.clientX-r.left)/r.width*_DW|0, y: (e.clientY-r.top)/r.height*_DH|0, t: Date.now() }; });
dscreen.addEventListener('mouseup', async e => {
  if (!ds||!currentSerial) return;
  const r = dscreen.getBoundingClientRect();
  const ex = (e.clientX-r.left)/r.width*_DW|0, ey = (e.clientY-r.top)/r.height*_DH|0;
  const dist = Math.hypot(ex-ds.x, ey-ds.y);
  if (dist < 10) { addLog('tap '+ds.x+','+ds.y); await fetch('/api/tap?serial='+encodeURIComponent(currentSerial)+'&x='+ds.x+'&y='+ds.y); }
  else { const ms = Math.max(100,Date.now()-ds.t); addLog('swipe'); await fetch('/api/swipe?serial='+encodeURIComponent(currentSerial)+'&x1='+ds.x+'&y1='+ds.y+'&x2='+ex+'&y2='+ey+'&ms='+ms); }
  ds = null; setTimeout(captureFrame, 150);
});
dscreen.addEventListener('mousemove', e => { const r = dscreen.getBoundingClientRect(); document.getElementById('coords').style.display='block'; document.getElementById('coords').textContent = ((e.clientX-r.left)/r.width*_DW|0)+', '+((e.clientY-r.top)/r.height*_DH|0); });
dscreen.addEventListener('dragstart', e => e.preventDefault());

async function dk(code) { if (!currentSerial) return; addLog('key '+code); await fetch('/api/key?serial='+encodeURIComponent(currentSerial)+'&code='+code); setTimeout(captureFrame, 250); }
document.getElementById('text-input').addEventListener('keydown', async e => {
  if (e.key==='Enter' && currentSerial) { const t = e.target.value; if (t) { addLog('text: '+t); await fetch('/api/text?serial='+encodeURIComponent(currentSerial)+'&value='+encodeURIComponent(t)); e.target.value=''; setTimeout(captureFrame, 250); } }
});

// Apps
async function loadRecentApps() {
  const res = await fetch('/api/recents?serial=' + encodeURIComponent(currentSerial));
  const apps = await res.json();
  const cur = await (await fetch('/api/current?serial=' + encodeURIComponent(currentSerial))).json();
  document.getElementById('recent-apps').innerHTML = apps.map(a =>
    '<div class="app-item' + (cur.app === a.package ? ' current' : '') + '" onclick="launchApp(\'' + a.package + '\')">' + a.package + '</div>'
  ).join('') || '<div style="color:#555">No recent apps</div>';
}

let allAppsCache = [];
async function loadAllApps() {
  const res = await fetch('/api/apps?serial=' + encodeURIComponent(currentSerial));
  allAppsCache = await res.json();
  renderApps();
}
function filterApps() { renderApps(); }
function renderApps() {
  const q = document.getElementById('app-search').value.toLowerCase();
  const filtered = allAppsCache.filter(a => a.toLowerCase().includes(q));
  document.getElementById('all-apps').innerHTML = filtered.slice(0, 50).map(a =>
    '<div class="app-item" onclick="launchApp(\'' + a + '\')">' + a.split('.').pop() + '<span style="color:#555">' + a + '</span></div>'
  ).join('');
}

async function launchApp(pkg) {
  addLog('launch: ' + pkg);
  await fetch('/api/launch?serial=' + encodeURIComponent(currentSerial) + '&pkg=' + encodeURIComponent(pkg));
  setTimeout(() => { captureFrame(); loadRecentApps(); }, 1000);
}

// UI
async function dumpUI() {
  addLog('dumping UI...');
  const res = await fetch('/api/ui?serial=' + encodeURIComponent(currentSerial));
  const xml = await res.text();
  const nodes = [];
  const re = /class="([^"]*)".*?text="([^"]*)".*?resource-id="([^"]*)".*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g;
  let m;
  while ((m = re.exec(xml)) !== null) {
    const id = m[3].split('/').pop(), text = m[2], cls = m[1].split('.').pop();
    const b = [m[4],m[5],m[6],m[7]];
    if (text || id) nodes.push('<div class="app-item" onclick="tapXY('+((+b[0]+ +b[2])/2|0)+','+((+b[1]+ +b[3])/2|0)+')">'+(id||cls)+(text?' "'+text+'"':'')+'</div>');
  }
  document.getElementById('ui-tree').innerHTML = nodes.join('') || '<pre>'+xml.substring(0,2000)+'</pre>';
  addLog('UI: '+nodes.length+' elements');
}
async function tapXY(x,y) { addLog('tap '+x+','+y); await fetch('/api/tap?serial='+encodeURIComponent(currentSerial)+'&x='+x+'&y='+y); setTimeout(captureFrame, 200); }

function addLog(m) { const l = document.getElementById('log'); l.textContent += new Date().toLocaleTimeString()+' '+m+'\n'; l.scrollTop = l.scrollHeight; }
</script></body></html>
""".trimIndent()

// ─── Request handler ───

fun handleClient(socket: Socket) {
    try {
        socket.soTimeout = 15000
        val input = socket.getInputStream().bufferedReader()
        val out = socket.getOutputStream()
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(" ")
        val path = parts.getOrElse(1) { "/" }
        // consume headers
        var line = input.readLine(); while (line != null && line.isNotEmpty()) { line = input.readLine() }

        val route = path.substringBefore("?")
        val query = path.substringAfter("?", "")
        val params = query.split("&").filter { it.contains("=") }
            .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        val serial = params["serial"] ?: ""

        when (route) {
            "/" -> sendHtml(out, DASHBOARD_HTML)

            "/api/devices" -> {
                val devs = listDevices()
                val json = devs.joinToString(",", "[", "]") { d ->
                    """{"serial":"${d.serial}","model":"${d.model}","android":"${d.android}","sdk":"${d.sdk}","screenW":${d.screenW},"screenH":${d.screenH},"ip":"${d.ip}"}"""
                }
                sendJson(out, json)
            }

            "/api/screenshot" -> {
                if (serial.isBlank()) { sendResponse(out, 400, "text/plain", "need serial".toByteArray()); return }
                val img = screenshot(serial)
                if (img.size > 100) sendImg(out, img)
                else sendResponse(out, 204, "text/plain", ByteArray(0))
            }

            "/api/current" -> {
                val app = if (serial.isNotBlank()) getCurrentApp(serial) else "unknown"
                sendJson(out, """{"app":"$app"}""")
            }

            "/api/recents" -> {
                val apps = if (serial.isNotBlank()) getRecentApps(serial) else emptyList()
                val json = apps.joinToString(",", "[", "]") { """{"package":"${it["package"]}"}""" }
                sendJson(out, json)
            }

            "/api/apps" -> {
                val apps = if (serial.isNotBlank()) getInstalledApps(serial) else emptyList()
                val json = apps.joinToString(",", "[", "]") { "\"$it\"" }
                sendJson(out, json)
            }

            "/api/tap" -> {
                tap(serial, params["x"]?.toIntOrNull() ?: 0, params["y"]?.toIntOrNull() ?: 0)
                sendJson(out, """{"ok":true}""")
            }
            "/api/swipe" -> {
                swipe(serial, params["x1"]?.toIntOrNull() ?: 0, params["y1"]?.toIntOrNull() ?: 0,
                    params["x2"]?.toIntOrNull() ?: 0, params["y2"]?.toIntOrNull() ?: 0,
                    params["ms"]?.toIntOrNull() ?: 300)
                sendJson(out, """{"ok":true}""")
            }
            "/api/key" -> {
                keyEvent(serial, params["code"]?.toIntOrNull() ?: 0)
                sendJson(out, """{"ok":true}""")
            }
            "/api/text" -> {
                textInput(serial, params["value"] ?: "")
                sendJson(out, """{"ok":true}""")
            }
            "/api/launch" -> {
                launchApp(serial, params["pkg"] ?: "")
                sendJson(out, """{"ok":true}""")
            }
            "/api/ui" -> {
                sendResponse(out, 200, "text/xml", uiDump(serial).toByteArray())
            }
            else -> sendResponse(out, 404, "text/plain", "not found".toByteArray())
        }
    } catch (e: Exception) { } finally { socket.close() }
}

// ─── Main ───

val devices = listDevices()
println("Android CDP Dashboard")
println("Found ${devices.size} device(s):")
devices.forEach { println("  ${it.model} (${it.serial}) - Android ${it.android} - IP ${it.ip}") }
println("\nhttp://localhost:$PORT")

val server = ServerSocket(PORT)
while (true) {
    val client = server.accept()
    Thread { handleClient(client) }.start()
}
