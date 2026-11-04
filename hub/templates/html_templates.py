"""
HTML Templates for Ekatra AI Offline Learning System
Enhanced for v2.1 with template compatibility
"""

def get_base_template():
    """Get base template (not used in Flask template system)"""
    return ""

def get_chat_template():
    """Get chat template (not used in Flask template system)"""
    return ""

def get_clients_template():
    """Get clients template (not used in Flask template system)"""
    return ""

def get_about_template():
    """Get about template (not used in Flask template system)"""
    return ""

def get_manifest():
    """Get PWA manifest"""
    return {
        "name": "Ekatra AI",
        "short_name": "EkatraAI",
        "description": "Secure offline AI assistant",
        "start_url": "/",
        "display": "standalone",
        "background_color": "#22223b",
        "theme_color": "#22223b",
        "icons": [
            {
                "src": "/static/icons/icon-192.png",
                "sizes": "192x192",
                "type": "image/png"
            },
            {
                "src": "/static/icons/icon-512.png", 
                "sizes": "512x512",
                "type": "image/png"
            }
        ]
    }

def get_service_worker():
    """Get service worker JavaScript"""
    return """
const CACHE_NAME = 'ekatra-ai-v2.1';
const urlsToCache = [
    '/',
    '/static/css/main.css',
    '/static/js/main.js'
];

self.addEventListener('install', event => {
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then(cache => cache.addAll(urlsToCache))
    );
});

self.addEventListener('fetch', event => {
    event.respondWith(
        caches.match(event.request)
            .then(response => response || fetch(event.request))
    );
});
"""

