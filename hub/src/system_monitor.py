"""
System Monitoring Module
Handles system resource monitoring and health checks
"""
import psutil
import time
import platform
from typing import Dict, Any

class SystemMonitor:
    """System resource monitor"""
    
    @staticmethod
    def get_cpu_usage() -> float:
        """Get current CPU usage percentage"""
        return psutil.cpu_percent(interval=1)
    
    @staticmethod
    def get_memory_usage() -> Dict[str, Any]:
        """Get memory usage information"""
        memory = psutil.virtual_memory()
        return {
            "total": memory.total,
            "available": memory.available,
            "used": memory.used,
            "percentage": memory.percent,
            "total_gb": round(memory.total / (1024**3), 2),
            "used_gb": round(memory.used / (1024**3), 2),
            "available_gb": round(memory.available / (1024**3), 2)
        }
    
    @staticmethod
    def get_disk_usage() -> Dict[str, Any]:
        """Get disk usage information"""
        disk = psutil.disk_usage('/')
        return {
            "total": disk.total,
            "used": disk.used,
            "free": disk.free,
            "percentage": round((disk.used / disk.total) * 100, 1),
            "total_gb": round(disk.total / (1024**3), 2),
            "used_gb": round(disk.used / (1024**3), 2),
            "free_gb": round(disk.free / (1024**3), 2)
        }
    
    @staticmethod
    def get_network_stats() -> Dict[str, Any]:
        """Get network interface statistics"""
        try:
            net_io = psutil.net_io_counters()
            return {
                "bytes_sent": net_io.bytes_sent,
                "bytes_recv": net_io.bytes_recv,
                "packets_sent": net_io.packets_sent,
                "packets_recv": net_io.packets_recv,
                "bytes_sent_mb": round(net_io.bytes_sent / (1024**2), 2),
                "bytes_recv_mb": round(net_io.bytes_recv / (1024**2), 2)
            }
        except Exception:
            return {
                "bytes_sent": 0,
                "bytes_recv": 0,
                "packets_sent": 0,
                "packets_recv": 0,
                "bytes_sent_mb": 0,
                "bytes_recv_mb": 0
            }
    
    @staticmethod
    def get_system_info() -> Dict[str, Any]:
        """Get comprehensive system information"""
        return {
            "cpu_usage": SystemMonitor.get_cpu_usage(),
            "memory": SystemMonitor.get_memory_usage(),
            "disk": SystemMonitor.get_disk_usage(),
            "network": SystemMonitor.get_network_stats(),
            "uptime": time.time() - psutil.boot_time(),
            "uptime_hours": round((time.time() - psutil.boot_time()) / 3600, 1),
            "platform": platform.platform(),
            "python_version": platform.python_version(),
            "cpu_count": psutil.cpu_count(),
            "cpu_freq": psutil.cpu_freq().current if psutil.cpu_freq() else 0
        }
    
    @staticmethod
    def get_system_stats() -> Dict[str, Any]:
        """Alias for get_system_info for compatibility"""
        return SystemMonitor.get_system_info()
    
    @staticmethod
    def get_health_status() -> Dict[str, Any]:
        """Get system health status"""
        cpu_usage = SystemMonitor.get_cpu_usage()
        memory = SystemMonitor.get_memory_usage()
        disk = SystemMonitor.get_disk_usage()
        
        # Determine health status
        health = "healthy"
        warnings = []
        
        if cpu_usage > 85:
            health = "warning"
            warnings.append(f"High CPU usage: {cpu_usage:.1f}%")
        
        if memory["percentage"] > 85:
            health = "warning"
            warnings.append(f"High memory usage: {memory['percentage']:.1f}%")
        
        if disk["percentage"] > 85:
            health = "warning"
            warnings.append(f"High disk usage: {disk['percentage']:.1f}%")
        
        if cpu_usage > 95 or memory["percentage"] > 95:
            health = "critical"
        
        return {
            "status": health,
            "cpu_usage": cpu_usage,
            "memory_usage": memory["percentage"],
            "disk_usage": disk["percentage"],
            "warnings": warnings,
            "timestamp": time.time()
        }
