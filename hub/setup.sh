#!/bin/bash

echo "🚀 Setting up Ekatra AI Offline Learning System"
echo "================================================"

# Check if running on macOS
if [[ "$OSTYPE" != "darwin"* ]]; then
    echo "❌ This setup script is designed for macOS"
    echo "For other systems, please check the documentation"
    exit 1
fi

# Check Python version
if ! command -v python3 &> /dev/null; then
    echo "❌ Python 3 is required but not installed"
    echo "Please install Python 3.8+ from https://python.org"
    exit 1
fi

PYTHON_VERSION=$(python3 -c 'import sys; print(".".join(map(str, sys.version_info[:2])))')
echo "✅ Python version: $PYTHON_VERSION"

# Create virtual environment
echo "📦 Creating virtual environment..."
python3 -m venv venv
source venv/bin/activate

# Upgrade pip
echo "⬆️ Upgrading pip..."
pip install --upgrade pip

# Install requirements
echo "📥 Installing Python packages..."
pip install -r requirements.txt

# Create .env file from example if it doesn't exist
if [ ! -f .env ]; then
    echo "⚙️ Creating .env file..."
    cp .env.example .env
    echo "📝 Please edit .env file and add your tokens if needed"
fi

# Create static directories
echo "📁 Creating static directories..."
mkdir -p static/css static/js static/icons

# Create PWA manifest
cat > static/manifest.json << 'EOF'
{
  "name": "Ekatra AI Learning",
  "short_name": "EkatraAI",
  "description": "Offline AI Learning Assistant",
  "start_url": "/",
  "display": "standalone",
  "background_color": "#667eea",
  "theme_color": "#667eea",
  "icons": [
    {
      "src": "/static/icon-192x192.png",
      "sizes": "192x192",
      "type": "image/png"
    }
  ]
}
EOF

# Create service worker for PWA
cat > static/sw.js << 'EOF'
const CACHE_NAME = 'ekatra-ai-v1';
const urlsToCache = [
  '/',
  '/admin'
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
      .then(response => {
        if (response) {
          return response;
        }
        return fetch(event.request);
      }
    )
  );
});
EOF

# Set permissions
chmod +x server.py
chmod +x run.sh

echo ""
echo "✅ Setup completed successfully!"
echo ""
echo "🎯 Next steps:"
echo "1. Edit .env file if you want to use HuggingFace models or API mode"
echo "2. Run the system: ./start.sh"
echo ""
echo "📱 For macOS WiFi sharing:"
echo "1. Go to System Preferences → Sharing"
echo "2. Enable 'Internet Sharing'"
echo "3. Share from: Ethernet"
echo "4. To computers using: Wi-Fi"
echo "5. Configure Wi-Fi Options with your preferred name and password"
echo ""
echo "🌐 Once running, access:"
echo "   - Client: http://[YOUR_IP]:5000"
echo "   - Admin:  http://[YOUR_IP]:5000/admin"
