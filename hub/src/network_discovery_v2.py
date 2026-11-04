"""
Advanced Network Discovery Module for Ekatra AI
Supports comprehensive device discovery across all local subnets
"""

import asyncio
import concurrent.futures
import ipaddress
import json
import logging
import os
import platform
import re
import socket
import struct
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from typing import Dict, List, Optional, Set, Tuple, Any
import psutil
import netifaces

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Environment configuration
SCAN_TIMEOUT_MS = int(os.getenv('SCAN_TIMEOUT_MS', '3000'))  # 3s per /24
SCAN_METHODS = os.getenv('SCAN_METHODS', 'arp,icmp,mdns,ssdp').split(',')
DEEP_SCAN = os.getenv('DEEP_SCAN', 'false').lower() == 'true'
MAX_WORKERS = int(os.getenv('MAX_DISCOVERY_WORKERS', '12'))

@dataclass
class NetworkDevice:
    """Represents a discovered network device"""
    ip: str
    mac: Optional[str] = None
    hostname: Optional[str] = None
    methods: List[str] = None
    interface: Optional[str] = None
    first_seen: Optional[str] = None
    
    def __post_init__(self):
        if self.methods is None:
            self.methods = []
        if self.first_seen is None:
            self.first_seen = datetime.now(timezone.utc).isoformat()

@dataclass
class SubnetInfo:
    """Represents a network subnet with discovered devices"""
    interface: str
    cidr: str
    devices: List[NetworkDevice]
    scan_duration_ms: float = 0.0
    scan_methods: List[str] = None
    errors: List[str] = None
    
    def __post_init__(self):
        if self.scan_methods is None:
            self.scan_methods = []
        if self.errors is None:
            self.errors = []

