#!/usr/bin/env python3
"""
Network Discovery Tool for Ekatra AI System
"""
import subprocess
import socket
import time

def get_all_ips():
    """Get all IP addresses on this system"""
    try:
        result = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=5)
        ips = []
        current_interface = None
        
        for line in result.stdout.split('\n'):
            if not line.startswith('\t') and not line.startswith(' ') and line.strip():
                current_interface = line.split(':')[0]
            elif 'inet ' in line and '127.0.0.1' not in line:
                parts = line.strip().split()
                for i, part in enumerate(parts):
                    if part == 'inet' and i+1 < len(parts):
                        ip = parts[i+1]
                        ips.append({
                            "interface": current_interface,
                            "ip": ip,
                            "type": "WiFi Hotspot" if "192.168.2." in ip or "192.168.137." in ip 
                                   else "Local Network" if "192.168." in ip or "10." in ip 
                                   else "Other"
                        })
        return ips
    except Exception as e:
        print(f"Error getting IPs: {e}")
        return []

def check_port_listening(port=5000):
    """Check if port is listening"""
    try:
        result = subprocess.run(["lsof", "-i", f":{port}"], capture_output=True, text=True)
        return result.returncode == 0, result.stdout
    except:
        return False, "Unable to check"

def test_server_access(ip, port=5000):
    """Test if we can connect to the server"""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(2)
        result = sock.connect_ex((ip, port))
        sock.close()
        return result == 0
    except:
        return False

def main():
    print("🔍 Ekatra AI Network Discovery")
    print("=" * 50)
    
    # Get all IPs
    ips = get_all_ips()
    
    if not ips:
        print("❌ No network interfaces found!")
        return
    
    print("📡 Available Network Interfaces:")
    for ip_info in ips:
        print(f"   {ip_info['interface']}: {ip_info['ip']} ({ip_info['type']})")
    
    print()
    
    # Check if server is running
    is_listening, port_info = check_port_listening()
    
    if is_listening:
        print("✅ Server is listening on port 5000")
    else:
        print("❌ Server is NOT listening on port 5000")
        print("   Make sure your server is running with: ./run.sh")
        return
    
    print()
    print("🌐 Testing Server Access:")
    
    working_urls = []
    
    for ip_info in ips:
        ip = ip_info['ip']
        can_connect = test_server_access(ip)
        status = "✅ Working" if can_connect else "❌ Not accessible"
        print(f"   http://{ip}:5000 - {status}")
        
        if can_connect:
            working_urls.append(f"http://{ip}:5000")
    
    print()
    
    if working_urls:
        print("🎯 Use these URLs to access your Ekatra AI system:")
        for url in working_urls:
            print(f"   📱 Client: {url}")
            print(f"   🛡️ Admin:  {url}/admin")
        
        print()
        print("📋 For clients to connect:")
        print("1. Connect to WiFi network: 'EkatraHub'")
        print("2. Open browser and go to one of the working URLs above")
        
        # Find the best URL to recommend
        hotspot_url = next((url for url in working_urls if "192.168.2." in url or "192.168.137." in url), None)
        local_url = next((url for url in working_urls if "192.168.1." in url or "10." in url), None)
        
        if hotspot_url:
            print(f"3. Recommended hotspot URL: {hotspot_url}")
        elif local_url:
            print(f"3. Recommended local network URL: {local_url}")
            
    else:
        print("❌ Server is not accessible on any interface!")
        print("💡 Possible solutions:")
        print("1. Check if your firewall is blocking connections")
        print("2. Make sure server is bound to 0.0.0.0 (all interfaces)")
        print("3. Try restarting the server")

if __name__ == "__main__":
    main()
