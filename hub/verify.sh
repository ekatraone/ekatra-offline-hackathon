#!/bin/bash

# Ekatra AI System Verification Script
# ====================================

echo "🔍 Ekatra AI System Verification"
echo "================================="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

PASS=0
FAIL=0

check_pass() {
    echo -e "${GREEN}✅ $1${NC}"
    ((PASS++))
}

check_fail() {
    echo -e "${RED}❌ $1${NC}"
    ((FAIL++))
}

check_warn() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

echo "📋 Checking Files and Permissions:"
echo "-----------------------------------"

# Check main files
files=("server.py" "start.sh" "restart.sh" "status.sh" "run.sh" "setup.sh")
for file in "${files[@]}"; do
    if [ -f "$file" ]; then
        if [ -x "$file" ] || [[ "$file" == *.py ]]; then
            check_pass "$file exists and is executable"
        else
            check_fail "$file exists but not executable"
        fi
    else
        check_fail "$file missing"
    fi
done

echo ""
echo "📦 Checking Dependencies:"
echo "-------------------------"

# Check Python environment
if [ -d "venv" ]; then
    check_pass "Virtual environment exists"
    
    # Activate and check packages
    source venv/bin/activate
    
    packages=("flask" "transformers" "torch" "psutil")
    for package in "${packages[@]}"; do
        if python -c "import $package" 2>/dev/null; then
            check_pass "$package installed"
        else
            check_fail "$package missing"
        fi
    done
else
    check_fail "Virtual environment missing - run ./setup.sh"
fi

echo ""
echo "⚙️  Checking Configuration:"
echo "---------------------------"

if [ -f ".env" ]; then
    check_pass ".env file exists"
    
    # Check for important settings
    if grep -q "MAX_TOKENS=500" .env; then
        check_pass "MAX_TOKENS set to 500 (good response length)"
    else
        check_warn "MAX_TOKENS not set to 500 (responses may be short)"
    fi
    
    if grep -q "HF_TOKEN=" .env; then
        check_pass "HuggingFace token configured"
    else
        check_warn "HuggingFace token not set (may limit model access)"
    fi
else
    check_fail ".env file missing"
fi

echo ""
echo "🖥️  System Capabilities:"
echo "------------------------"

# Check Python version
python_version=$(python3 -c 'import sys; print(".".join(map(str, sys.version_info[:2])))')
version_check=$(python3 -c "import sys; print(1 if sys.version_info >= (3, 8) else 0)")
if [ "$version_check" == "1" ]; then
    check_pass "Python $python_version (compatible)"
else
    check_fail "Python $python_version (need 3.8+)"
fi

# Check system type
system=$(uname -s)
check_pass "System: $system"

# Check hardware capabilities
if [ -f "venv/bin/python" ]; then
    source venv/bin/activate
    device_info=$(python -c "
import torch
import platform
try:
    if torch.cuda.is_available():
        print(f'NVIDIA GPU: {torch.cuda.get_device_name(0)}')
    elif hasattr(torch.backends, 'mps') and torch.backends.mps.is_available():
        print(f'Apple Silicon GPU: {platform.processor()}')
    else:
        print(f'CPU only: {platform.processor()}')
except:
    print('Unable to detect hardware')
" 2>/dev/null)
    check_pass "Hardware: $device_info"
fi

echo ""
echo "🌐 Network Status:"
echo "------------------"

# Check port availability
if lsof -Pi :5000 -sTCP:LISTEN -t >/dev/null 2>&1; then
    check_warn "Port 5000 is in use (server may be running)"
else
    check_pass "Port 5000 available"
fi

# Check for network interfaces
if command -v ifconfig >/dev/null 2>&1; then
    ip_count=$(ifconfig | grep 'inet ' | grep -v '127.0.0.1' | wc -l)
    check_pass "Network interfaces: $ip_count"
    
    if [[ "$system" == "Darwin" ]]; then
        if ifconfig | grep -q "192.168.2.1"; then
            check_pass "Internet Sharing detected"
        else
            check_warn "Internet Sharing not active"
        fi
    fi
fi

echo ""
echo "📊 Summary:"
echo "-----------"
echo -e "${GREEN}✅ Passed: $PASS${NC}"
if [ $FAIL -gt 0 ]; then
    echo -e "${RED}❌ Failed: $FAIL${NC}"
    echo ""
    echo "⚠️  Please fix the failed items before running the system"
else
    echo -e "${GREEN}🎉 All checks passed! System ready to run.${NC}"
    echo ""
    echo "🚀 Start the system with: ./start.sh"
fi

echo ""
echo "🔗 Quick Commands:"
echo "  ./start.sh    - Start the system"
echo "  ./status.sh   - Check system status"  
echo "  ./restart.sh  - Restart system"
