"""
Improved WiFi Management Module for macOS
"""
import subprocess
import platform
import socket
import os
import json
from typing import Dict, List, Optional, Any, Tuple

class WiFiManager:
    def __init__(self, ssid: str = "EkatraHub", password: str = "your_wifi_password_here"):
        self.system = platform.system()
        self.hotspot_active = False
        self.ssid = ssid
        self.password = password
        self.connected_devices = []
        
    def _check_macos_internet_sharing(self) -> bool:
        """Check if macOS Internet Sharing is actually active"""
        try:
            # Method 1: Check if we have hotspot IP configured
            result = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=5)
            if "192.168.2.1" in result.stdout:
                return True
                
            # Method 2: Check Internet Sharing preference
            try:
                result = subprocess.run([
                    "defaults", "read", 
                    "/Library/Preferences/SystemConfiguration/com.apple.nat", "NAT"
                ], capture_output=True, text=True, timeout=5)
                
                if result.returncode == 0 and "Enabled" in result.stdout:
                    return True
            except:
                pass
                
            # Method 3: Check if bridge100 interface exists (Internet Sharing creates this)
            result = subprocess.run(["ifconfig", "bridge100"], capture_output=True, text=True, timeout=5)
            if result.returncode == 0 and "UP" in result.stdout:
                return True
                
            return False
            
        except Exception as e:
            print(f"Error checking macOS Internet Sharing: {e}")
            return False
    
    def start_hotspot(self) -> bool:
        """Start WiFi hotspot based on operating system"""
        print(f"🌐 Attempting to start WiFi hotspot: {self.ssid}")
        
        if self.system == "Darwin":  # macOS
            # Check if Internet Sharing is already active
            if self._check_macos_internet_sharing():
                self.hotspot_active = True
                print("✅ macOS Internet Sharing is already active")
                return True
            else:
                print("❌ Internet Sharing is not active")
                print("\n🔄 Running in regular network mode instead")
                print(f"📱 Server accessible at: {self.get_regular_network_ip()}:5000")
                print("\n💡 To enable hotspot mode:")
                print("1. System Preferences → Sharing → Internet Sharing")
                print(f"2. Set Wi-Fi name: {self.ssid}")
                print(f"3. Set password: {self.password}")
                print("4. Enable Internet Sharing")
                print("5. Restart the server")
                
                self.hotspot_active = False
                return False
        else:
            # Handle other systems (Windows, Linux)
            return self._start_other_system_hotspot()
    
    def _start_other_system_hotspot(self) -> bool:
        """Handle Windows and Linux hotspot creation"""
        # Original implementation for Windows/Linux
        try:
            if self.system == "Windows":
                return self._start_windows_hotspot()
            elif self.system == "Linux":
                return self._start_linux_hotspot()
        except Exception as e:
            print(f"❌ Error starting hotspot: {str(e)}")
            return False
        return False
    
    def _start_windows_hotspot(self) -> bool:
        """Start Windows hosted network"""
        try:
            subprocess.run(["netsh", "wlan", "stop", "hostednetwork"], 
                         capture_output=True, check=False)
            
            cmd_config = [
                "netsh", "wlan", "set", "hostednetwork", 
                "mode=allow", f"ssid={self.ssid}", f"key={self.password}"
            ]
            result = subprocess.run(cmd_config, capture_output=True, text=True)
            
            if result.returncode == 0:
                cmd_start = ["netsh", "wlan", "start", "hostednetwork"]
                result_start = subprocess.run(cmd_start, capture_output=True, text=True)
                
                if result_start.returncode == 0:
                    self.hotspot_active = True
                    print("✅ Windows WiFi hotspot started successfully")
                    return True
                    
        except Exception as e:
            print(f"❌ Windows hotspot error: {str(e)}")
        return False
    
    def _start_linux_hotspot(self) -> bool:
        """Start Linux hostapd-based hotspot"""
        try:
            result = subprocess.run(["which", "hostapd"], capture_output=True)
            if result.returncode != 0:
                print("❌ hostapd not found. Install with: sudo apt-get install hostapd dnsmasq")
                return False
            
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
            
            self.hostapd_process = subprocess.Popen([
                "sudo", "hostapd", "/tmp/ekatra_hostapd.conf"
            ], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            
            self.hotspot_active = True
            print("✅ Linux WiFi hotspot started successfully")
            return True
            
        except Exception as e:
            print(f"❌ Linux hotspot error: {str(e)}")
        return False
    
    def stop_hotspot(self) -> bool:
        """Stop WiFi hotspot"""
        try:
            if self.system == "Darwin":
                print("🍎 To stop Internet Sharing:")
                print("Go to System Preferences → Sharing and uncheck 'Internet Sharing'")
                self.hotspot_active = False
                return True
            elif self.system == "Windows":
                result = subprocess.run(
                    ["netsh", "wlan", "stop", "hostednetwork"], 
                    capture_output=True, text=True
                )
                if result.returncode == 0:
                    self.hotspot_active = False
                    print("✅ Windows WiFi hotspot stopped")
                    return True
            elif self.system == "Linux":
                if hasattr(self, 'hostapd_process'):
                    self.hostapd_process.terminate()
                subprocess.run(["sudo", "pkill", "hostapd"], capture_output=True)
                self.hotspot_active = False
                print("✅ Linux WiFi hotspot stopped")
                return True
                
        except Exception as e:
            print(f"❌ Error stopping hotspot: {str(e)}")
        return False
    
    def get_connected_devices(self) -> List[Dict[str, Any]]:
        """Get list of connected devices"""
        devices = []
        try:
            if self.system == "Darwin" and self.hotspot_active:
                # Check ARP table for devices on hotspot network
                result = subprocess.run(["arp", "-a"], capture_output=True, text=True, timeout=5)
                for line in result.stdout.split('\n'):
                    if "192.168.2." in line:  # macOS hotspot network
                        parts = line.split()
                        if len(parts) >= 3:
                            ip = parts[1].strip('()')
                            mac = parts[3] if len(parts) > 3 else "Unknown"
                            devices.append({
                                "ip": ip,
                                "mac": mac,
                                "hostname": f"Device-{ip.split('.')[-1]}",
                                "connected_time": "Active",
                                "data_usage": "N/A"
                            })
        except Exception as e:
            print(f"Error getting connected devices: {e}")
        return devices
    
    def get_network_info(self) -> Dict[str, Any]:
        """Get current network configuration with real status"""
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
        """Get the correct host IP address"""
        try:
            if self.system == "Darwin":
                # Check if Internet Sharing is active
                if self._check_macos_internet_sharing():
                    return "192.168.2.1"  # macOS hotspot IP
                else:
                    # Return regular network IP
                    result = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=5)
                    for line in result.stdout.split('\n'):
                        if 'inet 192.168' in line and 'netmask' in line and '192.168.2.1' not in line:
                            return line.split()[1]
                    
            elif self.system == "Windows" and self.hotspot_active:
                return "192.168.137.1"
            elif self.system == "Linux" and self.hotspot_active:
                return "192.168.4.1"
            
            # Fallback to detecting regular network IP
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                s.connect(("8.8.8.8", 80))
                ip = s.getsockname()[0]
                s.close()
                return ip
            except:
                return "127.0.0.1"
                
        except Exception as e:
            print(f"Error getting host IP: {e}")
            return "127.0.0.1"
    
    def is_hotspot_supported(self) -> Tuple[bool, str]:
        """Check if hotspot is supported on this system"""
        if self.system == "Darwin":
            return True, "macOS Internet Sharing available"
        elif self.system == "Windows":
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
        
        return False, f"Unsupported system: {self.system}"
    
    def get_regular_network_ip(self) -> str:
        """Get the regular network IP (not hotspot)"""
        try:
            result = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=5)
            for line in result.stdout.split('\n'):
                if 'inet 192.168' in line and 'netmask' in line and '192.168.2.1' not in line:
                    return line.split()[1]
            
            # Fallback method
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                s.connect(("8.8.8.8", 80))
                ip = s.getsockname()[0]
                s.close()
                return ip
            except:
                return "127.0.0.1"
        except:
            return "127.0.0.1"
