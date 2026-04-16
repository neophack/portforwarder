package com.aucneon.portforwarder;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.text.TextUtils;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny offline HTTP server for diagnostics and forwarding verification.
 */
public class HelloWebServer {
    private static final String TAG = "HelloWebServer";

    private final Context appContext;
    private final int port;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService clientExecutor;

    public HelloWebServer(Context context, int port) {
        this.appContext = context.getApplicationContext();
        this.port = port;
    }

    public synchronized boolean start() {
        if (running) {
            return true;
        }

        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
            clientExecutor = Executors.newCachedThreadPool();
            running = true;

            acceptThread = new Thread(this::acceptLoop, "hello-web-accept");
            acceptThread.start();

            Log.i(TAG, "HelloWeb server started on port " + port);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start hello web server", e);
            running = false;
            closeQuietly(serverSocket);
            serverSocket = null;
            if (clientExecutor != null) {
                clientExecutor.shutdownNow();
                clientExecutor = null;
            }
            return false;
        }
    }

    public synchronized boolean stop() {
        if (!running) {
            return true;
        }

        running = false;
        closeQuietly(serverSocket);
        serverSocket = null;

        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
            clientExecutor = null;
        }

        if (acceptThread != null) {
            try {
                acceptThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            acceptThread = null;
        }

        Log.i(TAG, "HelloWeb server stopped");
        return true;
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                if (clientExecutor != null) {
                    clientExecutor.submit(() -> handleClient(socket));
                } else {
                    closeQuietly(socket);
                }
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "accept failed", e);
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream output = s.getOutputStream()) {

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) {
                return;
            }

            String path = "/";
            String[] firstParts = requestLine.split(" ");
            if (firstParts.length >= 2) {
                path = firstParts[1];
            }

            // Consume headers
            String line;
            while ((line = reader.readLine()) != null && line.length() > 0) {
                // no-op
            }

            if ("/api/info".equals(path)) {
                byte[] body = buildInfoJson().toString(2).getBytes(StandardCharsets.UTF_8);
                writeHttpResponse(output, "200 OK", "application/json; charset=utf-8", body);
                return;
            }

            if ("/favicon.svg".equals(path)) {
                byte[] body = buildFaviconSvg().getBytes(StandardCharsets.UTF_8);
                writeHttpResponse(output, "200 OK", "image/svg+xml; charset=utf-8", body);
                return;
            }

            byte[] body = buildHtml().getBytes(StandardCharsets.UTF_8);
            writeHttpResponse(output, "200 OK", "text/html; charset=utf-8", body);
        } catch (Exception e) {
            Log.w(TAG, "handle client failed", e);
        }
    }

    private void writeHttpResponse(OutputStream output, String status, String contentType, byte[] body) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.write("HTTP/1.1 " + status + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("Content-Type: " + contentType + "\r\n");
        writer.write("Content-Length: " + body.length + "\r\n");
        writer.write("Cache-Control: no-store\r\n");
        writer.write("\r\n");
        writer.flush();
        output.write(body);
        output.flush();
    }

    private JSONObject buildInfoJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));

            JSONObject device = new JSONObject();
            device.put("manufacturer", Build.MANUFACTURER);
            device.put("brand", Build.BRAND);
            device.put("model", Build.MODEL);
            device.put("device", Build.DEVICE);
            device.put("board", Build.BOARD);
            device.put("hardware", Build.HARDWARE);
            device.put("android", Build.VERSION.RELEASE);
            device.put("sdk", Build.VERSION.SDK_INT);
            device.put("abis", TextUtils.join(", ", Build.SUPPORTED_ABIS));
            root.put("device", device);

            JSONObject memory = new JSONObject();
            ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            if (am != null) {
                am.getMemoryInfo(memoryInfo);
                memory.put("totalBytes", memoryInfo.totalMem);
                memory.put("availableBytes", memoryInfo.availMem);
                memory.put("usedBytes", Math.max(0, memoryInfo.totalMem - memoryInfo.availMem));
            }
            root.put("memory", memory);

            JSONObject storage = new JSONObject();
            StatFs dataFs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            long totalStorage = dataFs.getTotalBytes();
            long availStorage = dataFs.getAvailableBytes();
            storage.put("totalBytes", totalStorage);
            storage.put("availableBytes", availStorage);
            storage.put("usedBytes", Math.max(0, totalStorage - availStorage));
            root.put("storage", storage);

            JSONObject cpu = new JSONObject();
            cpu.put("cores", Runtime.getRuntime().availableProcessors());
            cpu.put("model", readCpuModel());
            root.put("cpu", cpu);

            JSONObject gpu = new JSONObject();
            ActivityManager activityManager = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                ConfigurationInfo ci = activityManager.getDeviceConfigurationInfo();
                gpu.put("openGLES", ci != null ? ci.getGlEsVersion() : "unknown");
            } else {
                gpu.put("openGLES", "unknown");
            }
            root.put("gpu", gpu);

            JSONArray forwards = new JSONArray();
            Map<Integer, PortForwarder.ForwardInfo> map = PortForwarder.getAllForwards();
            for (PortForwarder.ForwardInfo info : map.values()) {
                JSONObject item = new JSONObject();
                item.put("sessionId", info.sessionId);
                item.put("protocol", info.getProtocolName());
                item.put("listenPort", info.listenPort);
                item.put("target", info.targetHost + ":" + info.targetPort);
                forwards.put(item);
            }
            root.put("activeForwards", forwards);

            JSONArray openPorts = new JSONArray();
            for (PortEntry entry : collectOpenPorts()) {
                JSONObject item = new JSONObject();
                item.put("protocol", entry.protocol);
                item.put("port", entry.port);
                item.put("source", entry.source);
                openPorts.put(item);
            }
            root.put("openPorts", openPorts);

            root.put("helloServerPort", port);
        } catch (Exception e) {
            Log.e(TAG, "buildInfoJson error", e);
        }
        return root;
    }

    private String buildHtml() {
        return "<!doctype html>\n"
                + "<html lang='zh-CN'>\n"
                + "<head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>\n"
                + "<title>Hello PortForwarder</title>\n"
                + "<style>\n"
                + ":root{--bg:#f3f7fb;--card:#ffffff;--ink:#0f172a;--muted:#64748b;--line:#dbe5ef;--ok:#14b8a6;}\n"
                + "*{box-sizing:border-box}body{margin:0;font-family:-apple-system,BlinkMacSystemFont,'SF Pro Display','PingFang SC',sans-serif;background:radial-gradient(circle at 10% 0,#dbeafe 0,#f3f7fb 45%),var(--bg);color:var(--ink)}\n"
                + ".wrap{max-width:1040px;margin:0 auto;padding:16px} .hero{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:16px;display:flex;gap:14px;align-items:center;box-shadow:0 10px 30px rgba(15,23,42,.07)}\n"
                + ".hero svg{width:72px;height:72px;flex:0 0 72px}.title{font-size:24px;font-weight:700}.sub{color:var(--muted);margin-top:4px}\n"
                + ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px;margin-top:14px}\n"
                + ".card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:14px}\n"
                + ".card h3{margin:0 0 8px;font-size:14px;color:var(--muted);font-weight:600}.kv{display:flex;justify-content:space-between;padding:6px 0;border-bottom:1px dashed #edf2f7}.kv:last-child{border-bottom:none}\n"
                + ".mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}.pill{display:inline-block;padding:2px 8px;border-radius:999px;background:#ecfeff;color:#0f766e;border:1px solid #99f6e4;font-size:12px}\n"
                + ".list{max-height:240px;overflow:auto;border:1px solid var(--line);border-radius:12px;padding:8px;background:#fafcff}.row{padding:6px 0;border-bottom:1px solid #eef3f9}.row:last-child{border-bottom:none}\n"
                + "@media (max-width:720px){.title{font-size:20px}}\n"
                + "</style></head><body>\n"
                + "<div class='wrap'>\n"
                + "<div class='hero'>\n"
                + "<svg viewBox='0 0 120 120' aria-hidden='true'><defs><linearGradient id='g' x1='0' x2='1'><stop offset='0' stop-color='#22d3ee'/><stop offset='1' stop-color='#3b82f6'/></linearGradient></defs><rect x='8' y='8' width='104' height='104' rx='26' fill='url(#g)'/><path d='M36 68h48M36 52h30' stroke='white' stroke-width='8' stroke-linecap='round'/><circle cx='84' cy='52' r='6' fill='#fff'/></svg>\n"
                + "<div><div class='title'>Hello, PortForwarder</div><div class='sub'>离线诊断页面。用于验证远程访问转发器是否连通。</div><div style='margin-top:8px'><span class='pill'>HTTP : " + port + "</span></div></div>\n"
                + "</div>\n"
                + "<div class='grid'>\n"
                + "<section class='card'><h3>设备信息</h3><div id='device'></div></section>\n"
                + "<section class='card'><h3>资源占用</h3><div id='resource'></div></section>\n"
                + "<section class='card'><h3>活动转发</h3><div class='list mono' id='forwards'></div></section>\n"
                + "<section class='card'><h3>开放端口</h3><div class='list mono' id='ports'></div></section>\n"
                + "</div>\n"
                + "</div>\n"
                + "<script>\n"
                + "const fmt=n=>{if(!n&&n!==0)return '--';const u=['B','KB','MB','GB'];let i=0,v=n;while(v>=1024&&i<u.length-1){v/=1024;i++;}return v.toFixed(v>=100||i===0?0:1)+' '+u[i];};\n"
                + "const kv=(k,v)=>`<div class='kv'><span>${k}</span><b>${v}</b></div>`;\n"
                + "function render(d){\n"
                + "document.getElementById('device').innerHTML=[kv('厂商',d.device.manufacturer),kv('型号',d.device.model),kv('系统',`Android ${d.device.android} (SDK ${d.device.sdk})`),kv('ABI',d.device.abis),kv('OpenGL ES',d.gpu.openGLES),kv('更新时间',d.timestamp)].join('');\n"
                + "document.getElementById('resource').innerHTML=[kv('内存已用',fmt(d.memory.usedBytes)),kv('内存可用',fmt(d.memory.availableBytes)),kv('存储已用',fmt(d.storage.usedBytes)),kv('存储可用',fmt(d.storage.availableBytes)),kv('CPU核心',d.cpu.cores),kv('CPU型号',d.cpu.model)].join('');\n"
                + "const f=d.activeForwards||[];document.getElementById('forwards').innerHTML=f.length?f.map(x=>`<div class='row'>[${x.protocol}] ${x.listenPort} -> ${x.target}  (sid:${x.sessionId})</div>`).join(''):'<div class=\"row\">暂无活动转发</div>';\n"
                + "const p=d.openPorts||[];document.getElementById('ports').innerHTML=p.length?p.map(x=>`<div class='row'>${x.protocol.padEnd(4,' ')} : ${x.port} <span style='color:#94a3b8'>(${x.source})</span></div>`).join(''):'<div class=\"row\">暂无可见端口</div>';\n"
                + "}\n"
                + "async function load(){try{const r=await fetch('/api/info',{cache:'no-store'});const d=await r.json();render(d);}catch(e){console.error(e);}}\n"
                + "load();setInterval(load,1500);\n"
                + "</script></body></html>";
    }

    private String buildFaviconSvg() {
        return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'>"
                + "<rect width='64' height='64' rx='14' fill='#3b82f6'/>"
                + "<path d='M16 36h32M16 25h20' stroke='white' stroke-width='5' stroke-linecap='round'/>"
                + "<circle cx='43' cy='25' r='4' fill='white'/></svg>";
    }

    private String readCpuModel() {
        List<String> lines = readLines("/proc/cpuinfo");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.US);
            if (lower.startsWith("hardware") || lower.startsWith("model name") || lower.startsWith("processor")) {
                int idx = line.indexOf(':');
                if (idx >= 0 && idx < line.length() - 1) {
                    return line.substring(idx + 1).trim();
                }
            }
        }
        return Build.HARDWARE;
    }

    private List<String> readLines(String path) {
        File file = new File(path);
        if (!file.exists()) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                list.add(line);
            }
        } catch (Exception ignored) {
            // best effort only
        }
        return list;
    }

    private List<PortEntry> collectOpenPorts() {
        List<PortEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        collectFromProcNet("/proc/net/tcp", "TCP", true, entries, seen);
        collectFromProcNet("/proc/net/tcp6", "TCP6", true, entries, seen);
        collectFromProcNet("/proc/net/udp", "UDP", false, entries, seen);
        collectFromProcNet("/proc/net/udp6", "UDP6", false, entries, seen);

        entries.sort(Comparator.comparingInt(a -> a.port));
        return entries;
    }

    private void collectFromProcNet(String path, String protocol, boolean tcp, List<PortEntry> out, Set<String> seen) {
        List<String> lines = readLines(path);
        if (lines.size() <= 1) {
            return;
        }

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("\\s+");
            if (parts.length < 4) {
                continue;
            }

            String local = parts[1];
            String state = parts[3];

            if (tcp && !"0A".equalsIgnoreCase(state)) {
                continue;
            }

            String[] hostPort = local.split(":");
            if (hostPort.length < 2) {
                continue;
            }

            int portNum;
            try {
                portNum = Integer.parseInt(hostPort[1], 16);
            } catch (Exception ignored) {
                continue;
            }

            if (portNum <= 0) {
                continue;
            }

            String key = protocol + ":" + portNum;
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            out.add(new PortEntry(protocol, portNum, "/proc"));
        }

        for (PortForwarder.ForwardInfo info : PortForwarder.getAllForwards().values()) {
            String proto = info.getProtocolName();
            String key = proto + ":" + info.listenPort;
            if (!seen.contains(key)) {
                seen.add(key);
                out.add(new PortEntry(proto, info.listenPort, "forward"));
            }
        }

        String helloKey = "HTTP:" + port;
        if (isRunning() && !seen.contains(helloKey)) {
            seen.add(helloKey);
            out.add(new PortEntry("HTTP", port, "hello"));
        }
    }

    private void closeQuietly(ServerSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
                // ignored
            }
        }
    }

    private void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
                // ignored
            }
        }
    }

    private static class PortEntry {
        final String protocol;
        final int port;
        final String source;

        PortEntry(String protocol, int port, String source) {
            this.protocol = protocol;
            this.port = port;
            this.source = source;
        }
    }
}