class NetworkDiscovery:
    """Advanced network discovery with multi-method scanning"""
    
    def __init__(self, interface_filter: str = 'all'):
        """
        Initialize network discovery
        
        Args:
            interface_filter: 'all', 'wifi', 'hotspot', 'eth'
        """
        self.interface_filter = interface_filter
        self.discovered_devices: Dict[str, NetworkDevice] = {}  # Key: ip_mac
        self.system = platform.system()
        
    def get_active_interfaces(self) -> List[Tuple[str, str, str]]:
        """
        Get all active network interfaces with IPv4 addresses
        
        Returns:
            List of (interface_name, ip_address, cidr) tuples
        """
        interfaces = []
        
        try:
            # Use psutil for cross-platform interface detection
            net_if_addrs = psutil.net_if_addrs()
            net_if_stats = psutil.net_if_stats()
            
            for interface_name, addrs in net_if_addrs.items():
                # Skip if interface is down
                if interface_name in net_if_stats and not net_if_stats[interface_name].isup:
                    continue
                    
                # Skip loopback
                if interface_name.startswith('lo') or 'loopback' in interface_name.lower():
                    continue
                
                # Find IPv4 addresses
                for addr in addrs:
                    if addr.family == socket.AF_INET and addr.address:
                        ip = addr.address
                        netmask = addr.netmask
                        
                        # Skip loopback IPs
                        if ip.startswith('127.'):
                            continue
                            
                        # Apply interface filter
                        if not self._should_scan_interface(interface_name, ip):
                            continue
                        
                        # Calculate CIDR
                        try:
                            network = ipaddress.IPv4Network(f"{ip}/{netmask}", strict=False)
                            cidr = str(network.network_address) + '/' + str(network.prefixlen)
                            interfaces.append((interface_name, ip, cidr))
                        except Exception as e:
                            logger.warning(f"Failed to calculate CIDR for {interface_name} {ip}: {e}")
                            
        except Exception as e:
            logger.error(f"Failed to enumerate interfaces: {e}")
            # Fallback to netifaces
            try:
                for interface in netifaces.interfaces():
                    addrs = netifaces.ifaddresses(interface)
                    if netifaces.AF_INET in addrs:
                        for addr_info in addrs[netifaces.AF_INET]:
                            ip = addr_info.get('addr')
                            netmask = addr_info.get('netmask')
                            if ip and netmask and not ip.startswith('127.'):
                                if self._should_scan_interface(interface, ip):
                                    try:
                                        network = ipaddress.IPv4Network(f"{ip}/{netmask}", strict=False)
                                        cidr = str(network.network_address) + '/' + str(network.prefixlen)
                                        interfaces.append((interface, ip, cidr))
                                    except:
                                        pass
            except Exception as e2:
                logger.error(f"Fallback interface enumeration failed: {e2}")
        
        logger.info(f"Found {len(interfaces)} active interfaces: {[iface[0] for iface in interfaces]}")
        return interfaces
    
    def _should_scan_interface(self, interface_name: str, ip: str) -> bool:
        """Check if interface should be scanned based on filter"""
        if self.interface_filter == 'all':
            return True
        elif self.interface_filter == 'wifi':
            return 'wi-fi' in interface_name.lower() or 'wlan' in interface_name.lower() or 'en0' in interface_name
        elif self.interface_filter == 'hotspot':
            return ip.startswith('192.168.2.') or ip.startswith('192.168.137.') or 'bridge' in interface_name
        elif self.interface_filter == 'eth':
            return 'ethernet' in interface_name.lower() or 'eth' in interface_name.lower() or 'en1' in interface_name
        return True
    
    def discover_all_subnets(self, filter_self_network_only: bool = False, deep_scan: bool = False, timeout: int = 10) -> Dict[str, Any]:
        """
        Discover devices across all active subnets
        
        Args:
            filter_self_network_only: If True, only scan subnets that contain this host
            deep_scan: If True, perform more thorough scanning (currently same as regular scan)
            timeout: Timeout for scanning operations in seconds
        
        Returns:
            Discovery results with subnets and devices
        """
        start_time = time.time()
        interfaces = self.get_active_interfaces()
        
        if not interfaces:
            logger.warning("No active interfaces found")
            return {
                "subnets": [],
                "scanned_at": datetime.now(timezone.utc).isoformat(),
                "scan_duration_ms": 0,
                "total_devices": 0
            }
        
        # Filter interfaces if only self-network requested
        if filter_self_network_only:
            interfaces = self._filter_self_networks(interfaces)
        
        # Discover devices on each subnet
        subnets = []
        total_devices = 0
        
        # Use thread pool for concurrent subnet scanning
        with ThreadPoolExecutor(max_workers=min(MAX_WORKERS, len(interfaces))) as executor:
            future_to_interface = {
                executor.submit(self._scan_subnet, iface, ip, cidr): (iface, ip, cidr)
                for iface, ip, cidr in interfaces
            }
            
            for future in as_completed(future_to_interface, timeout=15):  # Hard cap 15s
                iface, ip, cidr = future_to_interface[future]
                try:
                    subnet_info = future.result(timeout=1)
                    if subnet_info:
                        # Filter out the host machine itself if requested
                        if filter_self_network_only:
                            subnet_info.devices = self._filter_host_devices(subnet_info.devices, ip)
                        
                        subnets.append(subnet_info)
                        total_devices += len(subnet_info.devices)
                        logger.info(f"Subnet {cidr} on {iface}: {len(subnet_info.devices)} devices")
                except Exception as e:
                    logger.error(f"Failed to scan subnet {cidr} on {iface}: {e}")
                    # Add empty subnet with error
                    error_subnet = SubnetInfo(
                        interface=iface,
                        cidr=cidr,
                        devices=[],
                        errors=[str(e)]
                    )
                    subnets.append(error_subnet)
        
        total_duration = (time.time() - start_time) * 1000
        
        result = {
            "subnets": [self._subnet_to_dict(subnet) for subnet in subnets],
            "scanned_at": datetime.now(timezone.utc).isoformat(),
            "scan_duration_ms": total_duration,
            "total_devices": total_devices,
            "scan_methods": SCAN_METHODS,
            "interface_filter": self.interface_filter,
            "self_network_only": filter_self_network_only
        }
        
        logger.info(f"Discovery completed in {total_duration:.1f}ms, found {total_devices} devices across {len(subnets)} subnets")
        return result
    
    def _filter_self_networks(self, interfaces: List[Tuple[str, str, str]]) -> List[Tuple[str, str, str]]:
        """Filter interfaces to only include networks where this host can create hotspots or act as server"""
        filtered = []
        
        for iface, ip, cidr in interfaces:
            # Include WiFi networks where we're connected
            if 'wi-fi' in iface.lower() or 'wlan' in iface.lower() or 'en0' in iface:
                filtered.append((iface, ip, cidr))
            # Include hotspot networks (common ranges)
            elif ip.startswith('192.168.2.') or ip.startswith('192.168.137.') or ip.startswith('192.168.4.'):
                filtered.append((iface, ip, cidr))
            # Include bridge interfaces (often used for hotspots)
            elif 'bridge' in iface.lower() or 'ap0' in iface.lower():
                filtered.append((iface, ip, cidr))
        
        return filtered if filtered else interfaces  # Fallback to all if none match
    
    def _filter_host_devices(self, devices: List[NetworkDevice], host_ip: str) -> List[NetworkDevice]:
        """Filter out the host machine itself from device list"""
        return [device for device in devices if device.ip != host_ip]
    
    def _scan_subnet(self, interface: str, host_ip: str, cidr: str) -> SubnetInfo:
        """Scan a specific subnet for devices"""
        start_time = time.time()
        subnet_info = SubnetInfo(interface=interface, cidr=cidr, devices=[])
        
        try:
            network = ipaddress.IPv4Network(cidr, strict=False)
            
            # Skip very large networks to avoid timeouts
            if network.prefixlen < 20:  # Larger than /20 (4096 hosts)
                logger.warning(f"Skipping large network {cidr} (>{4096} hosts)")
                subnet_info.errors.append(f"Network too large ({network.num_addresses} hosts)")
                return subnet_info
            
            discovered_ips: Set[str] = set()
            
            # Method 1: ARP sweep (fastest and most reliable)
            if 'arp' in SCAN_METHODS:
                try:
                    arp_devices = self._arp_sweep(network, interface)
                    for device in arp_devices:
                        key = f"{device.ip}_{device.mac or 'unknown'}"
                        if key not in self.discovered_devices:
                            device.interface = interface
                            device.methods.append('ARP')
                            self.discovered_devices[key] = device
                            subnet_info.devices.append(device)
                            discovered_ips.add(device.ip)
                    subnet_info.scan_methods.append('ARP')
                    logger.debug(f"ARP sweep on {cidr}: {len(arp_devices)} devices")
                except Exception as e:
                    logger.warning(f"ARP sweep failed on {cidr}: {e}")
                    subnet_info.errors.append(f"ARP: {str(e)}")
            
            # Method 2: ICMP ping sweep (fallback)
            if 'icmp' in SCAN_METHODS:
                try:
                    icmp_ips = self._icmp_sweep(network, max_hosts=50 if not DEEP_SCAN else 254)
                    for ip in icmp_ips:
                        if ip not in discovered_ips:
                            device = NetworkDevice(ip=ip, methods=['ICMP'], interface=interface)
                            # Try to get MAC from ARP table
                            mac = self._get_mac_for_ip(ip)
                            if mac:
                                device.mac = mac
                            key = f"{ip}_{mac or 'unknown'}"
                            self.discovered_devices[key] = device
                            subnet_info.devices.append(device)
                            discovered_ips.add(ip)
                    subnet_info.scan_methods.append('ICMP')
                    logger.debug(f"ICMP sweep on {cidr}: {len(icmp_ips)} new devices")
                except Exception as e:
                    logger.warning(f"ICMP sweep failed on {cidr}: {e}")
                    subnet_info.errors.append(f"ICMP: {str(e)}")
            
            # Method 3: mDNS discovery (for silent hosts)
            if 'mdns' in SCAN_METHODS and len(discovered_ips) < 10:  # Only if few devices found
                try:
                    mdns_devices = self._mdns_discovery(interface, timeout=2)
                    for device in mdns_devices:
                        if device.ip not in discovered_ips:
                            device.interface = interface
                            key = f"{device.ip}_{device.mac or 'unknown'}"
                            if key in self.discovered_devices:
                                # Merge methods
                                self.discovered_devices[key].methods.extend(device.methods)
                            else:
                                self.discovered_devices[key] = device
                                subnet_info.devices.append(device)
                    subnet_info.scan_methods.append('mDNS')
                    logger.debug(f"mDNS discovery on {interface}: {len(mdns_devices)} devices")
                except Exception as e:
                    logger.debug(f"mDNS discovery failed on {interface}: {e}")
            
            # Resolve hostnames for all devices
            self._resolve_hostnames(subnet_info.devices)
            
        except Exception as e:
            logger.error(f"Subnet scan failed for {cidr}: {e}")
            subnet_info.errors.append(str(e))
        
        subnet_info.scan_duration_ms = (time.time() - start_time) * 1000
        return subnet_info
    
    def _arp_sweep(self, network: ipaddress.IPv4Network, interface: str) -> List[NetworkDevice]:
        """Perform ARP sweep on network"""
        devices = []
        
        try:
            if self.system == "Darwin":  # macOS
                result = subprocess.run(["arp", "-a"], capture_output=True, text=True, timeout=2)
                arp_output = result.stdout
            elif self.system == "Linux":
                result = subprocess.run(["arp", "-a"], capture_output=True, text=True, timeout=2)
                arp_output = result.stdout
            elif self.system == "Windows":
                result = subprocess.run(["arp", "-a"], capture_output=True, text=True, timeout=2)
                arp_output = result.stdout
            else:
                return devices
            
            # Parse ARP output
            for line in arp_output.split('\n'):
                line = line.strip()
                if not line:
                    continue
                
                # Extract IP and MAC
                ip_match = re.search(r'(\d+\.\d+\.\d+\.\d+)', line)
                mac_match = re.search(r'([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}', line)
                
                if ip_match:
                    ip = ip_match.group(1)
                    # Check if IP is in our target network
                    try:
                        if ipaddress.ip_address(ip) in network:
                            mac = mac_match.group(0) if mac_match else None
                            device = NetworkDevice(ip=ip, mac=mac, methods=['ARP'])
                            devices.append(device)
                    except:
                        continue
                        
        except subprocess.TimeoutExpired:
            logger.warning("ARP command timed out")
        except Exception as e:
            logger.warning(f"ARP sweep error: {e}")
        
        return devices
    
    def _icmp_sweep(self, network: ipaddress.IPv4Network, max_hosts: int = 50) -> List[str]:
        """Perform ICMP ping sweep"""
        alive_ips = []
        
        # Limit hosts to scan for performance
        hosts_to_scan = list(network.hosts())[:max_hosts] if not DEEP_SCAN else list(network.hosts())
        
        if self.system == "Windows":
            ping_cmd = ["ping", "-n", "1", "-w", "500"]
        else:
            ping_cmd = ["ping", "-c", "1", "-W", "0.5"]
        
        def ping_host(host_ip):
            try:
                cmd = ping_cmd + [str(host_ip)]
                result = subprocess.run(cmd, capture_output=True, timeout=1)
                if result.returncode == 0:
                    return str(host_ip)
            except:
                pass
            return None
        
        # Use thread pool for concurrent pings
        with ThreadPoolExecutor(max_workers=min(20, len(hosts_to_scan))) as executor:
            future_to_ip = {executor.submit(ping_host, host): host for host in hosts_to_scan}
            
            for future in as_completed(future_to_ip, timeout=SCAN_TIMEOUT_MS/1000):
                result = future.result()
                if result:
                    alive_ips.append(result)
        
        return alive_ips
    
    def _mdns_discovery(self, interface: str, timeout: int = 2) -> List[NetworkDevice]:
        """Discover devices using mDNS"""
        devices = []
        
        try:
            # Simple mDNS query - this is a basic implementation
            # In production, consider using python-zeroconf library
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.settimeout(timeout)
            
            # mDNS multicast address
            mcast_addr = ('224.0.0.251', 5353)
            
            # Simple mDNS query for _services._dns-sd._udp.local
            query = b'\x00\x00\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x09_services\x07_dns-sd\x04_udp\x05local\x00\x00\x0c\x00\x01'
            
            sock.sendto(query, mcast_addr)
            
            start_time = time.time()
            while time.time() - start_time < timeout:
                try:
                    data, addr = sock.recvfrom(1024)
                    ip = addr[0]
                    device = NetworkDevice(ip=ip, methods=['mDNS'])
                    devices.append(device)
                except socket.timeout:
                    break
                except:
                    continue
            
            sock.close()
            
        except Exception as e:
            logger.debug(f"mDNS discovery failed: {e}")
        
        return devices
    
    def _get_mac_for_ip(self, ip: str) -> Optional[str]:
        """Get MAC address for IP from ARP table"""
        try:
            result = subprocess.run(["arp", "-n", ip], capture_output=True, text=True, timeout=1)
            if result.returncode == 0:
                mac_match = re.search(r'([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}', result.stdout)
                if mac_match:
                    return mac_match.group(0)
        except:
            pass
        return None
    
    def _resolve_hostnames(self, devices: List[NetworkDevice]):
        """Resolve hostnames for devices"""
        def resolve_hostname(device):
            try:
                hostname = socket.gethostbyaddr(device.ip)[0]
                device.hostname = hostname
            except:
                # Generate a friendly name
                device.hostname = f"Device-{device.ip.split('.')[-1]}"
        
        # Resolve hostnames concurrently
        with ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(resolve_hostname, device) for device in devices]
            for future in as_completed(futures, timeout=2):
                try:
                    future.result()
                except:
                    pass
    
    def _subnet_to_dict(self, subnet: SubnetInfo) -> Dict[str, Any]:
        """Convert SubnetInfo to dictionary"""
        return {
            "interface": subnet.interface,
            "cidr": subnet.cidr,
            "devices": [asdict(device) for device in subnet.devices],
            "scan_duration_ms": subnet.scan_duration_ms,
            "scan_methods": subnet.scan_methods,
            "errors": subnet.errors
        }

# CLI for testing
if __name__ == "__main__":
    import argparse
    
    parser = argparse.ArgumentParser(description="Network Discovery Tool")
    parser.add_argument("--ifaces", choices=['all', 'wifi', 'hotspot', 'eth'], 
                       default='all', help="Interface filter")
    parser.add_argument("--deep-scan", action='store_true', help="Enable deep scan")
    parser.add_argument("--methods", default="arp,icmp,mdns", help="Scan methods")
    parser.add_argument("--timeout", type=int, default=5000, help="Timeout in ms")
    
    args = parser.parse_args()
    
    # Set environment
    os.environ['DEEP_SCAN'] = str(args.deep_scan).lower()
    os.environ['SCAN_METHODS'] = args.methods
    os.environ['SCAN_TIMEOUT_MS'] = str(args.timeout)
    
    # Run discovery
    discovery = NetworkDiscovery(interface_filter=args.ifaces)
    results = discovery.discover_all_subnets()
    
    print(json.dumps(results, indent=2))
