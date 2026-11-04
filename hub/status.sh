#!/bin/bash

# Ekatra AI System Status Monitor
# ===============================

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

PORT=5000

echo "📊 Ekatra AI System Status"
echo "=========================="
echo ""

# Check if port is listening
if lsof -Pi :$PORT -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo -e "${GREEN}✅ Server Status: Running${NC}"
    
    # Get process information
    PIDS=$(lsof -ti :$PORT)
    echo -e "${BLUE}📋 Process IDs: $PIDS${NC}"
    
    # Try to get a health check
    if command -v curl >/dev/null 2>&1; then
        echo ""
        echo "🏥 Health Check:"
        if curl -s "http://localhost:$PORT/health" >/dev/null 2>&1; then
            echo -e "${GREEN}✅ Server responding to health checks${NC}"
        else
            echo -e "${YELLOW}⚠️  Server not responding to health checks${NC}"
        fi
    fi
    
    # Show system stats if available
    echo ""
    echo "📈 Quick System Stats:"
    if command -v curl >/dev/null 2>&1; then
        STATS=$(curl -s "http://localhost:$PORT/api/system/stats" 2>/dev/null)
        if [ $? -eq 0 ]; then
            echo "$STATS" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(f'   CPU: {data.get(\"cpu_usage\", \"N/A\")}%')
    print(f'   Memory: {data.get(\"memory_usage\", \"N/A\")}%')
    print(f'   WiFi: {\"Active\" if data.get(\"wifi_active\") else \"Inactive\"}')
    print(f'   Active Clients: {data.get(\"active_clients_count\", 0)}')
    print(f'   Model Device: {data.get(\"device_name\", data.get(\"device\", \"Unknown\"))}')
except:
    print('   Unable to parse system stats')
" 2>/dev/null || echo "   Unable to fetch system stats"
        else
            echo "   Unable to fetch system stats"
        fi
    else
        echo "   curl not available for stats"
    fi
    
else
    echo -e "${RED}❌ Server Status: Not Running${NC}"
fi

echo ""

# Check for log files
if [ -f "logs/startup.log" ]; then
    echo "📄 Recent Log Entries:"
    echo "---------------------"
    tail -n 5 logs/startup.log | while read line; do
        echo "   $line"
    done
else
    echo -e "${YELLOW}⚠️  No log file found${NC}"
fi

echo ""

# Show available commands
echo "🛠️  Available Commands:"
echo "   ./start.sh          - Start the system"
echo "   ./start.sh --stop   - Stop the system"  
echo "   ./start.sh --status - Check status"
echo "   ./start.sh --logs   - View logs"
echo "   ./restart.sh        - Restart system"
echo "   ./status.sh         - This status check"
