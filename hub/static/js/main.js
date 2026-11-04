/* Ekatra AI v2.1 Main JavaScript */

// Enhanced chat functionality
document.addEventListener('DOMContentLoaded', function() {
    console.log('Ekatra AI v2.1 loaded');
    
    // Register service worker for PWA
    if ('serviceWorker' in navigator) {
        navigator.serviceWorker.register('/sw.js')
            .then(reg => console.log('Service Worker registered'))
            .catch(err => console.log('Service Worker registration failed'));
    }
    
    // Initialize chat if on main page
    if (document.getElementById('chat-form')) {
        initializeChat();
    }
    
    // Auto-refresh for clients and devices pages
    if (window.location.pathname === '/clients') {
        setInterval(loadClients, 30000); // Refresh every 30s
    }
    
    if (window.location.pathname === '/devices') {
        setInterval(loadDevices, 60000); // Refresh every 60s
    }
});

function initializeChat() {
    const chatForm = document.getElementById('chat-form');
    const chatInput = document.getElementById('chat-input');
    const chatLog = document.getElementById('chat-log');
    const chatStatus = document.getElementById('chat-status');
    
    // Welcome message
    chatLog.innerHTML = `
        <div class='ai-msg'>
            <b>Ekatra:</b> Hello! I'm your educational AI assistant. I'm here to help you learn and explore ideas. What would you like to know today?
        </div>
    `;
    
    chatForm.onsubmit = async (e) => {
        e.preventDefault();
        const message = chatInput.value.trim();
        if (!message) return;
        
        // Add user message
        addMessage('user', message);
        chatInput.value = '';
        chatStatus.textContent = 'Thinking...';
        chatStatus.className = 'loading';
        
        try {
            const response = await fetch('/api/generate', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ 
                    message: message,
                    format: 'html',
                    timeout: 30
                })
            });
            
            const data = await response.json();
            
            if (data.success) {
                addMessage('ai', data.data.response, true); // true for HTML content
                
                // Show timing info
                if (data.metadata && data.metadata.generation_time_ms) {
                    chatStatus.textContent = `Response generated in ${data.metadata.generation_time_ms}ms`;
                }
            } else {
                addMessage('error', `Error: ${data.error}`);
            }
            
        } catch (err) {
            console.error('Chat error:', err);
            addMessage('error', `Network error: ${err.message}`);
        }
        
        chatStatus.textContent = '';
        chatStatus.className = '';
        chatLog.scrollTop = chatLog.scrollHeight;
    };
    
    // Auto-focus input
    chatInput.focus();
}

function addMessage(type, content, isHTML = false) {
    const chatLog = document.getElementById('chat-log');
    const msgDiv = document.createElement('div');
    
    let prefix = '';
    switch (type) {
        case 'user':
            msgDiv.className = 'user-msg';
            prefix = '<b>You:</b> ';
            break;
        case 'ai':
            msgDiv.className = 'ai-msg';
            prefix = '<b>Ekatra:</b> ';
            break;
        case 'error':
            msgDiv.className = 'error-msg';
            prefix = '<b>Error:</b> ';
            break;
    }
    
    if (isHTML) {
        msgDiv.innerHTML = prefix + content;
    } else {
        msgDiv.innerHTML = prefix + escapeHtml(content);
    }
    
    chatLog.appendChild(msgDiv);
    chatLog.scrollTop = chatLog.scrollHeight;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// Utility functions for other pages
async function loadClients() {
    try {
        const response = await fetch('/api/clients/active');
        const data = await response.json();
        
        if (data.success) {
            const clients = data.data.clients;
            let html = '';
            
            if (clients.length === 0) {
                html = '<p>No active clients</p>';
            } else {
                html = '<ul>';
                for (const client of clients) {
                    const connectedTime = new Date(client.connected_at).toLocaleString();
                    html += `
                        <li>
                            <strong>IP:</strong> ${client.ip}<br>
                            <strong>User Agent:</strong> ${client.user_agent}<br>
                            <strong>Connected:</strong> ${connectedTime}<br>
                            <strong>Requests:</strong> ${client.requests_count}
                        </li>
                    `;
                }
                html += '</ul>';
            }
            
            document.getElementById('clients-list').innerHTML = html;
        } else {
            document.getElementById('clients-list').innerHTML = `<p>Error: ${data.error}</p>`;
        }
    } catch (err) {
        document.getElementById('clients-list').innerHTML = `<p>Network error: ${err.message}</p>`;
    }
}

async function loadDevices() {
    const devicesList = document.getElementById('devices-list');
    devicesList.innerHTML = '<p class="loading">Scanning network...</p>';
    
    try {
        const params = new URLSearchParams({
            filter: currentFilter,
            deep: 'false',
            ekatra_only: ekatraOnly.toString()
        });
        
        const response = await fetch(`/api/devices?${params}`);
        const data = await response.json();
        
        if (data.success) {
            let html = '';
            const subnets = data.data.subnets;
            
            if (subnets.length === 0) {
                html = '<p>No network interfaces found</p>';
            } else {
                for (const subnet of subnets) {
                    html += `
                        <div class="subnet-section">
                            <h3>${subnet.interface} (${subnet.cidr})</h3>
                            <p><small>Scan time: ${subnet.scan_duration_ms}ms | Methods: ${subnet.scan_methods.join(', ')}</small></p>
                    `;
                    
                    if (subnet.devices.length === 0) {
                        html += '<p>No devices found on this subnet</p>';
                    } else {
                        html += '<ul>';
                        for (const device of subnet.devices) {
                            const deviceType = device.device_type || 'Unknown';
                            html += `
                                <li>
                                    <strong>${device.ip}</strong> 
                                    ${device.mac ? `(${device.mac})` : ''}
                                    <br>
                                    <small>
                                        Hostname: ${device.hostname || 'Unknown'} | 
                                        Type: ${deviceType} | 
                                        Methods: ${device.methods.join(', ')}
                                    </small>
                                </li>
                            `;
                        }
                        html += '</ul>';
                    }
                    
                    if (subnet.errors && subnet.errors.length > 0) {
                        html += `<p style="color: #ff9800;"><small>Warnings: ${subnet.errors.join(', ')}</small></p>`;
                    }
                    
                    html += '</div>';
                }
                
                // Add metadata
                html += `
                    <div style="margin-top: 2rem; padding: 1rem; background: rgba(0,0,0,0.2); border-radius: 5px;">
                        <h4>Scan Summary</h4>
                        <p>Total devices: ${data.data.total_devices}</p>
                        <p>Total scan time: ${data.data.scan_duration_ms}ms</p>
                        <p>Scan methods: ${data.data.scan_methods.join(', ')}</p>
                    </div>
                `;
            }
            
            devicesList.innerHTML = html;
        } else {
            devicesList.innerHTML = `<p>Error: ${data.error}</p>`;
        }
    } catch (err) {
        devicesList.innerHTML = `<p>Network error: ${err.message}</p>`;
    }
}

// Keyboard shortcuts
document.addEventListener('keydown', function(e) {
    // Ctrl/Cmd + Enter to send message
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        const chatForm = document.getElementById('chat-form');
        if (chatForm) {
            chatForm.dispatchEvent(new Event('submit'));
        }
    }
    
    // F5 or Ctrl/Cmd + R to refresh devices/clients
    if (e.key === 'F5' || ((e.ctrlKey || e.metaKey) && e.key === 'r')) {
        if (window.location.pathname === '/devices') {
            e.preventDefault();
            loadDevices();
        } else if (window.location.pathname === '/clients') {
            e.preventDefault();
            loadClients();
        }
    }
});
