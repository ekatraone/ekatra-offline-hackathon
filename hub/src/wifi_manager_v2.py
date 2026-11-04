"""
Enhanced WiFi Manager for Ekatra AI v2.1
Integrates with advanced network discovery system
"""

import json
import logging
import os
import platform
import re
import subprocess
import time
from datetime import datetime, timezone
from typing import Dict, List, Optional, Any
from dataclasses import dataclass, asdict

from .network_discovery_v2 import NetworkDiscovery, NetworkDevice

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

@dataclass
class WiFiNetwork:
    """Represents a WiFi network"""
    ssid: str
    bssid: Optional[str] = None
    signal_strength: Optional[int] = None
    security: Optional[str] = None
    frequency: Optional[str] = None
    connected: bool = False

@dataclass
class WiFiClient:
    """Represents a client connected to WiFi hotspot"""
    ip: str
    mac: str
    hostname: Optional[str] = None
    connected_at: Optional[str] = None
    data_usage_mb: float = 0.0
    device_type: Optional[str] = None
    
    def __post_init__(self):
        if self.connected_at is None:
            self.connected_at = datetime.now(timezone.utc).isoformat()

class WiFiManagerV2:
    """Enhanced WiFi management with comprehensive device discovery"""
    
    def __init__(self):
        self.system = platform.system()
        self.discovery = NetworkDiscovery()
        self.wifi_interface = self._detect_wifi_interface()
        self.hotspot_enabled = False
        self._connected_clients_cache: Dict[str, WiFiClient] = {}
        
    def _detect_wifi_interface(self) -> Optional[str]:
        """Detect the primary WiFi interface"""
        try:
            if self.system == "Darwin":  # macOS
                result = subprocess.run([
                    "networksetup", "-listallhardwareports"
                ], capture_output=True, text=True, timeout=5)
                
                lines = result.stdout.split('\n')
                for i, line in enumerate(lines):
                    if "Wi-Fi" in line and i + 1 < len(lines):
                        device_line = lines[i + 1]
                        if "Device:" in device_line:
                            return device_line.split("Device:")[1].strip()
                
            elif self.system == "Linux":
                # Try iwconfig first
                try:
                    result = subprocess.run([
                        "iwconfig"
                    ], capture_output=True, text=True, timeout=3)
                    
                    for line in result.stdout.split('\n'):
                        if 'IEEE 802.11' in line:
                            return line.split()[0]
                except:
                    pass
                
                # Fallback to /proc/net/wireless
                try:
                    with open('/proc/net/wireless', 'r') as f:
                        lines = f.readlines()
                        if len(lines) > 2:  # Skip header lines
                            return lines[2].split(':')[0].strip()
                except:
                    pass
                    
            elif self.system == "Windows":
                result = subprocess.run([
                    "netsh", "wlan", "show", "interfaces"
                ], capture_output=True, text=True, timeout=5)
                
                for line in result.stdout.split('\n'):
                    if "Name" in line and ":" in line:
                        return line.split(':', 1)[1].strip()
                        
        except Exception as e:
            logger.warning(f"Failed to detect WiFi interface: {e}")
        
        # Common fallbacks
        fallbacks = ['wlan0', 'wlp2s0', 'en0', 'wlan1']
        for iface in fallbacks:
            if self._interface_exists(iface):
                return iface
        
        return None
    
    def _interface_exists(self, interface: str) -> bool:
        """Check if network interface exists"""
        try:
            import psutil
            return interface in psutil.net_if_addrs()
        except:
            return False
    
    def get_wifi_status(self) -> Dict[str, Any]:
        """Get comprehensive WiFi status and connected devices"""
        status = {
            "wifi_interface": self.wifi_interface,
            "connected_network": None,
            "hotspot_enabled": self.hotspot_enabled,
            "connected_clients": [],
            "available_networks": [],
            "scan_timestamp": datetime.now(timezone.utc).isoformat()
        }
        
        # Get current connection status
        try:
            current_network = self._get_current_wifi_network()
            if current_network:
                status["connected_network"] = asdict(current_network)
        except Exception as e:
            logger.warning(f"Failed to get current WiFi network: {e}")
        
        # Discover devices on WiFi networks
        try:
            wifi_discovery = NetworkDiscovery(interface_filter='wifi')
            wifi_results = wifi_discovery.discover_all_subnets()
            
            # Convert discovered devices to WiFi clients
            clients = []
            for subnet in wifi_results.get("subnets", []):
                for device_data in subnet.get("devices", []):
                    device = NetworkDevice(**device_data)
                    client = WiFiClient(
                        ip=device.ip,
                        mac=device.mac or "unknown",
                        hostname=device.hostname,
                        device_type=self._identify_device_type(device)
                    )
                    clients.append(asdict(client))
            
            status["connected_clients"] = clients
            status["discovery_results"] = wifi_results
            
        except Exception as e:
            logger.error(f"Failed to discover WiFi devices: {e}")
            status["error"] = str(e)
        
        # Get available networks (scan)
        try:
            networks = self._scan_wifi_networks()
            status["available_networks"] = [asdict(net) for net in networks]
        except Exception as e:
            logger.warning(f"Failed to scan WiFi networks: {e}")
        
        return status
    
    def _get_current_wifi_network(self) -> Optional[WiFiNetwork]:
        """Get currently connected WiFi network"""
        try:
            if self.system == "Darwin":  # macOS
                result = subprocess.run([
                    "/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport",
                    "-I"
                ], capture_output=True, text=True, timeout=5)
                
                if result.returncode == 0:
                    lines = result.stdout.strip().split('\n')
                    network_info = {}
                    for line in lines:
                        if ':' in line:
                            key, value = line.strip().split(':', 1)
                            network_info[key.strip()] = value.strip()
                    
                    if 'SSID' in network_info:
                        return WiFiNetwork(
                            ssid=network_info.get('SSID', ''),
                            bssid=network_info.get('BSSID', ''),
                            signal_strength=int(network_info.get('agrCtlRSSI', 0)),
                            security=network_info.get('link auth', ''),
                            connected=True
                        )
                        
            elif self.system == "Linux":
                # Try iwconfig
                if self.wifi_interface:
                    result = subprocess.run([
                        "iwconfig", self.wifi_interface
                    ], capture_output=True, text=True, timeout=3)
                    
                    if "ESSID:" in result.stdout:
                        ssid_match = re.search(r'ESSID:"([^"]*)"', result.stdout)
                        if ssid_match:
                            ssid = ssid_match.group(1)
                            if ssid and ssid != "off/any":
                                return WiFiNetwork(ssid=ssid, connected=True)
                
            elif self.system == "Windows":
                result = subprocess.run([
                    "netsh", "wlan", "show", "interfaces"
                ], capture_output=True, text=True, timeout=5)
                
                lines = result.stdout.split('\n')
                for line in lines:
                    if "SSID" in line and ":" in line:
                        ssid = line.split(':', 1)[1].strip()
                        if ssid:
                            return WiFiNetwork(ssid=ssid, connected=True)
                            
        except Exception as e:
            logger.warning(f"Failed to get current WiFi network: {e}")
        
        return None
    
    def _scan_wifi_networks(self) -> List[WiFiNetwork]:
        """Scan for available WiFi networks"""
        networks = []
        
        try:
            if self.system == "Darwin":  # macOS
                result = subprocess.run([
                    "/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport",
                    "-s"
                ], capture_output=True, text=True, timeout=10)
                
                if result.returncode == 0:
                    lines = result.stdout.strip().split('\n')[1:]  # Skip header
                    for line in lines:
                        if line.strip():
                            parts = line.split()
                            if len(parts) >= 6:
                                ssid = parts[0]
                                bssid = parts[1]
                                signal = int(parts[2])
                                security = ' '.join(parts[6:]) if len(parts) > 6 else ''
                                
                                networks.append(WiFiNetwork(
                                    ssid=ssid,
                                    bssid=bssid,
                                    signal_strength=signal,
                                    security=security,
                                    connected=False
                                ))
                                
            elif self.system == "Linux":
                if self.wifi_interface:
                    # Try iwlist scan
                    result = subprocess.run([
                        "iwlist", self.wifi_interface, "scan"
                    ], capture_output=True, text=True, timeout=10)
                    
                    if result.returncode == 0:
                        current_network = {}
                        for line in result.stdout.split('\n'):
                            line = line.strip()
                            if 'Cell' in line and 'Address:' in line:
                                if current_network.get('ssid'):
                                    networks.append(WiFiNetwork(**current_network))
                                current_network = {'connected': False}
                                bssid_match = re.search(r'Address: ([0-9a-fA-F:]+)', line)
                                if bssid_match:
                                    current_network['bssid'] = bssid_match.group(1)
                            elif 'ESSID:' in line:
                                ssid_match = re.search(r'ESSID:"([^"]*)"', line)
                                if ssid_match:
                                    current_network['ssid'] = ssid_match.group(1)
                            elif 'Signal level=' in line:
                                signal_match = re.search(r'Signal level=(-?\d+)', line)
                                if signal_match:
                                    current_network['signal_strength'] = int(signal_match.group(1))
                        
                        if current_network.get('ssid'):
                            networks.append(WiFiNetwork(**current_network))
                            
            elif self.system == "Windows":
                result = subprocess.run([
                    "netsh", "wlan", "show", "profiles"
                ], capture_output=True, text=True, timeout=5)
                
                # This gets saved profiles, not a live scan
                for line in result.stdout.split('\n'):
                    if 'All User Profile' in line:
                        ssid = line.split(':')[1].strip()
                        networks.append(WiFiNetwork(ssid=ssid, connected=False))
                        
        except Exception as e:
            logger.warning(f"Failed to scan WiFi networks: {e}")
        
        return networks
    
    def _identify_device_type(self, device: NetworkDevice) -> str:
        """Identify device type based on MAC address and hostname"""
        if not device.mac:
            return "Unknown"
        
        mac_upper = device.mac.upper().replace(':', '').replace('-', '')
        
        # Common OUI (Organizationally Unique Identifier) mappings
        oui_map = {
            'APPLE': ['00:03:93', '00:05:02', '00:0A:95', '00:0D:93', '00:14:51', '00:16:CB', '00:17:F2', '00:1B:63', '00:1C:B3', '00:1E:C2', '00:1F:F3', '00:21:E9', '00:22:41', '00:23:12', '00:23:DF', '00:25:00', '00:25:4B', '00:25:BC', '00:26:08', '00:26:4A', '00:26:B0', '00:26:BB', 'A4:83:E7', 'B8:09:8A', 'B8:E8:56', 'BC:52:B7', 'BC:6C:21', 'BC:92:6B', 'C0:84:7A', 'D0:E1:40', 'DC:56:E7', 'E0:B9:BA', 'E4:8B:7F', 'E8:8D:28', 'F0:DB:E2', 'F4:37:B7', 'F8:1E:DF'],
            'SAMSUNG': ['00:07:AB', '00:09:18', '00:0D:E5', '00:12:FB', '00:13:77', '00:15:99', '00:16:32', '00:17:C9', '00:18:AF', '00:1A:8A', '00:1B:98', '00:1C:43', '00:1D:25', '00:1E:7D', '00:1F:CC', '00:21:19', '00:23:39', '00:26:37', '00:E3:B2', '08:08:C2', 'A0:0B:BA', 'B4:07:F9', 'C0:BD:D1', 'E8:50:8B'],
            'GOOGLE': ['AC:37:43', 'B4:F0:AB', 'DA:A1:19', 'F4:F5:DB', 'F8:8F:CA'],
            'AMAZON': ['44:65:0D', '50:DC:E7', '74:C2:46', '84:D6:D0', 'AC:63:BE', 'B0:7B:25', 'F0:27:2D', 'FC:A6:67'],
            'MICROSOFT': ['00:03:FF', '00:12:5A', '00:17:FA', '00:21:5C', '00:50:F2', '7C:1E:52', '98:5F:D3', 'A0:99:9B'],
            'INTEL': ['00:02:B3', '00:03:47', '00:04:23', '00:07:E9', '00:0E:0C', '00:13:02', '00:13:CE', '00:15:00', '00:16:EA', '00:19:D1', '00:1B:77', '00:1C:BF', '00:1E:64', '00:1F:3B', '00:21:5D', '00:22:FB', '00:24:D7', '00:26:C6', '00:27:10', '04:7D:7B', '10:0B:A9', '3C:97:0E', '68:05:CA', '6C:88:14', 'A4:34:D9', 'AC:2B:6E', 'D4:BE:D9', 'E0:94:67'],
            'RASPBERRY_PI': ['B8:27:EB', 'DC:A6:32', 'E4:5F:01'],
            'NINTENDO': ['00:09:BF', '00:16:56', '00:17:AB', '00:19:1D', '00:1A:E9', '00:1B:7A', '00:1C:BE', '00:1D:BC', '00:1E:35', '00:1F:32', '00:21:47', '00:22:AA', '00:22:D7', '00:23:31', '00:24:F3', '00:25:A0', '00:26:59', '34:AF:2C', '40:D2:8A', '40:F4:07', '48:A5:E7', '58:BD:A3', '78:A2:A0', '8C:56:C5', '98:B6:E9', 'A4:C0:E1', 'B8:AE:6E', 'CC:9E:00', 'D8:6B:F7'],
            'SONY': ['00:01:C9', '00:02:C7', '00:04:1F', '00:06:4F', '00:09:B7', '00:0A:D9', '00:0B:C1', '00:0D:F0', '00:0F:A3', '00:13:A9', '00:16:FE', '00:19:C5', '00:1B:FB', '00:1D:BA', '00:1E:3D', '00:23:06', '04:4E:5A', '0C:FC:83', '14:CF:92', '18:C0:96', '20:54:FA', '30:39:F2', '34:C8:03', '4C:B1:99', '50:56:BF', '54:84:CD', '5C:96:9D', '64:BC:0C', '6C:40:08', '70:1C:E7', '74:90:50', '80:C7:E9', '84:38:38', '8C:64:22', '8C:C8:F4', '90:84:0D', '9C:64:8B', 'A0:1B:29', 'A4:77:33', 'AC:9B:0A', 'B0:E2:35', 'B8:F9:34', 'BC:60:A7', 'C0:CB:38', 'C4:04:15', 'C8:63:F1', 'CC:F9:54', 'D0:E7:82', 'D4:32:43', 'DC:0B:1A', 'E0:76:D0', 'E4:40:E2', 'EC:A8:6B', 'F0:BF:97', 'F8:46:1C', 'FC:0F:E6']
        }
        
        # Check OUI
        for device_type, ouis in oui_map.items():
            for oui in ouis:
                if mac_upper.startswith(oui.replace(':', '')):
                    return device_type
        
        # Check hostname patterns
        if device.hostname:
            hostname_lower = device.hostname.lower()
            if any(x in hostname_lower for x in ['iphone', 'ipad', 'macbook', 'imac']):
                return 'APPLE'
            elif any(x in hostname_lower for x in ['android', 'samsung', 'galaxy']):
                return 'ANDROID'
            elif any(x in hostname_lower for x in ['windows', 'desktop', 'laptop', 'pc']):
                return 'WINDOWS'
            elif any(x in hostname_lower for x in ['raspberry', 'pi', 'rpi']):
                return 'RASPBERRY_PI'
            elif any(x in hostname_lower for x in ['echo', 'alexa']):
                return 'AMAZON'
            elif any(x in hostname_lower for x in ['chromecast', 'nest', 'pixel']):
                return 'GOOGLE'
        
        return "Unknown"
    
    def get_hotspot_clients(self) -> List[Dict[str, Any]]:
        """Get clients connected to hotspot (if enabled)"""
        clients = []
        
        try:
            # Use discovery with hotspot filter
            hotspot_discovery = NetworkDiscovery(interface_filter='hotspot')
            results = hotspot_discovery.discover_all_subnets()
            
            for subnet in results.get("subnets", []):
                for device_data in subnet.get("devices", []):
                    device = NetworkDevice(**device_data)
                    
                    # Skip the hotspot host itself
                    if device.ip.endswith('.1'):  # Common hotspot host IP
                        continue
                    
                    client = WiFiClient(
                        ip=device.ip,
                        mac=device.mac or "unknown",
                        hostname=device.hostname,
                        device_type=self._identify_device_type(device)
                    )
                    
                    # Add to cache
                    self._connected_clients_cache[device.ip] = client
                    clients.append(asdict(client))
                    
        except Exception as e:
            logger.error(f"Failed to get hotspot clients: {e}")
        
        return clients
    
    def enable_hotspot(self, ssid: str = "EkatraAI", password: str = None) -> Dict[str, Any]:
        """Enable WiFi hotspot"""
        try:
            if self.system == "Darwin":  # macOS
                # macOS Internet Sharing via command line is complex
                # This is a simplified approach
                result = subprocess.run([
                    "sudo", "networksetup", "-setairportpower", 
                    self.wifi_interface or "en0", "on"
                ], capture_output=True, text=True, timeout=10)
                
                # Note: Full hotspot setup on macOS requires System Preferences
                self.hotspot_enabled = True
                return {"success": True, "message": "Hotspot setup initiated"}
                
            elif self.system == "Linux":
                # Use hostapd or create_ap if available
                try:
                    # Simple approach using nmcli
                    cmd = [
                        "nmcli", "connection", "add", "type", "wifi", 
                        "ifname", self.wifi_interface or "wlan0",
                        "con-name", "EkatraHotspot", "autoconnect", "yes",
                        "ssid", ssid
                    ]
                    
                    if password:
                        cmd.extend(["wifi-sec.key-mgmt", "wpa-psk", "wifi-sec.psk", password])
                    
                    result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
                    
                    if result.returncode == 0:
                        # Activate the connection
                        subprocess.run([
                            "nmcli", "connection", "up", "EkatraHotspot"
                        ], capture_output=True, text=True, timeout=5)
                        
                        self.hotspot_enabled = True
                        return {"success": True, "message": "Hotspot enabled"}
                    
                except Exception as e:
                    logger.error(f"Failed to enable hotspot with nmcli: {e}")
                    
            elif self.system == "Windows":
                # Use netsh to create mobile hotspot
                cmd = [
                    "netsh", "wlan", "set", "hostednetwork", 
                    f"ssid={ssid}", f"key={password or 'password123'}", "keyUsage=persistent"
                ]
                
                result = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
                
                if result.returncode == 0:
                    # Start the hosted network
                    start_result = subprocess.run([
                        "netsh", "wlan", "start", "hostednetwork"
                    ], capture_output=True, text=True, timeout=5)
                    
                    if start_result.returncode == 0:
                        self.hotspot_enabled = True
                        return {"success": True, "message": "Hotspot enabled"}
            
            return {"success": False, "message": "Hotspot setup not supported on this system"}
            
        except Exception as e:
            logger.error(f"Failed to enable hotspot: {e}")
            return {"success": False, "message": str(e)}
    
    def disable_hotspot(self) -> Dict[str, Any]:
        """Disable WiFi hotspot"""
        try:
            if self.system == "Linux":
                # Disconnect nmcli hotspot
                subprocess.run([
                    "nmcli", "connection", "down", "EkatraHotspot"
                ], capture_output=True, text=True, timeout=5)
                
                subprocess.run([
                    "nmcli", "connection", "delete", "EkatraHotspot"
                ], capture_output=True, text=True, timeout=5)
                
            elif self.system == "Windows":
                subprocess.run([
                    "netsh", "wlan", "stop", "hostednetwork"
                ], capture_output=True, text=True, timeout=5)
            
            self.hotspot_enabled = False
            self._connected_clients_cache.clear()
            return {"success": True, "message": "Hotspot disabled"}
            
        except Exception as e:
            logger.error(f"Failed to disable hotspot: {e}")
            return {"success": False, "message": str(e)}

# CLI for testing
if __name__ == "__main__":
    wifi_manager = WiFiManagerV2()
    
    print("=== WiFi Status ===")
    status = wifi_manager.get_wifi_status()
    print(json.dumps(status, indent=2))
    
    print("\n=== Hotspot Clients ===")
    clients = wifi_manager.get_hotspot_clients()
    print(json.dumps(clients, indent=2))