class AdminDashboard:
    @staticmethod
    def get_template():
        return '''
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Ekatra AI Server Dashboard</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { 
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%);
            color: white; min-height: 100vh; padding: 20px;
        }
        .container { max-width: 1200px; margin: 0 auto; }
        .header { text-align: center; margin-bottom: 30px; }
        .header h1 { font-size: 2.5rem; margin-bottom: 10px; }
        .status-bar { 
            display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 20px; margin-bottom: 30px;
        }
        .status-card { 
            background: rgba(255,255,255,0.1); padding: 20px; border-radius: 10px;
            backdrop-filter: blur(10px); text-align: center;
        }
        .status-card h3 { margin-bottom: 10px; color: #64b5f6; }
        .status-online { color: #4caf50; }
        .status-offline { color: #f44336; }
        .metrics-grid { 
            display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
            gap: 20px; margin-bottom: 30px;
        }
        .metric-card { 
            background: rgba(255,255,255,0.1); padding: 25px; border-radius: 10px;
            backdrop-filter: blur(10px);
        }
        .progress-bar { 
            width: 100%; height: 20px; background: rgba(0,0,0,0.2);
            border-radius: 10px; overflow: hidden; margin: 10px 0;
        }
        .progress-fill { 
            height: 100%; background: linear-gradient(90deg, #4caf50, #8bc34a);
            transition: width 0.3s ease;
        }
        .btn { 
            padding: 12px 24px; border: none; border-radius: 6px; cursor: pointer;
            font-size: 14px; font-weight: 500; margin: 5px; transition: all 0.3s;
        }
        .btn-primary { background: #2196f3; color: white; }
        .btn-success { background: #4caf50; color: white; }
        .btn-danger { background: #f44336; color: white; }
        .btn-warning { background: #ff9800; color: white; }
        .btn:hover { transform: translateY(-2px); box-shadow: 0 4px 8px rgba(0,0,0,0.3); }
        .device-table { width: 100%; border-collapse: collapse; margin-top: 15px; }
        .device-table th, .device-table td { 
            padding: 12px; text-align: left;
            border-bottom: 1px solid rgba(255,255,255,0.1);
        }
        .device-table th { background: rgba(0,0,0,0.2); font-weight: 600; }
        .refresh-btn { 
            position: fixed; bottom: 30px; right: 30px; width: 60px; height: 60px;
            border-radius: 50%; background: #2196f3; border: none; color: white;
            font-size: 20px; cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,0.3);
        }
        @media (max-width: 768px) {
            body { padding: 10px; }
            .header h1 { font-size: 2rem; }
            .status-bar, .metrics-grid { grid-template-columns: 1fr; }
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>🛡️ Ekatra AI Server Dashboard</h1>
            <p>Offline AI Learning Hub</p>
        </div>

        <div class="status-bar">
            <div class="status-card">
                <h3>Server Status</h3>
                <div class="status-online">🟢 Online</div>
            </div>
            <div class="status-card">
                <h3>WiFi Hotspot</h3>
                <div class="wifi-status {{ 'status-online' if wifi_info.active else 'status-offline' }}">
                    {{ '🟢 Active' if wifi_info.active else '🔴 Inactive' }}
                </div>
            </div>
            <div class="status-card">
                <h3>Connected Users</h3>
                <div class="connected-users-count status-online">{{ wifi_info.connected_devices }}</div>
            </div>
            <div class="status-card">
                <h3>AI Model</h3>
                <div class="ai-model-status {{ 'status-online' if model_stats.loaded else 'status-offline' }}">
                    {{ '✅ Ready' if model_stats.loaded else '❌ Not Loaded' }}
                </div>
            </div>
        </div>

        <div class="metrics-grid">
            <div class="metric-card">
                <h3>📊 System Metrics</h3>
                <div style="margin: 15px 0;">
                    <label>CPU Usage: <span class="cpu-usage">{{ "%.1f"|format(system_info.cpu_usage) }}%</span></label>
                    <div class="progress-bar">
                        <div class="progress-fill cpu-progress" style="width: {{ system_info.cpu_usage }}%"></div>
                    </div>
                </div>
                <div style="margin: 15px 0;">
                    <label>Memory: <span class="memory-usage">{{ "%.1f"|format(system_info.memory.percentage) }}%</span></label>
                    <div class="progress-bar">
                        <div class="progress-fill memory-progress" style="width: {{ system_info.memory.percentage }}%"></div>
                    </div>
                </div>
                <div style="margin: 15px 0;">
                    <label>Storage: <span class="disk-usage">{{ "%.1f"|format(system_info.disk.percentage) }}%</span></label>
                    <div class="progress-bar">
                        <div class="progress-fill disk-progress" style="width: {{ system_info.disk.percentage }}%"></div>
                    </div>
                </div>
            </div>

            <div class="metric-card">
                <h3>🌐 Network Configuration</h3>
                <p><strong>SSID:</strong> {{ wifi_info.ssid }}</p>
                <p><strong>Password:</strong> ••••••••••</p>
                <p><strong>IP Address:</strong> <span class="network-ip">{{ wifi_info.ip_address }}</span></p>
                <p><strong>System:</strong> <span class="network-system">{{ wifi_info.system }}</span></p>
                <div style="margin-top: 20px;">
                    {% if wifi_info.active %}
                        <button class="btn btn-danger" onclick="toggleHotspot(false)">Stop Hotspot</button>
                    {% else %}
                        <button class="btn btn-success" onclick="toggleHotspot(true)">Start Hotspot</button>
                    {% endif %}
                    <button class="btn btn-primary" onclick="showNetworkConfig()">Configure</button>
                </div>
            </div>

            <div class="metric-card">
                <h3>🤖 AI Model Management</h3>
                <p><strong>Current Model:</strong> <span id="currentModelName">{{ model_stats.model_name.split('/')[-1] }}</span></p>
                <p><strong>Status:</strong> <span class="model-status">{{ 'Loaded and Ready' if model_stats.loaded else 'Not Loaded' }}</span></p>
                <p><strong>Mode:</strong> <span class="model-mode">{{ model_stats.mode.title() }}</span></p>
                <p><strong>Responses Generated:</strong> <span class="response-count">{{ model_stats.response_count }}</span></p>
                <p><strong>Avg Response Time:</strong> <span class="avg-response-time">{{ model_stats.average_response_time }}s</span></p>
                <p><strong>Device:</strong> <span class="model-device">{{ model_stats.device_name or model_stats.device.upper() }}</span></p>
                
                <div style="margin: 20px 0;">
                    <label for="modelSelect"><strong>Switch Model:</strong></label>
                    <select id="modelSelect" style="width: 100%; padding: 8px; margin: 10px 0; border-radius: 5px; border: 1px solid #ccc;">
                        <option value="">Loading models...</option>
                    </select>
                </div>
                
                <div style="margin-top: 20px;">
                    <button class="btn btn-primary" onclick="switchModel()">Switch Model</button>
                    <button class="btn btn-warning" onclick="restartModel()">Restart Current</button>
                    <button class="btn btn-primary" onclick="showModelPerformance()">Performance</button>
                </div>
            </div>
        </div>

        <div class="metric-card">
            <h3>👥 Connected Devices</h3>
            {% if connected_devices %}
                <table class="device-table">
                    <thead>
                        <tr>
                            <th>Device Name</th>
                            <th>IP Address</th>
                            <th>MAC Address</th>
                            <th>Status</th>
                        </tr>
                    </thead>
                    <tbody>
                        {% for device in connected_devices %}
                        <tr>
                            <td>{{ device.hostname }}</td>
                            <td>{{ device.ip }}</td>
                            <td>{{ device.mac }}</td>
                            <td>{{ device.connected_time }}</td>
                        </tr>
                        {% endfor %}
                    </tbody>
                </table>
            {% else %}
                <p style="text-align: center; margin: 20px; opacity: 0.7;">No devices currently connected</p>
            {% endif %}
        </div>
    </div>

    <button class="refresh-btn" onclick="location.reload()" title="Refresh Dashboard">🔄</button>

    <script>
        let availableModels = {};
        let currentModel = '';

        // Load available models on page load
        async function loadAvailableModels() {
            try {
                const response = await fetch('/admin/models/available');
                const data = await response.json();
                
                availableModels = data.models;
                currentModel = data.current_model;
                
                const select = document.getElementById('modelSelect');
                select.innerHTML = '';
                
                // Group models by category
                const categories = {};
                Object.entries(availableModels).forEach(([modelId, modelInfo]) => {
                    if (!categories[modelInfo.category]) {
                        categories[modelInfo.category] = [];
                    }
                    categories[modelInfo.category].push({id: modelId, info: modelInfo});
                });
                
                // Create optgroups
                Object.entries(categories).forEach(([category, models]) => {
                    const optgroup = document.createElement('optgroup');
                    optgroup.label = category;
                    
                    models.forEach(({id, info}) => {
                        const option = document.createElement('option');
                        option.value = id;
                        option.textContent = `${info.name} (${info.size})`;
                        if (info.requires_token) {
                            option.textContent += ' 🔐';
                        }
                        if (id === currentModel) {
                            option.selected = true;
                        }
                        optgroup.appendChild(option);
                    });
                    
                    select.appendChild(optgroup);
                });
                
                // Add model description on selection change
                select.addEventListener('change', function() {
                    const selectedModel = availableModels[this.value];
                    if (selectedModel) {
                        const description = document.getElementById('modelDescription');
                        if (!description) {
                            const descDiv = document.createElement('div');
                            descDiv.id = 'modelDescription';
                            descDiv.style.cssText = 'margin: 10px 0; padding: 10px; background: rgba(0,0,0,0.1); border-radius: 5px; font-size: 0.9em;';
                            select.parentNode.appendChild(descDiv);
                        }
                        document.getElementById('modelDescription').textContent = selectedModel.description;
                    }
                });
                
            } catch (error) {
                console.error('Error loading models:', error);
                document.getElementById('modelSelect').innerHTML = '<option value="">Error loading models</option>';
            }
        }

        async function switchModel() {
            const select = document.getElementById('modelSelect');
            const selectedModel = select.value;
            
            if (!selectedModel) {
                alert('Please select a model first.');
                return;
            }
            
            if (selectedModel === currentModel) {
                alert('This model is already active.');
                return;
            }
            
            const modelInfo = availableModels[selectedModel];
            const confirmMsg = `Switch to ${modelInfo.name}?\n\nSize: ${modelInfo.size}\nDescription: ${modelInfo.description}\n\n${modelInfo.requires_token ? 'Note: This model requires HuggingFace authentication.' : ''}`;
            
            if (!confirm(confirmMsg)) {
                return;
            }
            
            try {
                document.getElementById('currentModelName').textContent = 'Switching...';
                
                const response = await fetch('/admin/model/switch', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ model_name: selectedModel })
                });
                
                const result = await response.json();
                
                if (result.success) {
                    currentModel = selectedModel;
                    document.getElementById('currentModelName').textContent = modelInfo.name;
                    alert(`✅ ${result.message}`);
                    // Reload page to update all stats
                    setTimeout(() => location.reload(), 1000);
                } else {
                    alert(`❌ ${result.message}`);
                    document.getElementById('currentModelName').textContent = 'Switch Failed';
                }
            } catch (error) {
                alert(`❌ Error switching model: ${error.message}`);
                document.getElementById('currentModelName').textContent = 'Error';
            }
        }

        async function toggleHotspot(start) {
            const action = start ? 'start' : 'stop';
            try {
                const response = await fetch(`/admin/wifi/${action}`, { method: 'POST' });
                const result = await response.json();
                if (result.success) {
                    location.reload();
                } else {
                    alert(`Failed to ${action} hotspot: ${result.message}`);
                }
            } catch (error) {
                alert(`Error: ${error.message}`);
            }
        }

        function showNetworkConfig() {
            alert('Network configuration interface coming soon!');
        }

        function restartModel() {
            if (confirm('Are you sure you want to restart the current AI model? This may take a few minutes.')) {
                fetch('/admin/model/restart', { method: 'POST' })
                    .then(response => response.json())
                    .then(result => {
                        alert(result.message);
                        if (result.success) location.reload();
                    })
                    .catch(error => alert(`Error: ${error.message}`));
            }
        }

        function showModelPerformance() {
            const modelInfo = availableModels[currentModel];
            if (modelInfo) {
                alert(`📊 Model Performance Info\n\nModel: ${modelInfo.name}\nSize: ${modelInfo.size}\nCategory: ${modelInfo.category}\nRequires Token: ${modelInfo.requires_token ? 'Yes' : 'No'}\n\nFor detailed performance metrics, check the system stats above.`);
            } else {
                alert('Model performance analytics coming soon!');
            }
        }

        // Update metrics in real-time
        async function updateMetrics() {
            try {
                const response = await fetch('/api/system/stats');
                const data = await response.json();
                
                // Update CPU usage
                document.querySelector('.cpu-usage').textContent = data.cpu_usage.toFixed(1) + '%';
                document.querySelector('.cpu-progress').style.width = data.cpu_usage + '%';
                
                // Update Memory usage
                document.querySelector('.memory-usage').textContent = data.memory_usage.toFixed(1) + '%';
                document.querySelector('.memory-progress').style.width = data.memory_usage + '%';
                
                // Update Disk usage
                document.querySelector('.disk-usage').textContent = data.disk_usage.toFixed(1) + '%';
                document.querySelector('.disk-progress').style.width = data.disk_usage + '%';
                
                // Update WiFi status
                const wifiStatus = document.querySelector('.wifi-status');
                if (data.wifi_active) {
                    wifiStatus.textContent = '🟢 Active';
                    wifiStatus.className = 'wifi-status status-online';
                } else {
                    wifiStatus.textContent = '🔴 Inactive';
                    wifiStatus.className = 'wifi-status status-offline';
                }
                
                // Update connected users count
                document.querySelector('.connected-users-count').textContent = data.connected_devices_count;
                
                // Update model info
                const modelName = data.model_name.includes('/') ? data.model_name.split('/').pop() : data.model_name;
                document.getElementById('currentModelName').textContent = modelName;
                document.querySelector('.model-status').textContent = data.model_loaded ? 'Loaded and Ready' : 'Not Loaded';
                document.querySelector('.model-mode').textContent = data.model_mode.charAt(0).toUpperCase() + data.model_mode.slice(1);
                document.querySelector('.response-count').textContent = data.response_count;
                document.querySelector('.avg-response-time').textContent = data.avg_response_time + 's';
                document.querySelector('.model-device').textContent = data.device_name || data.device.toUpperCase();
                
                // Update AI Model status indicator
                const aiModelStatus = document.querySelector('.ai-model-status');
                if (data.model_loaded) {
                    aiModelStatus.textContent = '✅ Ready';
                    aiModelStatus.className = 'ai-model-status status-online';
                } else {
                    aiModelStatus.textContent = '❌ Not Loaded';
                    aiModelStatus.className = 'ai-model-status status-offline';
                }
                
            } catch (error) {
                console.error('Error updating metrics:', error);
            }
        }
        
        // Update WiFi status separately
        async function updateWifiStatus() {
            try {
                const response = await fetch('/api/wifi/status');
                const data = await response.json();
                
                const wifiStatus = document.querySelector('.wifi-status');
                if (data.active) {
                    wifiStatus.textContent = '🟢 Active';
                    wifiStatus.className = 'wifi-status status-online';
                } else {
                    wifiStatus.textContent = '🔴 Inactive';
                    wifiStatus.className = 'wifi-status status-offline';
                }
                
                // Update network info if needed
                document.querySelector('.network-ip').textContent = data.ip_address;
                document.querySelector('.network-system').textContent = data.system;
                
            } catch (error) {
                console.error('Error updating WiFi status:', error);
            }
        }

        // Load models when page loads
        document.addEventListener('DOMContentLoaded', function() {
            loadAvailableModels();
            updateMetrics();
            updateWifiStatus();
        });

        // Update metrics every 3 seconds
        setInterval(updateMetrics, 3000);
        // Update WiFi status every 5 seconds
        setInterval(updateWifiStatus, 5000);
        
        // Remove the full page reload interval
        // setInterval(() => { location.reload(); }, 30000);
    </script>
</body>
</html>
        '''

