"""
Configuration module for Ekatra AI v3.0 Offline Learning System
Enhanced configuration with security and production settings
"""
import os
from pathlib import Path
from typing import List, Optional
from dotenv import load_dotenv

# Load environment variables
load_dotenv()

class Config:
    """Enhanced configuration class with all system settings"""
    
    def __init__(self, config_path: Optional[str] = None):
        """Initialize configuration with optional config file path"""
        if config_path:
            load_dotenv(config_path)
    
    # =========================================================================
    # Server Configuration
    # =========================================================================
    
    SERVER_HOST = os.getenv("EKATRA_HOST", "0.0.0.0")
    SERVER_PORT = int(os.getenv("EKATRA_PORT", 5000))
    DEBUG_MODE = os.getenv("EKATRA_DEBUG", "false").lower() == "true"
    LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")
    
    # Security settings
    ALLOWED_ORIGINS = os.getenv("ALLOWED_ORIGINS", "*").split(",")
    MAX_REQUEST_SIZE = int(os.getenv("MAX_REQUEST_SIZE", 16777216))  # 16MB
    RATE_LIMIT_ENABLED = os.getenv("RATE_LIMIT_ENABLED", "false").lower() == "true"
    
    # =========================================================================
    # AI Model Configuration  
    # =========================================================================
    
    # Hugging Face Configuration
    HF_TOKEN = os.getenv("HF_TOKEN", "")
    
    # Model settings
    MODEL_NAME = os.getenv("MODEL_NAME", "meta-llama/Llama-3.2-1B-Instruct")
    USE_LOCAL_MODEL = os.getenv("USE_LOCAL_MODEL", "true").lower() == "true"
    MODEL_CACHE_DIR = os.getenv("MODEL_CACHE_DIR", "./models")
    
    # Generation parameters
    MAX_TOKENS = int(os.getenv("MAX_TOKENS", 750))
    TEMPERATURE = float(os.getenv("TEMPERATURE", 0.7))
    TOP_P = float(os.getenv("TOP_P", 0.95))
    TOP_K = int(os.getenv("TOP_K", 50))
    REPETITION_PENALTY = float(os.getenv("REPETITION_PENALTY", 1.1))
    
    # Fallback API Configuration (Tune AI)
    TUNE_API_URL = "https://proxy.tune.app/chat/completions"
    TUNE_API_KEY = os.getenv("TUNE_API_KEY", "")
    TUNE_ORG_ID = os.getenv("TUNE_ORG_ID", "")
    TUNE_MODEL_NAME = "meta/llama-3.2-90b-vision"
    
    # =========================================================================
    # Network & WiFi Configuration
    # =========================================================================
    
    # WiFi Hotspot settings
    WIFI_SSID = os.getenv("WIFI_SSID", "EkatraHub")
    WIFI_PASSWORD = os.getenv("WIFI_PASSWORD", "your_wifi_password_here")
    WIFI_CHANNEL = int(os.getenv("WIFI_CHANNEL", 6))
    
    # Network discovery settings
    NETWORK_SCAN_TIMEOUT = int(os.getenv("NETWORK_SCAN_TIMEOUT", 10))
    DEVICE_DISCOVERY_ENABLED = os.getenv("DEVICE_DISCOVERY_ENABLED", "true").lower() == "true"
    
    # Platform-specific hotspot IPs
    HOTSPOT_IP_WINDOWS = "192.168.137.1"
    HOTSPOT_IP_LINUX = "192.168.4.1"
    HOTSPOT_IP_MACOS = "192.168.2.1"
    
    # =========================================================================
    # Educational System Configuration
    # =========================================================================
    
    
    # Enhanced educational system prompt with comprehensive teaching framework
    SYSTEM_PROMPT = """You are Alfred, an expert AI learning assistant by Ekatra, designed to make learning engaging and effective for students of all levels.

## Your Teaching Philosophy:
- **Clarity First**: Break complex concepts into digestible parts
- **Active Learning**: Encourage questions and critical thinking
- **Practical Application**: Connect theory to real-world examples
- **Adaptive Teaching**: Adjust explanations based on student needs

## Response Structure:
1. **Quick Answer**: Start with a concise, direct response
2. **Detailed Explanation**: Provide comprehensive understanding with:
   - **Key concepts** highlighted in bold
   - Step-by-step breakdowns using numbered lists
   - Relevant examples and analogies
   - Visual descriptions when helpful
3. **Practice Element**: Suggest follow-up questions or exercises
4. **Connection**: Link to related topics when appropriate

## Communication Style:
- Use clear, friendly language appropriate for the topic
- Employ markdown formatting for better readability
- Include emojis sparingly for engagement (📚 🔬 💡)
- Break information into logical sections with proper spacing
- Encourage curiosity with thought-provoking questions

## Subject Areas:
Mathematics, Science, History, Literature, Geography, Technology, Arts, and interdisciplinary topics.

Your goal is to not just answer questions, but to inspire learning and help students develop critical thinking skills. Be encouraging, patient, and thorough in your explanations."""

    # =========================================================================
    # Application Settings
    # =========================================================================
    
    # Feature flags
    ENABLE_SYSTEM_MONITOR = os.getenv("ENABLE_SYSTEM_MONITOR", "true").lower() == "true"
    ENABLE_WIFI_MANAGER = os.getenv("ENABLE_WIFI_MANAGER", "true").lower() == "true"
    ENABLE_PWA_FEATURES = os.getenv("ENABLE_PWA_FEATURES", "true").lower() == "true"
    
    # Client management
    CLIENT_TIMEOUT = int(os.getenv("CLIENT_TIMEOUT", 300))  # 5 minutes
    MAX_CONCURRENT_CLIENTS = int(os.getenv("MAX_CONCURRENT_CLIENTS", 50))
    
    # Logging configuration
    LOG_DIR = Path(os.getenv("LOG_DIR", "logs"))
    LOG_MAX_SIZE = int(os.getenv("LOG_MAX_SIZE", 10485760))  # 10MB
    LOG_BACKUP_COUNT = int(os.getenv("LOG_BACKUP_COUNT", 5))
    
    # =========================================================================
    # Development & Testing Settings
    # =========================================================================
    
    # Development mode settings
    MOCK_COMPONENTS = os.getenv("MOCK_COMPONENTS", "false").lower() == "true"
    DISABLE_AUTH = os.getenv("DISABLE_AUTH", "false").lower() == "true"
    
    # Performance settings
    RESPONSE_CACHE_ENABLED = os.getenv("RESPONSE_CACHE_ENABLED", "false").lower() == "true"
    RESPONSE_CACHE_TTL = int(os.getenv("RESPONSE_CACHE_TTL", 3600))  # 1 hour
    
    @classmethod
    def validate(cls) -> List[str]:
        """Validate configuration and return list of issues"""
        issues = []
        
        # Check required settings
        if cls.USE_LOCAL_MODEL and not cls.HF_TOKEN:
            issues.append("HF_TOKEN required for local model usage")
        
        if not cls.USE_LOCAL_MODEL and not cls.TUNE_API_KEY:
            issues.append("TUNE_API_KEY required when not using local model")
        
        # Check valid ranges
        if not 1 <= cls.SERVER_PORT <= 65535:
            issues.append(f"Invalid SERVER_PORT: {cls.SERVER_PORT}")
        
        if not 0.0 <= cls.TEMPERATURE <= 2.0:
            issues.append(f"Invalid TEMPERATURE: {cls.TEMPERATURE}")
        
        # Check file system
        if not Path(cls.MODEL_CACHE_DIR).exists():
            try:
                Path(cls.MODEL_CACHE_DIR).mkdir(parents=True, exist_ok=True)
            except Exception as e:
                issues.append(f"Cannot create model cache directory: {e}")
        
        if not cls.LOG_DIR.exists():
            try:
                cls.LOG_DIR.mkdir(parents=True, exist_ok=True)
            except Exception as e:
                issues.append(f"Cannot create log directory: {e}")
        
        return issues
    
    @classmethod
    def get_summary(cls) -> dict:
        """Get configuration summary for logging"""
        return {
            'server': f"{cls.SERVER_HOST}:{cls.SERVER_PORT}",
            'debug_mode': cls.DEBUG_MODE,
            'use_local_model': cls.USE_LOCAL_MODEL,
            'model_name': cls.MODEL_NAME,
            'wifi_enabled': cls.ENABLE_WIFI_MANAGER,
            'system_monitor': cls.ENABLE_SYSTEM_MONITOR,
            'log_level': cls.LOG_LEVEL
        }
