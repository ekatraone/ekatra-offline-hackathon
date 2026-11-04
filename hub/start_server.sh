#!/bin/bash

# Ekatra AI v3.0 - Production Startup Script
# Handles environment setup, dependency checks, and server startup

set -e  # Exit on error

# =============================================================================
# Configuration
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
VENV_DIR="$PROJECT_ROOT/venv"
FRONTEND_DIR="$PROJECT_ROOT/frontend"
LOG_DIR="$PROJECT_ROOT/logs"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# =============================================================================
# Helper Functions
# =============================================================================

log_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

log_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

log_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

log_error() {
    echo -e "${RED}❌ $1${NC}"
}

check_command() {
    if command -v "$1" &> /dev/null; then
        return 0
    else
        return 1
    fi
}

# =============================================================================
# Environment Validation
# =============================================================================

check_environment() {
    log_info "Checking system environment..."
    
    # Check Python
    if ! check_command python3; then
        log_error "Python 3 not found. Please install Python 3.8 or higher."
        exit 1
    fi
    
    python_version=$(python3 -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
    log_success "Python $python_version found"
    
    # Check Node.js (for frontend builds)
    if ! check_command node; then
        log_warning "Node.js not found. Frontend build may not work."
    else
        node_version=$(node --version)
        log_success "Node.js $node_version found"
    fi
    
    # Check required directories
    if [[ ! -f "$PROJECT_ROOT/server.py" ]]; then
        log_error "server.py not found. Please run from project root."
        exit 1
    fi
    
    # Create log directory
    mkdir -p "$LOG_DIR"
    log_success "Log directory ready"
}

# =============================================================================
# Virtual Environment Setup
# =============================================================================

setup_virtual_environment() {
    log_info "Setting up Python virtual environment..."
    
    if [[ ! -d "$VENV_DIR" ]]; then
        log_info "Creating virtual environment..."
        python3 -m venv "$VENV_DIR"
        log_success "Virtual environment created"
    fi
    
    # Activate virtual environment
    source "$VENV_DIR/bin/activate"
    log_success "Virtual environment activated"
    
    # Upgrade pip
    pip install --upgrade pip > /dev/null 2>&1
    
    # Install/update dependencies
    if [[ -f "$PROJECT_ROOT/requirements.txt" ]]; then
        log_info "Installing Python dependencies..."
        pip install -r "$PROJECT_ROOT/requirements.txt" > /dev/null 2>&1
        log_success "Python dependencies installed"
    else
        log_warning "requirements.txt not found"
    fi
}

# =============================================================================
# Frontend Build
# =============================================================================

build_frontend() {
    log_info "Checking frontend build..."
    
    if [[ ! -d "$FRONTEND_DIR" ]]; then
        log_warning "Frontend directory not found. Skipping frontend build."
        return 0
    fi
    
    cd "$FRONTEND_DIR"
    
    # Check if build exists and is recent
    if [[ -d "build" ]] && [[ -f "build/index.html" ]]; then
        # Check if build is newer than source files
        if [[ "build/index.html" -nt "src/App.js" ]]; then
            log_success "Frontend build is up to date"
            cd "$PROJECT_ROOT"
            return 0
        fi
    fi
    
    # Build frontend if Node.js is available
    if check_command npm; then
        log_info "Building React frontend..."
        
        # Install dependencies if needed
        if [[ ! -d "node_modules" ]]; then
            log_info "Installing Node.js dependencies..."
            npm install > /dev/null 2>&1
        fi
        
        # Build production version
        npm run build > /dev/null 2>&1
        log_success "Frontend build completed"
    else
        log_warning "Node.js not available. Using existing build if available."
    fi
    
    cd "$PROJECT_ROOT"
}

# =============================================================================
# Configuration Validation
# =============================================================================

validate_configuration() {
    log_info "Validating configuration..."
    
    # Check for required environment variables
    if [[ -z "$HF_TOKEN" ]] && [[ "${USE_LOCAL_MODEL:-true}" == "true" ]]; then
        log_warning "HF_TOKEN not set. Model download may fail."
    fi
    
    # Check model directory
    if [[ ! -d "models" ]]; then
        mkdir -p models
        log_success "Models directory created"
    fi
    
    # Validate config using Python
    source "$VENV_DIR/bin/activate"
    python3 -c "
from config.settings import Config
issues = Config.validate()
if issues:
    print('\\n'.join(issues))
    exit(1)
else:
    print('Configuration valid')
" && log_success "Configuration validation passed" || {
        log_error "Configuration validation failed"
        exit 1
    }
}

