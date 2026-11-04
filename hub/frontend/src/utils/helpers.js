export const formatTimestamp = (timestamp) => {
  if (!timestamp) return 'Unknown';
  
  try {
    const date = new Date(timestamp);
    return date.toLocaleTimeString('en-US', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: true,
    });
  } catch (error) {
    return 'Invalid time';
  }
};

export const formatRelativeTime = (timestamp) => {
  if (!timestamp) return 'Unknown';
  
  try {
    const now = new Date();
    const date = new Date(timestamp);
    const diffMs = now - date;
    const diffSecs = Math.floor(diffMs / 1000);
    const diffMins = Math.floor(diffSecs / 60);
    const diffHours = Math.floor(diffMins / 60);
    const diffDays = Math.floor(diffHours / 24);
    
    if (diffSecs < 60) return 'Just now';
    if (diffMins < 60) return `${diffMins}m ago`;
    if (diffHours < 24) return `${diffHours}h ago`;
    if (diffDays < 7) return `${diffDays}d ago`;
    
    return date.toLocaleDateString();
  } catch (error) {
    return 'Invalid time';
  }
};

export const formatBytes = (bytes, decimals = 2) => {
  if (!bytes || bytes === 0) return '0 Bytes';
  
  const k = 1024;
  const dm = decimals < 0 ? 0 : decimals;
  const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
  
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  
  return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
};

export const formatPercentage = (value, total) => {
  if (!total || total === 0) return '0%';
  return Math.round((value / total) * 100) + '%';
};

export const truncateText = (text, maxLength = 50) => {
  if (!text || text.length <= maxLength) return text;
  return text.substring(0, maxLength) + '...';
};

export const isValidIP = (ip) => {
  const ipPattern = /^(\d{1,3}\.){3}\d{1,3}$/;
  if (!ipPattern.test(ip)) return false;
  
  return ip.split('.').every(octet => {
    const num = parseInt(octet, 10);
    return num >= 0 && num <= 255;
  });
};

export const isValidMAC = (mac) => {
  const macPattern = /^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$/;
  return macPattern.test(mac);
};

export const getDeviceTypeDisplayName = (deviceType) => {
  const typeMap = {
    computer: 'Computer',
    laptop: 'Laptop',
    smartphone: 'Smartphone',
    mobile: 'Mobile Device',
    tablet: 'Tablet',
    router: 'Router',
    gateway: 'Gateway',
    printer: 'Printer',
    tv: 'Smart TV',
    'smart tv': 'Smart TV',
    iot: 'IoT Device',
    unknown: 'Unknown Device',
  };
  
  return typeMap[deviceType?.toLowerCase()] || 'Unknown Device';
};

export const getStatusColor = (status) => {
  const statusColors = {
    active: 'success',
    inactive: 'error',
    connected: 'success',
    disconnected: 'error',
    online: 'success',
    offline: 'error',
    unknown: 'default',
  };
  
  return statusColors[status?.toLowerCase()] || 'default';
};

export const debounce = (func, wait) => {
  let timeout;
  return function executedFunction(...args) {
    const later = () => {
      clearTimeout(timeout);
      func(...args);
    };
    clearTimeout(timeout);
    timeout = setTimeout(later, wait);
  };
};
