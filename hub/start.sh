#!/bin/bash

# Ekatra AI System Startup Script with Port Management
# ===================================================

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Configuration
DEFAULT_PORT=5000
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_FILE="$SCRIPT_DIR/logs/startup.log"
SERVER_ENTRYPOINT="server.py"

# Create logs directory
mkdir -p "$SCRIPT_DIR/logs"

# Logging function
log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" | tee -a "$LOG_FILE"
}

# Print colored output
print_status() {
    echo -e "${GREEN}✅ $1${NC}"
    log "SUCCESS: $1"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
    log "WARNING: $1"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
    log "ERROR: $1"
}

print_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
    log "INFO: $1"
}

print_header() {
    echo -e "${PURPLE}$1${NC}"
    log "HEADER: $1"
}

# Function to check if port is in use
check_port() {
    local port=$1
    if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
        return 0  # Port is in use
    else
        return 1  # Port is free
    fi
}

# Function to kill processes on port
kill_port_processes() {
    local port=$1
    print_info "Checking port $port..."
    
    if check_port $port; then
        print_warning "Port $port is currently in use. Attempting to free it..."
        
        # Get PIDs using the port
        local pids=$(lsof -ti :$port)
        
        if [ ! -z "$pids" ]; then
            print_info "Found processes using port $port: $pids"
            
            # Try graceful shutdown first
            echo "$pids" | xargs kill -TERM 2>/dev/null || true
            sleep 2
            
            # Check if processes are still running
            if check_port $port; then
                print_warning "Graceful shutdown failed. Force killing processes..."
                echo "$pids" | xargs kill -KILL 2>/dev/null || true
                sleep 1
            fi
            
            # Final check
            if check_port $port; then
                print_error "Failed to free port $port. Please manually stop the processes."
                return 1
            else
                print_status "Port $port is now free"
            fi
        fi
    else
        print_status "Port $port is available"
    fi
    return 0
}

# Function to cleanup on exit
cleanup() {
    print_info "Cleaning up..."
    if [ ! -z "$SERVER_PID" ]; then
        print_info "Stopping server (PID: $SERVER_PID)..."
        kill -TERM $SERVER_PID 2>/dev/null || true
        wait $SERVER_PID 2>/dev/null || true
    fi
    print_status "Cleanup completed"
}

# Function to check system requirements
check_requirements() {
    print_header "🔍 Checking System Requirements"
    
    # Check Python
    if ! command -v python3 &> /dev/null; then
        print_error "Python 3 is required but not installed"
        exit 1
    fi
    
    local python_version=$(python3 -c 'import sys; print(".".join(map(str, sys.version_info[:2])))')
    print_status "Python version: $python_version"
    
    # Check virtual environment
    if [ ! -d "venv" ]; then
        print_error "Virtual environment not found. Please run setup.sh first."
        exit 1
    fi
    
    # Check preferred server entrypoint
    if [ ! -f "$SERVER_ENTRYPOINT" ]; then
        print_error "$SERVER_ENTRYPOINT not found. Please check your installation."
        exit 1
    fi
    
    if [ ! -f ".env" ]; then
        print_warning ".env file not found. Using default configuration."
    fi
    
    print_status "All requirements satisfied"
}

# Function to setup environment
setup_environment() {
    print_header "⚙️  Setting Up Environment"
    
    # Change to script directory
    cd "$SCRIPT_DIR"
    
    # Activate virtual environment
    source venv/bin/activate
    print_status "Virtual environment activated"
    
    # Set environment variables
    export TOKENIZERS_PARALLELISM=false
    export PYTHONUNBUFFERED=1
    print_status "Environment variables set"
    
    # Check dependencies
    if ! python -c "import flask" &> /dev/null; then
        print_error "Flask not installed. Please run setup.sh"
        exit 1
    fi
    
    print_status "Environment setup completed"
}

# Function to check network configuration
check_network() {
    print_header "🌐 Network Configuration"
    
    local system=$(uname -s)
    print_info "System: $system"
    
    # Get IP addresses
    if [[ "$system" == "Darwin" ]]; then
        local ip=$(ifconfig | grep 'inet ' | grep -v '127.0.0.1' | head -1 | awk '{print $2}')
    else
        local ip=$(hostname -I | awk '{print $1}')
    fi
    
    print_info "Primary IP: ${ip:-Unable to detect}"
    
    # Check for WiFi sharing on macOS
    if [[ "$system" == "Darwin" ]]; then
        if ifconfig | grep -q "192.168.2.1"; then
            print_status "Internet Sharing appears to be active"
        else
            print_warning "Internet Sharing not detected. Enable in System Preferences > Sharing if needed"
        fi
    fi
}