class ClientInterface:
    @staticmethod
    def get_template():
        return '''
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
    <title>Ekatra AI Learning</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { 
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: #333; height: 100vh; overflow: hidden;
        }
        .app-container { display: flex; flex-direction: column; height: 100vh; }
        .header { 
            background: rgba(0,0,0,0.1); color: white; padding: 1rem;
            text-align: center; backdrop-filter: blur(10px);
        }
        .header h1 { font-size: 1.5rem; margin-bottom: 0.25rem; }
        .header p { font-size: 0.9rem; opacity: 0.9; }
        .chat-container { 
            flex: 1; background: white; display: flex; 
            flex-direction: column; overflow: hidden;
        }
        .messages { flex: 1; overflow-y: auto; padding: 1rem; }
        .message { margin-bottom: 1rem; display: flex; }
        .message.user { justify-content: flex-end; }
        .message-bubble { 
            max-width: 80%; padding: 0.75rem 1rem; border-radius: 1.25rem;
            font-size: 0.95rem; line-height: 1.4;
        }
        .message.user .message-bubble { 
            background: #007bff; color: white; 
            border-bottom-right-radius: 0.5rem;
        }
        .message.assistant .message-bubble { 
            background: #f1f3f4; color: #333;
            border-bottom-left-radius: 0.5rem;
        }
        .quick-actions { 
            padding: 0.5rem 1rem; display: flex; gap: 0.5rem;
            overflow-x: auto; border-top: 1px solid #eee;
        }
        .quick-btn { 
            flex-shrink: 0; padding: 0.5rem 1rem; background: #e9ecef;
            border: none; border-radius: 1rem; font-size: 0.85rem;
            cursor: pointer; transition: background 0.2s;
        }
        .quick-btn:hover { background: #dee2e6; }
        .input-area { 
            padding: 1rem; background: white; border-top: 1px solid #eee;
            display: flex; gap: 0.5rem; align-items: flex-end;
        }
        .message-input { 
            flex: 1; min-height: 2.5rem; max-height: 6rem; padding: 0.75rem 1rem;
            border: 1px solid #ddd; border-radius: 1.25rem; font-size: 1rem;
            resize: none; outline: none; font-family: inherit;
        }
        .send-btn { 
            width: 2.5rem; height: 2.5rem; border: none; background: #007bff;
            color: white; border-radius: 50%; cursor: pointer;
            display: flex; align-items: center; justify-content: center;
            transition: background 0.2s;
        }
        .send-btn:hover { background: #0056b3; }
        .send-btn:disabled { background: #ccc; cursor: not-allowed; }
        .status-indicator { 
            position: fixed; top: 10px; right: 10px; padding: 0.5rem 0.75rem;
            background: rgba(0,0,0,0.8); color: white; border-radius: 1rem;
            font-size: 0.75rem; z-index: 1000;
        }
        .typing-indicator { 
            display: flex; align-items: center; gap: 0.5rem;
            padding: 0.75rem 1rem; background: #f1f3f4; border-radius: 1.25rem;
            margin-bottom: 1rem; max-width: 80%;
        }
        .typing-dots { display: flex; gap: 0.25rem; }
        .typing-dot { 
            width: 0.5rem; height: 0.5rem; background: #999;
            border-radius: 50%; animation: typing 1.4s infinite ease-in-out;
        }
        .typing-dot:nth-child(1) { animation-delay: -0.32s; }
        .typing-dot:nth-child(2) { animation-delay: -0.16s; }
        @keyframes typing {
            0%, 80%, 100% { transform: scale(0.8); opacity: 0.5; }
            40% { transform: scale(1); opacity: 1; }
        }
        @media (max-width: 768px) {
            .header h1 { font-size: 1.25rem; }
            .message-bubble { max-width: 85%; }
        }
    </style>
</head>
<body>
    <div class="status-indicator" id="statusIndicator">📡 Connected</div>
    
    <div class="app-container">
        <header class="header">
            <h1>🎓 Ekatra AI Learning</h1>
            <p>Your Offline Learning Assistant</p>
        </header>

        <div class="chat-container">
            <div class="messages" id="messages">
                <div class="message assistant">
                    <div class="message-bubble">
                        🤖 Hello! I'm Alfred, your expert AI learning assistant by Ekatra! I'm designed to make learning engaging and effective through clear explanations, step-by-step guidance, and real-world examples. I can help with Mathematics, Science, History, Literature, Technology, and many other subjects. What would you like to explore today?
                    </div>
                </div>
            </div>
            
            <div class="quick-actions">
                <button class="quick-btn" onclick="sendQuick('Help me with Math')">📊 Math</button>
                <button class="quick-btn" onclick="sendQuick('Explain a Science concept')">🔬 Science</button>
                <button class="quick-btn" onclick="sendQuick('Tell me about History')">📚 History</button>
                <button class="quick-btn" onclick="sendQuick('Give me a quiz')">🎯 Quiz</button>
                <button class="quick-btn" onclick="sendQuick('Explain simply')">💡 Explain</button>
            </div>
        </div>
        
        <div class="input-area">
            <textarea 
                id="messageInput" 
                class="message-input" 
                placeholder="Ask me anything..."
                rows="1"
                onkeydown="handleKeyDown(event)"
                oninput="autoResize(this)"
            ></textarea>
            <button id="sendButton" class="send-btn" onclick="sendMessage()">
                📤
            </button>
        </div>
    </div>

    <script>
        let isTyping = false;
        let connectionStatus = true;

        function autoResize(textarea) {
            textarea.style.height = '2.5rem';
            const newHeight = Math.min(textarea.scrollHeight, 96);
            textarea.style.height = newHeight + 'px';
        }

        function handleKeyDown(event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                sendMessage();
            }
        }

        function addMessage(content, isUser = false) {
            const container = document.getElementById('messages');
            const messageDiv = document.createElement('div');
            messageDiv.className = `message ${isUser ? 'user' : 'assistant'}`;
            
            const bubbleDiv = document.createElement('div');
            bubbleDiv.className = 'message-bubble';
            bubbleDiv.innerHTML = `${isUser ? content : '🤖 ' + content}`;
            
            messageDiv.appendChild(bubbleDiv);
            container.appendChild(messageDiv);
            container.scrollTop = container.scrollHeight;
        }

        function showTyping() {
            const container = document.getElementById('messages');
            const typingDiv = document.createElement('div');
            typingDiv.id = 'typingIndicator';
            typingDiv.className = 'typing-indicator';
            typingDiv.innerHTML = `
                <span>🤖</span>
                <div class="typing-dots">
                    <div class="typing-dot"></div>
                    <div class="typing-dot"></div>
                    <div class="typing-dot"></div>
                </div>
            `;
            container.appendChild(typingDiv);
            container.scrollTop = container.scrollHeight;
        }

        function hideTyping() {
            const typing = document.getElementById('typingIndicator');
            if (typing) typing.remove();
        }

        async function sendMessage() {
            const input = document.getElementById('messageInput');
            const sendButton = document.getElementById('sendButton');
            const message = input.value.trim();
            
            if (!message || isTyping) return;
            
            addMessage(message, true);
            input.value = '';
            input.style.height = '2.5rem';
            
            isTyping = true;
            sendButton.disabled = true;
            showTyping();
            
            try {
                const response = await fetch('/generate', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ prompt: message }),
                    timeout: 30000
                });
                
                hideTyping();
                
                if (response.ok) {
                    const data = await response.json();
                    addMessage(data.response || 'Sorry, I had trouble processing that.');
                    updateConnectionStatus(true);
                } else {
                    addMessage('⚠️ Server error. Please try again.');
                    updateConnectionStatus(false);
                }
            } catch (error) {
                hideTyping();
                addMessage('❌ Connection error. Check your connection.');
                updateConnectionStatus(false);
            }
            
            isTyping = false;
            sendButton.disabled = false;
        }

        function sendQuick(message) {
            document.getElementById('messageInput').value = message;
            sendMessage();
        }

        function updateConnectionStatus(connected) {
            const statusIndicator = document.getElementById('statusIndicator');
            connectionStatus = connected;
            
            if (connected) {
                statusIndicator.textContent = '📡 Connected';
                statusIndicator.style.background = 'rgba(76, 175, 80, 0.9)';
            } else {
                statusIndicator.textContent = '🔴 Disconnected';
                statusIndicator.style.background = 'rgba(244, 67, 54, 0.9)';
            }
        }

        // Check connection periodically
        setInterval(async () => {
            try {
                const response = await fetch('/health', { timeout: 5000 });
                updateConnectionStatus(response.ok);
            } catch (error) {
                updateConnectionStatus(false);
            }
        }, 10000);
    </script>
</body>
</html>
        '''
