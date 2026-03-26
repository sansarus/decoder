package com.openipc.decoder;

import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class WebServer extends Thread {
    private static final String TAG = "WebServer";
    private ServerSocket serverSocket;
    private boolean isRunning = true;
    private SharedPreferences prefs;
    private int port = 8080;
    private static final int CAM_COUNT = 8;
    private SettingsChangeListener listener;

    public interface SettingsChangeListener {
        void onSettingsChanged();
    }

    public WebServer(SharedPreferences prefs) {
        this.prefs = prefs;
    }
    
    public void setListener(SettingsChangeListener listener) {
        this.listener = listener;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            Log.i(TAG, "Web server started on port " + port);
            
            while (isRunning) {
                try {
                    Socket client = serverSocket.accept();
                    handleClient(client);
                    client.close();
                } catch (IOException e) {
                    if (isRunning) {
                        Log.e(TAG, "Error handling client: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Web server error: " + e.getMessage());
        }
    }

    private void handleClient(Socket client) throws IOException {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(client.getInputStream()));
        OutputStream output = client.getOutputStream();
        
        String requestLine = reader.readLine();
        if (requestLine == null) return;
        
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;
        
        String method = parts[0];
        String path = parts[1];
        
        // Читаем заголовки
        String line;
        int contentLength = 0;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        
        if (method.equals("POST") && path.equals("/update")) {
            // Читаем POST данные
            char[] buffer = new char[contentLength];
            reader.read(buffer, 0, contentLength);
            String data = new String(buffer);
            
            Log.d(TAG, "Received POST data: " + data);
            
            // Парсим параметры
            String[] params = data.split("&");
            SharedPreferences.Editor editor = prefs.edit();
            boolean settingsChanged = false;
            
            for (String param : params) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2) {
                    String key = kv[0];
                    String value = decodeUrl(kv[1]);
                    
                    Log.d(TAG, "Processing: " + key + " = " + value);
                    
                    if (key.startsWith("cam_")) {
                        int camId = Integer.parseInt(key.substring(4));
                        editor.putString("host_" + camId, value);
                        settingsChanged = true;
                        Log.i(TAG, "Updated camera " + camId + " URL: " + value);
                    } else if (key.startsWith("type_")) {
                        int camId = Integer.parseInt(key.substring(5));
                        editor.putBoolean("type_" + camId, Boolean.parseBoolean(value));
                        settingsChanged = true;
                    } else if (key.startsWith("carousel_") && !key.equals("carousel_enabled") && !key.equals("carousel_interval")) {
                        int camId = Integer.parseInt(key.substring(9));
                        editor.putBoolean("carousel_" + camId, Boolean.parseBoolean(value));
                        settingsChanged = true;
                    } else if (key.startsWith("quad_")) {
                        int camId = Integer.parseInt(key.substring(5));
                        editor.putBoolean("quad_" + camId, Boolean.parseBoolean(value));
                        settingsChanged = true;
                    } else if (key.equals("active")) {
                        editor.putInt("active", Integer.parseInt(value));
                        settingsChanged = true;
                    } else if (key.equals("carousel_enabled")) {
                        editor.putBoolean("carousel_enabled", Boolean.parseBoolean(value));
                        settingsChanged = true;
                    } else if (key.equals("carousel_interval")) {
                        editor.putInt("carousel_interval", Integer.parseInt(value));
                        settingsChanged = true;
                    }
                }
            }
            
            if (settingsChanged) {
                editor.apply();
                Log.i(TAG, "Settings saved successfully");
                
                // Уведомляем активность об изменении настроек
                if (listener != null) {
                    listener.onSettingsChanged();
                }
            }
            
            String response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n\r\n" +
                            "{\"status\":\"ok\",\"message\":\"Settings saved\"}";
            output.write(response.getBytes(StandardCharsets.UTF_8));
        } else if (method.equals("GET") && path.equals("/api/config")) {
            // API для получения текущей конфигурации в JSON
            String config = getConfigJson();
            String response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Access-Control-Allow-Origin: *\r\n\r\n" +
                            config;
            output.write(response.getBytes(StandardCharsets.UTF_8));
        } else {
            // Отправляем HTML страницу
            String html = getHtmlPage();
            String response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=UTF-8\r\n" +
                            "Content-Length: " + html.length() + "\r\n\r\n" +
                            html;
            output.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }
    
    private String getConfigJson() {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"active\":").append(prefs.getInt("active", 0)).append(",");
        json.append("\"carousel_enabled\":").append(prefs.getBoolean("carousel_enabled", false)).append(",");
        json.append("\"carousel_interval\":").append(prefs.getInt("carousel_interval", 10)).append(",");
        json.append("\"cameras\":[");
        
        for (int i = 0; i < CAM_COUNT; i++) {
            if (i > 0) json.append(",");
            String url = prefs.getString("host_" + i, "rtsp://root:12345@192.168.1.10:554/stream=0");
            boolean type = prefs.getBoolean("type_" + i, false);
            boolean carousel = prefs.getBoolean("carousel_" + i, false);
            boolean quad = prefs.getBoolean("quad_" + i, false);
            
            json.append("{");
            json.append("\"id\":").append(i).append(",");
            json.append("\"url\":\"").append(escapeJson(url)).append("\",");
            json.append("\"transport\":").append(type ? "\"UDP\"" : "\"TCP\"").append(",");
            json.append("\"carousel\":").append(carousel).append(",");
            json.append("\"quad\":").append(quad);
            json.append("}");
        }
        
        json.append("]}");
        return json.toString();
    }
    
    private String getHtmlPage() {
        return "<!DOCTYPE html>\n" +
               "<html>\n" +
               "<head>\n" +
               "    <meta charset='UTF-8'>\n" +
               "    <meta name='viewport' content='width=device-width, initial-scale=1.0'>\n" +
               "    <title>OpenIPC Decoder - Camera Configuration</title>\n" +
               "    <style>\n" +
               "        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
               "        body {\n" +
               "            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n" +
               "            background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%);\n" +
               "            min-height: 100vh;\n" +
               "            padding: 20px;\n" +
               "        }\n" +
               "        .container {\n" +
               "            max-width: 1200px;\n" +
               "            margin: 0 auto;\n" +
               "        }\n" +
               "        .header {\n" +
               "            background: white;\n" +
               "            border-radius: 15px;\n" +
               "            padding: 20px;\n" +
               "            margin-bottom: 20px;\n" +
               "            box-shadow: 0 4px 6px rgba(0,0,0,0.1);\n" +
               "        }\n" +
               "        h1 {\n" +
               "            color: #333;\n" +
               "            margin-bottom: 10px;\n" +
               "        }\n" +
               "        .subtitle {\n" +
               "            color: #666;\n" +
               "            font-size: 14px;\n" +
               "        }\n" +
               "        .cam-grid {\n" +
               "            display: grid;\n" +
               "            grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));\n" +
               "            gap: 20px;\n" +
               "            margin-bottom: 20px;\n" +
               "        }\n" +
               "        .cam-card {\n" +
               "            background: white;\n" +
               "            border-radius: 15px;\n" +
               "            padding: 20px;\n" +
               "            box-shadow: 0 4px 6px rgba(0,0,0,0.1);\n" +
               "            transition: transform 0.2s;\n" +
               "        }\n" +
               "        .cam-card:hover {\n" +
               "            transform: translateY(-2px);\n" +
               "            box-shadow: 0 6px 12px rgba(0,0,0,0.15);\n" +
               "        }\n" +
               "        .cam-title {\n" +
               "            font-size: 18px;\n" +
               "            font-weight: bold;\n" +
               "            color: #2a5298;\n" +
               "            margin-bottom: 15px;\n" +
               "            padding-bottom: 10px;\n" +
               "            border-bottom: 2px solid #e0e0e0;\n" +
               "        }\n" +
               "        .form-group {\n" +
               "            margin-bottom: 15px;\n" +
               "        }\n" +
               "        label {\n" +
               "            display: block;\n" +
               "            margin-bottom: 5px;\n" +
               "            color: #555;\n" +
               "            font-weight: 500;\n" +
               "            font-size: 13px;\n" +
               "        }\n" +
               "        input[type='text'] {\n" +
               "            width: 100%;\n" +
               "            padding: 8px 12px;\n" +
               "            border: 2px solid #e0e0e0;\n" +
               "            border-radius: 8px;\n" +
               "            font-size: 13px;\n" +
               "            font-family: monospace;\n" +
               "            transition: border-color 0.3s;\n" +
               "        }\n" +
               "        input[type='text']:focus {\n" +
               "            outline: none;\n" +
               "            border-color: #2a5298;\n" +
               "        }\n" +
               "        .checkbox-group {\n" +
               "            display: flex;\n" +
               "            gap: 15px;\n" +
               "            margin-top: 10px;\n" +
               "        }\n" +
               "        .checkbox-group label {\n" +
               "            display: inline-flex;\n" +
               "            align-items: center;\n" +
               "            gap: 5px;\n" +
               "            cursor: pointer;\n" +
               "        }\n" +
               "        .checkbox-group input {\n" +
               "            cursor: pointer;\n" +
               "        }\n" +
               "        select {\n" +
               "            width: 100%;\n" +
               "            padding: 8px 12px;\n" +
               "            border: 2px solid #e0e0e0;\n" +
               "            border-radius: 8px;\n" +
               "            font-size: 13px;\n" +
               "            background: white;\n" +
               "        }\n" +
               "        button {\n" +
               "            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n" +
               "            color: white;\n" +
               "            border: none;\n" +
               "            padding: 10px 20px;\n" +
               "            border-radius: 8px;\n" +
               "            font-size: 14px;\n" +
               "            font-weight: 600;\n" +
               "            cursor: pointer;\n" +
               "            width: 100%;\n" +
               "            margin-top: 10px;\n" +
               "            transition: transform 0.2s;\n" +
               "        }\n" +
               "        button:hover {\n" +
               "            transform: translateY(-1px);\n" +
               "        }\n" +
               "        .status {\n" +
               "            position: fixed;\n" +
               "            bottom: 20px;\n" +
               "            right: 20px;\n" +
               "            padding: 12px 20px;\n" +
               "            border-radius: 10px;\n" +
               "            background: #4caf50;\n" +
               "            color: white;\n" +
               "            display: none;\n" +
               "            animation: slideIn 0.3s ease;\n" +
               "            z-index: 1000;\n" +
               "        }\n" +
               "        .status.error {\n" +
               "            background: #f44336;\n" +
               "        }\n" +
               "        @keyframes slideIn {\n" +
               "            from { transform: translateX(100%); opacity: 0; }\n" +
               "            to { transform: translateX(0); opacity: 1; }\n" +
               "        }\n" +
               "        .controls {\n" +
               "            background: white;\n" +
               "            border-radius: 15px;\n" +
               "            padding: 20px;\n" +
               "            margin-top: 20px;\n" +
               "        }\n" +
               "        .controls h3 {\n" +
               "            margin-bottom: 15px;\n" +
               "            color: #333;\n" +
               "        }\n" +
               "        .save-all {\n" +
               "            background: linear-gradient(135deg, #43a047 0%, #1b5e20 100%);\n" +
               "            margin-top: 0;\n" +
               "        }\n" +
               "        @media (max-width: 768px) {\n" +
               "            .cam-grid { grid-template-columns: 1fr; }\n" +
               "        }\n" +
               "    </style>\n" +
               "</head>\n" +
               "<body>\n" +
               "    <div class='container'>\n" +
               "        <div class='header'>\n" +
               "            <h1>🎥 OpenIPC Decoder</h1>\n" +
               "            <div class='subtitle'>Camera Configuration & Management</div>\n" +
               "        </div>\n" +
               "        \n" +
               "        <div id='camGrid' class='cam-grid'>\n" +
               "            <div class='cam-card'>Loading...</div>\n" +
               "        </div>\n" +
               "        \n" +
               "        <div class='controls'>\n" +
               "            <h3>⚙️ Global Settings</h3>\n" +
               "            <div class='form-group'>\n" +
               "                <label>Active Camera</label>\n" +
               "                <select id='activeCam'>\n" +
               "                    <option value='0'>Camera 1</option>\n" +
               "                    <option value='1'>Camera 2</option>\n" +
               "                    <option value='2'>Camera 3</option>\n" +
               "                    <option value='3'>Camera 4</option>\n" +
               "                    <option value='4'>Camera 5</option>\n" +
               "                    <option value='5'>Camera 6</option>\n" +
               "                    <option value='6'>Camera 7</option>\n" +
               "                    <option value='7'>Camera 8</option>\n" +
               "                </select>\n" +
               "            </div>\n" +
               "            <div class='form-group'>\n" +
               "                <label>Carousel Mode</label>\n" +
               "                <input type='checkbox' id='carouselEnabled'> Enable auto-switching\n" +
               "            </div>\n" +
               "            <div class='form-group'>\n" +
               "                <label>Carousel Interval (seconds)</label>\n" +
               "                <input type='number' id='carouselInterval' min='3' max='120' value='10'>\n" +
               "            </div>\n" +
               "            <button id='saveGlobal' class='save-all'>💾 Save All Settings</button>\n" +
               "        </div>\n" +
               "    </div>\n" +
               "    <div id='status' class='status'></div>\n" +
               "    \n" +
               "    <script>\n" +
               "        let cameras = [];\n" +
               "        \n" +
               "        async function loadConfig() {\n" +
               "            try {\n" +
               "                const response = await fetch('/api/config');\n" +
               "                const config = await response.json();\n" +
               "                cameras = config.cameras;\n" +
               "                document.getElementById('activeCam').value = config.active;\n" +
               "                document.getElementById('carouselEnabled').checked = config.carousel_enabled;\n" +
               "                document.getElementById('carouselInterval').value = config.carousel_interval;\n" +
               "                renderCameras();\n" +
               "            } catch (error) {\n" +
               "                console.error('Failed to load config:', error);\n" +
               "                showStatus('Failed to load configuration', 'error');\n" +
               "            }\n" +
               "        }\n" +
               "        \n" +
               "        function renderCameras() {\n" +
               "            const grid = document.getElementById('camGrid');\n" +
               "            grid.innerHTML = cameras.map(cam => `\n" +
               "                <div class='cam-card'>\n" +
               "                    <div class='cam-title'>Camera ${cam.id + 1}</div>\n" +
               "                    <div class='form-group'>\n" +
               "                        <label>RTSP URL</label>\n" +
               "                        <input type='text' id='url_${cam.id}' value='${escapeHtml(cam.url)}' placeholder='rtsp://user:pass@ip:port/stream'>\n" +
               "                    </div>\n" +
               "                    <div class='checkbox-group'>\n" +
               "                        <label>\n" +
               "                            <input type='checkbox' id='udp_${cam.id}' ${cam.transport === 'UDP' ? 'checked' : ''}> UDP\n" +
               "                        </label>\n" +
               "                        <label>\n" +
               "                            <input type='checkbox' id='carousel_${cam.id}' ${cam.carousel ? 'checked' : ''}> Carousel\n" +
               "                        </label>\n" +
               "                        <label>\n" +
               "                            <input type='checkbox' id='quad_${cam.id}' ${cam.quad ? 'checked' : ''}> Quad\n" +
               "                        </label>\n" +
               "                    </div>\n" +
               "                    <button onclick='saveCamera(${cam.id})'>Save Camera ${cam.id + 1}</button>\n" +
               "                </div>\n" +
               "            `).join('');\n" +
               "        }\n" +
               "        \n" +
               "        async function saveCamera(camId) {\n" +
               "            const url = document.getElementById(`url_${camId}`).value;\n" +
               "            const isUdp = document.getElementById(`udp_${camId}`).checked;\n" +
               "            const carousel = document.getElementById(`carousel_${camId}`).checked;\n" +
               "            const quad = document.getElementById(`quad_${camId}`).checked;\n" +
               "            \n" +
               "            const formData = new URLSearchParams();\n" +
               "            formData.append(`cam_${camId}`, url);\n" +
               "            formData.append(`type_${camId}`, isUdp);\n" +
               "            formData.append(`carousel_${camId}`, carousel);\n" +
               "            formData.append(`quad_${camId}`, quad);\n" +
               "            \n" +
               "            try {\n" +
               "                const response = await fetch('/update', {\n" +
               "                    method: 'POST',\n" +
               "                    headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
               "                    body: formData.toString()\n" +
               "                });\n" +
               "                \n" +
               "                if (response.ok) {\n" +
               "                    showStatus(`Camera ${camId + 1} saved successfully!`, 'success');\n" +
               "                    setTimeout(() => loadConfig(), 1000);\n" +
               "                } else {\n" +
               "                    showStatus('Failed to save camera settings', 'error');\n" +
               "                }\n" +
               "            } catch (error) {\n" +
               "                console.error('Save error:', error);\n" +
               "                showStatus('Network error: ' + error.message, 'error');\n" +
               "            }\n" +
               "        }\n" +
               "        \n" +
               "        async function saveAll() {\n" +
               "            const active = document.getElementById('activeCam').value;\n" +
               "            const carouselEnabled = document.getElementById('carouselEnabled').checked;\n" +
               "            const carouselInterval = document.getElementById('carouselInterval').value;\n" +
               "            \n" +
               "            const formData = new URLSearchParams();\n" +
               "            formData.append('active', active);\n" +
               "            formData.append('carousel_enabled', carouselEnabled);\n" +
               "            formData.append('carousel_interval', carouselInterval);\n" +
               "            \n" +
               "            // Save all cameras\n" +
               "            for (let i = 0; i < cameras.length; i++) {\n" +
               "                const url = document.getElementById(`url_${i}`)?.value;\n" +
               "                if (url) {\n" +
               "                    formData.append(`cam_${i}`, url);\n" +
               "                    formData.append(`type_${i}`, document.getElementById(`udp_${i}`)?.checked || false);\n" +
               "                    formData.append(`carousel_${i}`, document.getElementById(`carousel_${i}`)?.checked || false);\n" +
               "                    formData.append(`quad_${i}`, document.getElementById(`quad_${i}`)?.checked || false);\n" +
               "                }\n" +
               "            }\n" +
               "            \n" +
               "            try {\n" +
               "                const response = await fetch('/update', {\n" +
               "                    method: 'POST',\n" +
               "                    headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
               "                    body: formData.toString()\n" +
               "                });\n" +
               "                \n" +
               "                if (response.ok) {\n" +
               "                    showStatus('All settings saved successfully!', 'success');\n" +
               "                    setTimeout(() => loadConfig(), 1000);\n" +
               "                } else {\n" +
               "                    showStatus('Failed to save settings', 'error');\n" +
               "                }\n" +
               "            } catch (error) {\n" +
               "                console.error('Save all error:', error);\n" +
               "                showStatus('Network error: ' + error.message, 'error');\n" +
               "            }\n" +
               "        }\n" +
               "        \n" +
               "        function showStatus(message, type) {\n" +
               "            const status = document.getElementById('status');\n" +
               "            status.textContent = message;\n" +
               "            status.className = 'status ' + type;\n" +
               "            status.style.display = 'block';\n" +
               "            setTimeout(() => {\n" +
               "                status.style.display = 'none';\n" +
               "            }, 3000);\n" +
               "        }\n" +
               "        \n" +
               "        function escapeHtml(text) {\n" +
               "            const div = document.createElement('div');\n" +
               "            div.textContent = text;\n" +
               "            return div.innerHTML;\n" +
               "        }\n" +
               "        \n" +
               "        document.getElementById('saveGlobal').addEventListener('click', saveAll);\n" +
               "        loadConfig();\n" +
               "        \n" +
               "        // Auto-refresh every 5 seconds\n" +
               "        setInterval(loadConfig, 5000);\n" +
               "    </script>\n" +
               "</body>\n" +
               "</html>";
    }
    
    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }
    
    private String decodeUrl(String encoded) {
        try {
            return java.net.URLDecoder.decode(encoded, "UTF-8");
        } catch (Exception e) {
            return encoded;
        }
    }
    
    public void stopServer() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server: " + e.getMessage());
        }
    }
}
