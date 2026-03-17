const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;

// State: connected agents and viewers
const agents = new Map();       // deviceId -> ws
const viewers = new Map();      // deviceId -> Set<ws>
const viewerDevice = new Map(); // viewer ws -> deviceId
const pendingQueries = new Map(); // reqId -> viewer ws

const server = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');

  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, agents: agents.size }));
    return;
  }

  // Serve static files from public/
  let filePath = req.url === '/' ? '/index.html' : req.url;
  filePath = path.join(__dirname, 'public', filePath);

  const ext = path.extname(filePath);
  const mimeTypes = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'application/javascript',
    '.css': 'text/css',
    '.png': 'image/png',
    '.ico': 'image/x-icon',
  };

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Not found');
      return;
    }
    res.writeHead(200, { 'Content-Type': mimeTypes[ext] || 'application/octet-stream' });
    res.end(data);
  });
});

const wssAgent = new WebSocketServer({ noServer: true });
const wssViewer = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  if (req.url === '/agent') {
    wssAgent.handleUpgrade(req, socket, head, (ws) => wssAgent.emit('connection', ws));
  } else if (req.url === '/viewer') {
    wssViewer.handleUpgrade(req, socket, head, (ws) => wssViewer.emit('connection', ws));
  } else {
    socket.destroy();
  }
});

// --- Agent connections ---
wssAgent.on('connection', (ws) => {
  let deviceId = null;
  console.log('[agent] connected');

  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      // JPEG screenshot — zero-copy forward to viewers
      if (!deviceId) return;
      const dvs = viewers.get(deviceId);
      if (!dvs) return;
      for (const v of dvs) {
        if (v.readyState === 1) v.send(data, { binary: true });
      }
      return;
    }

    try {
      const msg = JSON.parse(data.toString());
      if (msg.type === 'register') {
        deviceId = msg.deviceId;
        ws._deviceInfo = { model: msg.model, android: msg.android, screen: msg.screen, capabilities: msg.capabilities || ['screen'] };
        agents.set(deviceId, ws);
        console.log(`[agent] registered: ${deviceId} (${msg.model})`);
        broadcastDeviceList();
        sendViewerCount(deviceId);
      } else if (msg.type === 'response' && msg.reqId) {
        // Forward query response to the viewer that requested it
        const viewerWs = pendingQueries.get(msg.reqId);
        if (viewerWs && viewerWs.readyState === 1) {
          viewerWs.send(JSON.stringify(msg));
        }
        pendingQueries.delete(msg.reqId);
      }
    } catch (e) {
      console.error('[agent] bad message:', e.message);
    }
  });

  ws.on('close', () => {
    console.log(`[agent] disconnected: ${deviceId}`);
    if (deviceId) {
      agents.delete(deviceId);
      broadcastDeviceList();
    }
  });

  ws.on('error', (e) => console.error('[agent] error:', e.message));
});

// --- Viewer connections ---
wssViewer.on('connection', (ws) => {
  console.log('[viewer] connected');

  ws.on('message', (data, isBinary) => {
    if (isBinary) return;

    try {
      const msg = JSON.parse(data.toString());

      if (msg.type === 'list') {
        sendDeviceList(ws);
        return;
      }

      if (msg.type === 'connect') {
        disconnectViewer(ws);
        const did = msg.deviceId;
        viewerDevice.set(ws, did);
        if (!viewers.has(did)) viewers.set(did, new Set());
        viewers.get(did).add(ws);
        console.log(`[viewer] watching ${did}`);
        sendViewerCount(did);
        return;
      }

      if (msg.type === 'disconnect') {
        disconnectViewer(ws);
        return;
      }

      // Query messages — forward to agent and track for response routing
      if (msg.type === 'query' && msg.reqId) {
        const did = viewerDevice.get(ws);
        if (did) {
          const agentWs = agents.get(did);
          if (agentWs && agentWs.readyState === 1) {
            pendingQueries.set(msg.reqId, ws);
            agentWs.send(JSON.stringify(msg));
          }
        }
        return;
      }

      // Commands (tap, swipe, key, text, launch) — forward to agent
      const did = viewerDevice.get(ws);
      if (did) {
        const agentWs = agents.get(did);
        if (agentWs && agentWs.readyState === 1) {
          agentWs.send(JSON.stringify(msg));
        }
      }
    } catch (e) {
      console.error('[viewer] bad message:', e.message);
    }
  });

  ws.on('close', () => {
    disconnectViewer(ws);
    viewerDevice.delete(ws);
    console.log('[viewer] disconnected');
  });

  ws.on('error', (e) => console.error('[viewer] error:', e.message));
});

function disconnectViewer(ws) {
  const did = viewerDevice.get(ws);
  if (!did) return;
  const set = viewers.get(did);
  if (set) {
    set.delete(ws);
    if (set.size === 0) viewers.delete(did);
    sendViewerCount(did);
  }
  viewerDevice.delete(ws);
}

function sendViewerCount(deviceId) {
  const agentWs = agents.get(deviceId);
  if (agentWs && agentWs.readyState === 1) {
    const count = viewers.has(deviceId) ? viewers.get(deviceId).size : 0;
    agentWs.send(JSON.stringify({ type: 'viewers', count }));
  }
}

function getDeviceList() {
  const list = [];
  for (const [deviceId, ws] of agents) {
    list.push({ deviceId, ...(ws._deviceInfo || {}) });
  }
  return list;
}

function sendDeviceList(ws) {
  ws.send(JSON.stringify({ type: 'devices', list: getDeviceList() }));
}

function broadcastDeviceList() {
  const msg = JSON.stringify({ type: 'devices', list: getDeviceList() });
  for (const client of wssViewer.clients) {
    if (client.readyState === 1) client.send(msg);
  }
}

server.listen(PORT, () => {
  console.log(`CDP Relay running on port ${PORT}`);
  console.log(`  Agent:  ws://localhost:${PORT}/agent`);
  console.log(`  Viewer: ws://localhost:${PORT}/viewer`);
  console.log(`  UI:     http://localhost:${PORT}/`);
});
