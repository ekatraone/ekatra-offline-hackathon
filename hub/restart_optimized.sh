#!/bin/bash

# Restart Ekatra AI Server with Performance Optimizations
echo "🔄 Restarting Ekatra AI Server with optimizations..."

# Set environment variables for better performance
export TOKENIZERS_PARALLELISM=false
export PYTORCH_MPS_HIGH_WATERMARK_RATIO=0.0
export OMP_NUM_THREADS=4

# Kill existing server processes
echo "🛑 Stopping existing server processes..."
pkill -f "python.*server.py" 2>/dev/null
pkill -f "python.*main" 2>/dev/null
sleep 2

# Change to project directory
cd "$(dirname "$0")"

# Check if virtual environment exists and activate it
if [ -d "venv" ]; then
    echo "📦 Activating virtual environment..."
    source venv/bin/activate
fi

# Start the optimized server
echo "🚀 Starting optimized server..."
echo "📊 Performance improvements:"
echo "   - Reduced network discovery timeouts"
echo "   - Fixed tokenizer parallelism warnings"
echo "   - Optimized device discovery"
echo "   - Enhanced error handling"
echo ""

# Start server with optimizations
python server.py

echo "✅ Server startup complete!"
