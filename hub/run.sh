#!/bin/bash

echo "🚀 Starting Ekatra AI Offline Learning System"
echo "=============================================="

SERVER_ENTRYPOINT="server.py"

# Check if virtual environment exists
if [ ! -d "venv" ]; then
    echo "❌ Virtual environment not found. Please run setup.sh first."
    exit 1
fi

# Activate virtual environment
source venv/bin/activate

# Check if required files exist
if [ ! -f "$SERVER_ENTRYPOINT" ]; then
    echo "❌ $SERVER_ENTRYPOINT not found. Please check your installation."
    exit 1
fi

if [ ! -f ".env" ]; then
    echo "⚠️ .env file not found. Using default configuration."
fi

# Set environment variables
export TOKENIZERS_PARALLELISM=false

# Display system information
echo "📍 System: $(uname -s)"
echo "🐍 Python: $(python --version)"
echo "📂 Working directory: $(pwd)"
echo ""

# Check for sudo requirements (Linux only)
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    echo "⚠️ Linux detected. Some WiFi features may require sudo privileges."
    echo "💡 You may be prompted for your password to enable WiFi hotspot."
    echo ""
fi

# Start the application
echo "🎯 Starting Ekatra AI server..."
echo "💡 Press Ctrl+C to stop the server"
echo ""

python "$SERVER_ENTRYPOINT"