# =============================================================================
# Server Startup
# =============================================================================

start_server() {
    log_info "Starting Ekatra AI v3.0 server..."
    
    # Activate virtual environment
    source "$VENV_DIR/bin/activate"
    
    # Set environment variables for tokenizers
    export TOKENIZERS_PARALLELISM=false
    export PYTHONPATH="$PROJECT_ROOT:$PYTHONPATH"
    
    # Get server configuration
    HOST="${EKATRA_HOST:-0.0.0.0}"
    PORT="${EKATRA_PORT:-5000}"
    DEBUG="${EKATRA_DEBUG:-false}"
    
    log_success "Server configuration:"
    echo "  Host: $HOST"
    echo "  Port: $PORT"
    echo "  Debug: $DEBUG"
    echo "  Frontend: http://$HOST:$PORT/client"
    echo "  Admin: http://$HOST:$PORT/admin"
    echo ""
    
    # Start server
    log_info "🚀 Launching server..."
    python3 server.py
}

# =============================================================================
# Cleanup and Signal Handling
# =============================================================================

cleanup() {
    log_info "Cleaning up..."
    # Kill any background processes if needed
    jobs -p | xargs -r kill
    log_success "Cleanup completed"
}

# Set trap for cleanup on exit
trap cleanup EXIT

# Handle interruption signals
handle_signal() {
    log_info "Received interrupt signal"
    cleanup
    exit 0
}

trap handle_signal SIGINT SIGTERM

# =============================================================================
# Main Execution
# =============================================================================

main() {
    echo "🚀 Ekatra AI v3.0 - Production Startup"
    echo "======================================"
    echo ""
    
    # Run all setup steps
    check_environment
    setup_virtual_environment
    build_frontend
    validate_configuration
    
    echo ""
    log_success "All checks passed! Starting server..."
    echo ""
    
    # Start the server
    start_server
}

# =============================================================================
# Command Line Options
# =============================================================================

show_help() {
    echo "Ekatra AI v3.0 Startup Script"
    echo ""
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --help              Show this help message"
    echo "  --check-only        Only run environment checks"
    echo "  --build-frontend    Only build the frontend"
    echo "  --setup-only        Only setup environment, don't start server"
    echo ""
    echo "Environment Variables:"
    echo "  EKATRA_HOST         Server host (default: 0.0.0.0)"
    echo "  EKATRA_PORT         Server port (default: 5000)"
    echo "  EKATRA_DEBUG        Enable debug mode (default: false)"
    echo "  HF_TOKEN           Hugging Face token for model downloads"
    echo "  LOG_LEVEL          Logging level (default: INFO)"
    echo ""
}

# Parse command line arguments
case "${1:-}" in
    --help)
        show_help
        exit 0
        ;;
    --check-only)
        check_environment
        validate_configuration
        log_success "Environment check completed"
        exit 0
        ;;
    --build-frontend)
        check_environment
        build_frontend
        log_success "Frontend build completed"
        exit 0
        ;;
    --setup-only)
        check_environment
        setup_virtual_environment
        build_frontend
        validate_configuration
        log_success "Setup completed. Run without --setup-only to start server."
        exit 0
        ;;
    "")
        main
        ;;
    *)
        log_error "Unknown option: $1"
        show_help
        exit 1
        ;;
esac
