#!/usr/bin/env python3
"""
Network debugging script for Ekatra AI
"""
import subprocess
import socket
import requests
import time

def check_interfaces():
    """Check all network interfaces"""
    print("🔍 Network Interfaces:")
    try:
        result = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=10)
        interfaces = {}
        current_interface = None
        
        for line in result.stdout.split('\n'):
            if not line.startswith('\t') and not line.startswith(' ') and ':' in line:
                current_interface = line.split(':')[0]
                interfaces[current_interface] = []
            elif 'inet ' in line and current_interface:
                ip = line.strip().split()[1]
                interfaces[current_interface].append(ip)
        
        for interface, ips in interfaces.items():
            if ips:
                print(f"   {interface}: {', '.join(ips)}")
        
        return interfaces
    except Exception as e:
        print(f"❌ Error checking interfaces: {e}")
        return {}

def test_connectivity():
    """Test various connection endpoints"""
    endpoints = [
        "http://127.0.0.1:5000/health",
        "http://192.168.1.10:5000/health", 
        "http://192.168.2.1:5000/health"
    ]
    
    print("\n🔗 Testing Connectivity:")
    for endpoint in endpoints:
        try:
            response = requests.get(endpoint, timeout=3)
            print(f"   ✅ {endpoint} - Status: {response.status_code}")
        except requests.exceptions.ConnectionError:
            print(f"   ❌ {endpoint} - Connection refused")
        except requests.exceptions.Timeout:
            print(f"   ⏱️ {endpoint} - Timeout")
        except Exception as e:
            print(f"   ❌ {endpoint} - Error: {e}")

def check_internet_sharing():
    """Check if Internet Sharing is really active"""
    print("\n🔍 Internet Sharing Status:")
    
    # Check bridge interfaces (Internet Sharing creates these)
    try:
        result = subprocess.run(["ifconfig"], capture_output=True, text=True)
        if "bridge100" in result.stdout or "bridge101" in result.stdout:
            print("   ✅ Bridge interface detected (Internet Sharing active)")
            
            # Extract bridge info
            lines = result.stdout.split('\n')
            for i, line in enumerate(lines):
                if 'bridge10' in line and ':' in line:
                    bridge_name = line.split(':')[0]
                    print(f"   📡 Bridge: {bridge_name}")
                    # Look for IP on this bridge
                    for j in range(i+1, min(i+10, len(lines))):
                        if 'inet ' in lines[j]:
                            ip = lines[j].strip().split()[1]
                            print(f"   🌐 Bridge IP: {ip}")
                            break
        else:
            print("   ❌ No bridge interface found")
    except Exception as e:
        print(f"   ❌ Error checking bridges: {e}")
    
    # Check NAT configuration
    try:
        result = subprocess.run([
            "defaults", "read", 
            "/Library/Preferences/SystemConfiguration/com.apple.nat", "NAT"
        ], capture_output=True, text=True)
        
        if result.returncode == 0:
            print(f"   ℹ️ NAT Config: {result.stdout.strip()}")
        else:
            print("   ⚠️ No NAT configuration found")
    except Exception as e:
        print(f"   ⚠️ Could not read NAT config: {e}")

def check_routing():
    """Check routing table"""
    print("\n🛤️ Routing Information:")
    try:
        # Check default route
        result = subprocess.run(["route", "-n", "get", "default"], capture_output=True, text=True)
        for line in result.stdout.split('\n'):
            if 'gateway:' in line or 'interface:' in line:
                print(f"   {line.strip()}")
        
        # Check specific routes
        routes_to_check = ["192.168.1.0/24", "192.168.2.0/24"]
        for route in routes_to_check:
            try:
                result = subprocess.run(["route", "-n", "get", route.split('/')[0]], 
                                      capture_output=True, text=True, timeout=3)
                if result.returncode == 0:
                    print(f"   ✅ Route exists for {route}")
                else:
                    print(f"   ❌ No route for {route}")
            except:
                print(f"   ⚠️ Could not check route for {route}")
                
    except Exception as e:
        print(f"❌ Error checking routing: {e}")

def check_listening_ports():
    """Check what's actually listening"""
    print("\n👂 Listening Ports:")
    try:
        result = subprocess.run(["lsof", "-i", ":5000"], capture_output=True, text=True)
        if result.stdout:
            lines = result.stdout.split('\n')[1:]  # Skip header
            for line in lines:
                if line.strip():
                    parts = line.split()
                    if len(parts) >= 9:
                        print(f"   🎯 {parts[0]} (PID {parts[1]}) listening on {parts[8]}")
    except Exception as e:
        print(f"❌ Error checking ports: {e}")

def ping_test():
    """Test if we can ping the hotspot IP"""
    print("\n🏓 Ping Tests:")
    ips_to_ping = ["127.0.0.1", "192.168.1.10", "192.168.2.1"]
    
    for ip in ips_to_ping:
        try:
            result = subprocess.run(["ping", "-c", "3", ip], 
                                  capture_output=True, text=True, timeout=10)
            if result.returncode == 0:
                # Extract round-trip time
                lines = result.stdout.split('\n')
                for line in lines:
                    if 'round-trip' in line:
                        print(f"   ✅ {ip} - {line.split('=')[-1].strip()}")
                        break
                else:
                    print(f"   ✅ {ip} - Reachable")
            else:
                print(f"   ❌ {ip} - Not reachable")
        except Exception as e:
            print(f"   ❌ {ip} - Error: {e}")

if __name__ == "__main__":
    print("🔍 EKATRA AI NETWORK DIAGNOSTICS")
    print("=" * 50)
    
    check_interfaces()
    test_connectivity()
    check_internet_sharing()
    check_routing()
    check_listening_ports()
    ping_test()
    
    print("\n" + "=" * 50)
    print("💡 TROUBLESHOOTING GUIDE:")
    print("1. If 192.168.1.10:5000 works → Use regular network mode")
    print("2. If 192.168.2.1 not reachable → Internet Sharing not properly configured")
    print("3. Check System Preferences > Sharing > Internet Sharing")
    print("4. Try accessing from a device connected to 'EkatraHub' network")
