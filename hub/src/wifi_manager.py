"""
WiFi Management Module
Handles WiFi hotspot creation and management across different operating systems
"""
import subprocess
import platform
import socket
import os
from typing import Dict, List, Optional, Any, Tuple

class WiFiManager:
    def __init__(self, ssid: str = "EkatraHub", password: str = "your_wifi_password_here"):
        self.system = platform.system()
        self.hotspot_active = False
        self.ssid = ssid
        self.password = password
        self.connected_devices = []
        
    def start_hotspot(self) -> bool:
        """Start WiFi hotspot based on operating system"""
        print(f"🌐 Starting WiFi hotspot: {self.ssid}")
        
        try:
            if self.system == "Windows":
                return self._start_windows_hotspot()
            elif self.system == "Linux":
                return self._start_linux_hotspot()
            elif self.system == "Darwin":  # macOS
                return self._start_macos_hotspot()
            else:
                print(f"❌ Unsupported operating system: {self.system}")
                return False
                
        except Exception as e:
            print(f"❌ Error starting hotspot: {str(e)}")
            return False
    
    def _start_windows_hotspot(self) -> bool:
        """Start Windows hosted network"""
        try:
            # Stop any existing hosted network
            subprocess.run(["netsh", "wlan", "stop", "hostednetwork"], 
                         capture_output=True, check=False)
            
            # Configure hosted network
            cmd_config = [
                "netsh", "wlan", "set", "hostednetwork", 
                "mode=allow", f"ssid={self.ssid}", f"key={self.password}"
            ]
            result = subprocess.run(cmd_config, capture_output=True, text=True)
            
            if result.returncode == 0:
                # Start hosted network
                cmd_start = ["netsh", "wlan", "start", "hostednetwork"]
                result_start = subprocess.run(cmd_start, capture_output=True, text=True)
                
                if result_start.returncode == 0:
                    self.hotspot_active = True
                    print("✅ Windows WiFi hotspot started successfully")
                    return True
                else:
                    print(f"❌ Failed to start Windows hotspot: {result_start.stderr}")
                    return False
            else:
                print(f"❌ Failed to configure Windows hotspot: {result.stderr}")
                return False
                
        except Exception as e:
            print(f"❌ Windows hotspot error: {str(e)}")
            return False
    
    def _start_linux_hotspot(self) -> bool:
        """Start Linux hostapd-based hotspot"""
        try:
            # Check if hostapd is installed
            result = subprocess.run(["which", "hostapd"], capture_output=True)
            if result.returncode != 0:
                print("❌ hostapd not found. Install with: sudo apt-get install hostapd dnsmasq")
                return False
            
            # Create temporary hostapd configuration
            hostapd_conf = f"""interface=wlan0
driver=nl80211
ssid={self.ssid}
hw_mode=g
channel=7
wmm_enabled=0
macaddr_acl=0
auth_algs=1
ignore_broadcast_ssid=0
wpa=2
wpa_passphrase={self.password}
wpa_key_mgmt=WPA-PSK
wpa_pairwise=TKIP
rsn_pairwise=CCMP
"""
            
            with open("/tmp/ekatra_hostapd.conf", "w") as f:
                f.write(hostapd_conf)
            
            # Start hostapd in background
            self.hostapd_process = subprocess.Popen([
                "sudo", "hostapd", "/tmp/ekatra_hostapd.conf"
            ], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            
            self.hotspot_active = True
            print("✅ Linux WiFi hotspot started successfully")
            return True
            
        except Exception as e:
            print(f"❌ Linux hotspot error: {str(e)}")
            return False
    
    def _start_macos_hotspot(self) -> bool:
        """Start macOS hotspot (requires manual configuration)"""
        print("🍎 macOS Internet Sharing Setup Required:")
        print("1. Go to System Preferences → Sharing")
        print("2. Select 'Internet Sharing'")
        print("3. Share from: Ethernet (or your internet source)")
        print("4. To computers using: Wi-Fi")
        print(f"5. Set Wi-Fi name to: {self.ssid}")
        print(f"6. Set password to: {self.password}")
        print("7. Check the Internet Sharing checkbox")
        
        # Check if already active
        if self._check_macos_internet_sharing():
            self.hotspot_active = True
            print("✅ Internet Sharing is already active!")
            return True
        
        print("\n⏳ Checking Internet Sharing status...")
        # Give user time to enable it, then check
        import time
        for i in range(30):  # Check for 30 seconds
            time.sleep(1)
            if self._check_macos_internet_sharing():
                self.hotspot_active = True
                print("✅ Internet Sharing detected!")
                return True
            if i % 5 == 0:
                print(f"   Still waiting... ({30-i}s remaining)")
        
        print("⚠️ Internet Sharing not detected. Please enable it manually.")
        return False
    
    def _check_macos_internet_sharing(self) -> bool:
        """Check if macOS Internet Sharing is actually active"""
        try:
            # Method 1: Check if we have hotspot IP configured
            result = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=5)
            if "192.168.2.1" in result.stdout:
                return True
                
            # Method 2: Check if bridge100 interface exists (Internet Sharing creates this)
            result = subprocess.run(["ifconfig", "bridge100"], capture_output=True, text=True, timeout=5)
            if result.returncode == 0 and "UP" in result.stdout:
                return True
                
            # Method 3: Check Internet Sharing preference
            try:
                result = subprocess.run([
                    "defaults", "read", 
                    "/Library/Preferences/SystemConfiguration/com.apple.nat", "NAT"
                ], capture_output=True, text=True, timeout=5)
                
                if result.returncode == 0 and "Enabled" in result.stdout:
                    return True
            except:
                pass
                
            return False
            
        except Exception as e:
            print(f"Error checking macOS Internet Sharing: {e}")
            return False
    
    def stop_hotspot(self) -> bool:
        """Stop WiFi hotspot"""
        try:
            if self.system == "Windows":
                result = subprocess.run(
                    ["netsh", "wlan", "stop", "hostednetwork"], 
                    capture_output=True, text=True
                )
                if result.returncode == 0:
                    self.hotspot_active = False
                    print("✅ Windows WiFi hotspot stopped")
                    return True
                else:
                    print(f"❌ Failed to stop Windows hotspot: {result.stderr}")
                    return False
                    
            elif self.system == "Linux":
                # Kill hostapd process
                if hasattr(self, 'hostapd_process'):
                    self.hostapd_process.terminate()
                subprocess.run(["sudo", "pkill", "hostapd"], capture_output=True)
                self.hotspot_active = False
                print("✅ Linux WiFi hotspot stopped")
                return True
                
            elif self.system == "Darwin":
                print("🍎 Please manually disable Internet Sharing in System Preferences")
                self.hotspot_active = False
                return True
                
        except Exception as e:
            print(f"❌ Error stopping hotspot: {str(e)}")
            return False
        
        return False
    
    def get_connected_devices(self) -> List[Dict[str, Any]]:
        """Get list of connected devices on both hotspot and local network"""
        devices = []
        try:
            # Get current network subnet to scan
            current_ip = self.get_host_ip()
            if current_ip == "127.0.0.1":
                return devices
            
            # Determine network subnet to scan
            ip_parts = current_ip.split('.')
            base_network = f"{ip_parts[0]}.{ip_parts[1]}.{ip_parts[2]}"
            
            print(f"🔍 Scanning network {base_network}.x for devices...")
            
            if self.system == "Darwin":  # macOS
                # Get ARP table
                result = subprocess.run(["arp", "-a"], capture_output=True, text=True, timeout=10)
                if result.returncode == 0:
                    for line in result.stdout.split('\n'):
                        # Look for devices in the current network subnet
                        if f"{base_network}." in line and current_ip not in line:
                            parts = line.split()
                            if len(parts) >= 3:
                                # Extract IP from format like "(192.168.1.100)" 
                                ip_match = next((p for p in parts if f"{base_network}." in p), None)
                                if ip_match:
                                    ip = ip_match.strip('()')
                                    # Find MAC address (format like "aa:bb:cc:dd:ee:ff")
                                    mac = next((p for p in parts if ':' in p and len(p.split(':')) == 6), "Unknown")
                                    
                                    devices.append({
                                        "ip": ip,
                                        "mac": mac,
                                        "hostname": f"Device-{ip.split('.')[-1]}",
                                        "connected_time": "Active",
                                        "data_usage": "N/A",
                                        "network_type": "Hotspot" if "192.168.2." in ip else "Local Network"
                                    })
                                    
            elif self.system == "Windows":
                # Get ARP table for Windows
                result = subprocess.run(["arp", "-a"], capture_output=True, text=True, timeout=10)
                if result.returncode == 0:
                    for line in result.stdout.split('\n'):
                        # Look for devices in current network and known hotspot networks
                        if (f"{base_network}." in line or "192.168.137." in line or "192.168.4." in line) and current_ip not in line:
                            parts = line.split()
                            if len(parts) >= 3:
                                ip = parts[0].strip()
                                mac = parts[1] if len(parts) > 1 else "Unknown"
                                
                                devices.append({
                                    "ip": ip,
                                    "mac": mac,
                                    "hostname": f"Device-{ip.split('.')[-1]}",
                                    "connected_time": "Active",
                                    "data_usage": "N/A",
                                    "network_type": "Hotspot" if ip.startswith("192.168.137.") else "Local Network"
                                })
                                
            elif self.system == "Linux":
                # Get ARP table for Linux
                result = subprocess.run(["arp", "-a"], capture_output=True, text=True, timeout=10)
                if result.returncode == 0:
                    for line in result.stdout.split('\n'):
                        if (f"{base_network}." in line or "192.168.4." in line) and current_ip not in line:
                            parts = line.split()
                            if len(parts) >= 3:
                                ip = parts[1].strip('()')
                                mac = parts[3] if len(parts) > 3 else "Unknown"
                                
                                devices.append({
                                    "ip": ip,
                                    "mac": mac,
                                    "hostname": f"Device-{ip.split('.')[-1]}",
                                    "connected_time": "Active",
                                    "data_usage": "N/A",
                                    "network_type": "Hotspot" if ip.startswith("192.168.4.") else "Local Network"
                                })
                                
            print(f"📱 Found {len(devices)} device(s) on the network")
            for device in devices:
                print(f"   {device['ip']} ({device['network_type']}) - {device['mac']}")
                            
        except Exception as e:
            print(f"❌ Error getting connected devices: {str(e)}")
            
        return devices
    
    def get_network_info(self) -> Dict[str, Any]:
        """Get current network configuration"""
        # Update hotspot status for macOS
        if self.system == "Darwin":
            self.hotspot_active = self._check_macos_internet_sharing()
        
        return {
            "ssid": self.ssid,
            "password": "••••••••••",  # Hide password in UI
            "active": self.hotspot_active,
            "ip_address": self.get_host_ip(),
            "connected_devices": len(self.get_connected_devices()),
            "system": self.system
        }
    
    def get_host_ip(self) -> str:
        """Get the host IP address based on system and current network state"""
        try:
            if self.system == "Windows" and self.hotspot_active:
                return "192.168.137.1"
            elif self.system == "Linux" and self.hotspot_active:
                return "192.168.4.1"
            elif self.system == "Darwin" and self.hotspot_active:
                return "192.168.2.1"
            else:
                # Not in hotspot mode or hotspot not active - get actual network IP
                if self.system == "Darwin":
                    # For macOS, check ifconfig for active network interfaces
                    result = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=5)
                    if result.returncode == 0:
                        lines = result.stdout.split('\n')
                        current_interface = None
                        
                        for line in lines:
                            # Detect interface name
                            if not line.startswith('\t') and not line.startswith(' ') and ':' in line:
                                current_interface = line.split(':')[0]
                            elif 'inet ' in line and '127.0.0.1' not in line and 'inet 192.168.2.1' not in line:
                                # Skip hotspot IP and localhost
                                parts = line.strip().split()
                                for i, part in enumerate(parts):
                                    if part == 'inet' and i+1 < len(parts):
                                        ip = parts[i+1]
                                        # Prefer WiFi interfaces and common local network ranges
                                        if (current_interface and ('en' in current_interface or 'wl' in current_interface)) or ip.startswith(('192.168.', '10.', '172.')):
                                            return ip
                
                # Fallback method for all systems
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                try:
                    # Connect to Google DNS to determine which interface is used for internet
                    s.connect(("8.8.8.8", 80))
                    ip = s.getsockname()[0]
                    s.close()
                    return ip
                except:
                    s.close()
                    return "127.0.0.1"
        except Exception as e:
            print(f"Error getting host IP: {e}")
            return "127.0.0.1"
    
    def is_hotspot_supported(self) -> Tuple[bool, str]:
        """Check if hotspot is supported on this system"""
        if self.system == "Windows":
            try:
                result = subprocess.run(
                    ["netsh", "wlan", "show", "drivers"], 
                    capture_output=True, text=True
                )
                if "Hosted network supported: Yes" in result.stdout:
                    return True, "Windows hosted network supported"
                else:
                    return False, "Windows WiFi adapter doesn't support hosted networks"
            except:
                return False, "Unable to check Windows hotspot support"
                
        elif self.system == "Linux":
            # Check if required tools are available
            tools = ["hostapd", "dnsmasq"]
            missing = []
            for tool in tools:
                result = subprocess.run(["which", tool], capture_output=True)
                if result.returncode != 0:
                    missing.append(tool)
            
            if missing:
                return False, f"Missing required tools: {', '.join(missing)}"
            else:
                return True, "Linux hotspot tools available"
                
        elif self.system == "Darwin":
            return True, "macOS Internet Sharing available (manual setup required)"
        
        return False, f"Unsupported system: {self.system}"
