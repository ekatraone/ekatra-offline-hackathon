#!/bin/bash

# Quick Restart Script for Ekatra AI
# ==================================

echo "🔄 Restarting Ekatra AI System..."

# Stop any existing server
echo "🛑 Stopping existing server..."
./start.sh --stop

# Wait a moment
sleep 2

# Start fresh
echo "🚀 Starting fresh server..."
./start.sh