# Function to start server
start_server() {
    print_header "🚀 Starting Ekatra AI Server"
    
    # Kill any existing processes on the port
    kill_port_processes $DEFAULT_PORT || exit 1
    
    print_info "Starting server on port $DEFAULT_PORT..."
    print_info "Log file: $LOG_FILE"
    
    # Start server in background
    python "$SERVER_ENTRYPOINT" >> "$LOG_FILE" 2>&1 &
    SERVER_PID=$!
    
    # Wait for server to start with progress indication
    print_info "Waiting for server to initialize (model loading may take up to 60 seconds)..."
    local max_wait=60
    local wait_count=0
    
    while [ $wait_count -lt $max_wait ]; do
        # Check if process is still running
        if ! kill -0 $SERVER_PID 2>/dev/null; then
            print_error "Server process died. Check logs for details."
            return 1
        fi
        
        # Check if port is listening
        if check_port $DEFAULT_PORT; then
            print_status "Server started successfully (PID: $SERVER_PID)"
            return 0
        fi
        
        # Show progress every 10 seconds
        if [ $((wait_count % 10)) -eq 0 ] && [ $wait_count -gt 0 ]; then
            print_info "Still waiting... (${wait_count}s elapsed, model loading in progress)"
        fi
        
        sleep 1
        wait_count=$((wait_count + 1))
    done
    
    # Timeout reached
    print_error "Server startup timed out after ${max_wait} seconds"
    print_info "Server process is still running (PID: $SERVER_PID), but port not responding"
    print_info "Check logs: tail -f $LOG_FILE"
    return 1
}

# Function to display access information
show_access_info() {
    print_header "📱 Access Information"
    
    local system=$(uname -s)
    
    if [[ "$system" == "Darwin" ]]; then
        # macOS specific IPs
        echo -e "${CYAN}WiFi Hotspot Access:${NC}"
        echo "   📱 Client: http://192.168.2.1:$DEFAULT_PORT"
        echo "   🛡️  Admin:  http://192.168.2.1:$DEFAULT_PORT/admin"
        echo ""
        
        echo -e "${CYAN}Local Network Access:${NC}"
        local local_ip=$(ifconfig | grep 'inet ' | grep -v '127.0.0.1' | grep -v '192.168.2.1' | head -1 | awk '{print $2}')
        if [ ! -z "$local_ip" ]; then
            echo "   📱 Client: http://$local_ip:$DEFAULT_PORT"
            echo "   🛡️  Admin:  http://$local_ip:$DEFAULT_PORT/admin"
        fi
    else
        local ip=$(hostname -I | awk '{print $1}')
        echo -e "${CYAN}Network Access:${NC}"
        echo "   📱 Client: http://$ip:$DEFAULT_PORT"
        echo "   🛡️  Admin:  http://$ip:$DEFAULT_PORT/admin"
    fi
    
    echo ""
    echo -e "${CYAN}Local Access:${NC}"
    echo "   📱 Client: http://localhost:$DEFAULT_PORT"
    echo "   🛡️  Admin:  http://localhost:$DEFAULT_PORT/admin"
    echo ""
    
    echo -e "${YELLOW}📋 Instructions:${NC}"
    echo "1. Connect devices to 'EkatraHub' WiFi network"
    echo "2. Open browser and visit one of the URLs above"
    echo "3. Use Ctrl+C to stop the server"
    echo ""
}

# Function to monitor server
monitor_server() {
    print_header "📊 Server Monitoring"
    print_info "Server is running. Press Ctrl+C to stop."
    
    # Monitor server health
    while kill -0 $SERVER_PID 2>/dev/null; do
        sleep 10
        if ! check_port $DEFAULT_PORT; then
            print_error "Server lost port $DEFAULT_PORT. Restarting..."
            break
        fi
    done
}

# Main execution
main() {
    # Set up signal handlers
    trap cleanup EXIT INT TERM
    
    echo ""
    print_header "🎯 Ekatra AI Offline Learning System"
    print_header "====================================="
    echo ""
    
    # Run setup checks
    check_requirements
    setup_environment
    check_network
    
    # Start the server
    if start_server; then
        show_access_info
        monitor_server
    else
        print_error "Failed to start server."
        print_info "Possible reasons:"
        print_info "1. Model loading timed out (may need more time on slower systems)"
        print_info "2. Port conflict or permission issues"
        print_info "3. Missing dependencies or corrupted model files"
        print_info ""
        print_info "Check the logs for detailed error information:"
        print_info "tail -f $LOG_FILE"
        print_info ""
        print_info "If model loading is taking longer, you can monitor progress with:"
        print_info "tail -f $LOG_FILE | grep -E '(Model|Loading|Error)'"
        cleanup
        exit 1
    fi
}

# Handle script arguments
case "${1:-}" in
    --help|-h)
        echo "Ekatra AI Startup Script"
        echo ""
        echo "Usage: $0 [OPTION]"
        echo ""
        echo "Options:"
        echo "  --help, -h     Show this help message"
        echo "  --status       Check if server is running"
        echo "  --stop         Stop the server"
        echo "  --logs         Show recent logs"
        echo ""
        exit 0
        ;;
    --status)
        if check_port $DEFAULT_PORT; then
            print_status "Server is running on port $DEFAULT_PORT"
            pids=$(lsof -ti :$DEFAULT_PORT)
            print_info "Process IDs: $pids"
        else
            print_info "Server is not running"
        fi
        exit 0
        ;;
    --stop)
        print_info "Stopping Ekatra AI server..."
        kill_port_processes $DEFAULT_PORT
        exit 0
        ;;
    --logs)
        if [ -f "$LOG_FILE" ]; then
            echo "Recent logs from $LOG_FILE:"
            echo "----------------------------------------"
            tail -n 50 "$LOG_FILE"
        else
            print_info "No log file found at $LOG_FILE"
        fi
        exit 0
        ;;
    "")
        # No arguments, run normally
        main
        ;;
    *)
        print_error "Unknown option: $1"
        echo "Use --help for usage information"
        exit 1
        ;;
esac
