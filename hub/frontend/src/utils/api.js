import axios from 'axios';

// Configure axios defaults
const api = axios.create({
  baseURL: process.env.REACT_APP_API_BASE_URL || '',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor for logging
api.interceptors.request.use(
  (config) => {
    console.log(`API Request: ${config.method?.toUpperCase()} ${config.url}`);
    return config;
  },
  (error) => {
    console.error('API Request Error:', error);
    return Promise.reject(error);
  }
);

// Response interceptor for error handling
api.interceptors.response.use(
  (response) => {
    return response;
  },
  (error) => {
    console.error('API Response Error:', error);
    
    // Handle common error scenarios
    if (error.response) {
      // Server responded with error status
      const { status, data } = error.response;
      
      switch (status) {
        case 404:
          error.message = 'Service not found. Please check if the server is running.';
          break;
        case 500:
          error.message = data?.error || 'Internal server error occurred.';
          break;
        case 503:
          error.message = 'Service temporarily unavailable. Please try again later.';
          break;
        default:
          error.message = data?.error || `Server error: ${status}`;
      }
    } else if (error.request) {
      // Network error
      error.message = 'Unable to connect to server. Please check your connection.';
    }
    
    return Promise.reject(error);
  }
);

// API functions
export const chatAPI = {
  sendMessage: async (message) => {
    const response = await api.post('/api/generate', { message });
    return response.data.success ? response.data.data : response.data;
  },
};

export const devicesAPI = {
  getDevices: async (ekatraOnly = true) => {
    const response = await api.get(`/api/devices?ekatra_only=${ekatraOnly}`);
    return response.data.success ? response.data.data : response.data;
  },
};

export const clientsAPI = {
  getActiveClients: async () => {
    const response = await api.get('/api/clients/active');
    return response.data.success ? response.data.data : response.data;
  },
};

export const systemAPI = {
  getSystemInfo: async () => {
    const response = await api.get('/api/system/info');
    return response.data.success ? response.data.data : response.data;
  },
};

export default api;
