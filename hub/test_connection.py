#!/usr/bin/env python3
"""
Quick network connectivity test
"""
import socket
import subprocess
import requests
import time

def test_local_connection():
    """Test if we can connect to our own server"""
    try:
        response = requests.get("http://127.0.0.1:5000/health", timeout=5)
        print(f"✅ Local connection works: {response.status_code}")
        return True
    except Exception as e:
        print(f"❌ Local connection failed: {e}")
        return False

def test_network_connection():
    """Test if we can connect via network IP"""
    try:
        # Get our IP
        result = subprocess.run(["ifconfig"], capture_output=True, text=True)
        ip = None
        for line in result.stdout.split('\n'):
            if 'inet 192.168' in line and 'netmask' in line:
                ip = line.split()[1]
                break
        
        if ip:
            print(f"Testing connection to {ip}:5000")
            response = requests.get(f"http://{ip}:5000/health", timeout=5)
            print(f"✅ Network connection works: {response.status_code}")
            return True
        else:
            print("❌ Could not find network IP")
            return False
    except Exception as e:
        print(f"❌ Network connection failed: {e}")
        return False

def test_socket_binding():
    """Test if we can bind to the port"""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('0.0.0.0', 5001))  # Test port 5001 to avoid conflict
        sock.listen(1)
        print("✅ Socket binding works")
        sock.close()
        return True
    except Exception as e:
        print(f"❌ Socket binding failed: {e}")
        return False

def check_firewall():
    """Check macOS firewall status"""
    try:
        result = subprocess.run(["sudo", "pfctl", "-s", "rules"], capture_output=True, text=True)
        if result.returncode == 0:
            print("ℹ️ Firewall rules active")
        else:
            print("ℹ️ No firewall rules found or pfctl not accessible")
    except Exception as e:
        print(f"ℹ️ Could not check firewall: {e}")

if __name__ == "__main__":
    print("🔍 Testing Ekatra AI Server Connectivity")
    print("=" * 50)
    
    print("\n1. Testing socket binding...")
    test_socket_binding()
    
    print("\n2. Testing local connection...")
    test_local_connection()
    
    print("\n3. Testing network connection...")
    test_network_connection()
    
    print("\n4. Checking firewall...")
    check_firewall()
    
    print("\n📋 Recommendations:")
    print("- Ensure your server is running on port 5000")
    print("- Have clients connect to: http://192.168.1.10:5000")
    print("- If still not working, check macOS firewall in System Preferences")
