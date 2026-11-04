#!/usr/bin/env python3
"""
Ekatra AI v3.0 - Production Ready Server
Optimized React-based educational AI system with comprehensive features

Features:
- React + Material-UI frontend with admin/client separation
- Modern Flask API with standardized endpoints
- Advanced device discovery and network management
- Educational AI model with enhanced prompting
- Real-time system monitoring and client tracking
- PWA support with offline capabilities
- Security hardening and error handling
"""

import asyncio
import json
import logging
import os
import time
import signal
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Any, Tuple
from contextlib import contextmanager

# Third-party imports
import markdown
import bleach
from flask import Flask, request, jsonify, send_from_directory, g
from flask_cors import CORS
from werkzeug.middleware.proxy_fix import ProxyFix
from werkzeug.exceptions import NotFound, InternalServerError

# Local imports
from config.settings import Config
from src.model_manager import ModelManager
from src.system_monitor import SystemMonitor
from src.wifi_manager_v2 import WiFiManagerV2
from src.network_discovery_v2 import NetworkDiscovery

# Configure logging
def setup_logging(log_level: str = "INFO"):
    """Setup comprehensive logging configuration"""
    log_dir = Path("logs")
    log_dir.mkdir(exist_ok=True)
    
    logging.basicConfig(
        level=getattr(logging, log_level.upper()),
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
        handlers=[
            logging.FileHandler(log_dir / "ekatra_ai.log"),
            logging.StreamHandler()
        ]
    )
    
    # Suppress noisy libraries
    logging.getLogger("werkzeug").setLevel(logging.WARNING)
    logging.getLogger("transformers").setLevel(logging.WARNING)
    
    return logging.getLogger(__name__)

# Initialize logger
logger = setup_logging(os.getenv("LOG_LEVEL", "INFO"))

class EkatraAIServer:
    """
    Production-ready Ekatra AI Server
    Handles React frontend, API endpoints, and system management
    """
    
    def __init__(self, config_path: Optional[str] = None):
        """Initialize the server with configuration and components"""
        self.config = Config(config_path) if config_path else Config()
        self.app = self._create_flask_app()
        self.active_clients: Dict[str, Dict[str, Any]] = {}
        self.request_stats = {
            'total_requests': 0,
            'api_requests': 0,
            'error_count': 0,
            'start_time': time.time()
        }
        
        # Initialize core components
        self._initialize_components()
        self._setup_routes()
        self._setup_middleware()
        self._setup_error_handlers()
        
        logger.info("Ekatra AI v3.0 server initialized successfully")

    def _create_flask_app(self) -> Flask:
        """Create and configure Flask application"""
        app = Flask(
            __name__,
            static_folder='frontend/build/static',
            static_url_path='/static',
            template_folder='frontend/build'
        )
        
        # Security configuration
        app.config.update(
            SECRET_KEY=os.urandom(24),
            JSON_SORT_KEYS=False,
            JSONIFY_PRETTYPRINT_REGULAR=True,
            MAX_CONTENT_LENGTH=16 * 1024 * 1024,  # 16MB max request size
        )
        
        # CORS configuration
        CORS(app, 
             origins=self.config.ALLOWED_ORIGINS,
             allow_headers=['Content-Type', 'Authorization'],
             methods=['GET', 'POST', 'OPTIONS'])
        
        # Proxy support for production deployment
        app.wsgi_app = ProxyFix(app.wsgi_app)
        
        return app

    def _initialize_components(self):
        """Initialize all system components with error handling"""
        try:
            logger.info("Initializing Ekatra AI components...")
            
            # Model Manager
            self.model_manager = ModelManager(self.config)
            if self.config.USE_LOCAL_MODEL:
                self.model_manager.initialize_local_model()
            logger.info("✓ AI Model Manager initialized")
            
            # System Monitor
            self.system_monitor = SystemMonitor()
            logger.info("✓ System Monitor initialized")
            
            # WiFi Manager
            self.wifi_manager = WiFiManagerV2()
            logger.info("✓ WiFi Manager initialized")
            
            # Network Discovery
            self.network_discovery = NetworkDiscovery()
            logger.info("✓ Network Discovery initialized")
            
            logger.info("All components initialized successfully")
            
        except Exception as e:
            logger.error(f"Component initialization failed: {e}")
            raise RuntimeError(f"Failed to initialize server components: {e}")

    def _setup_middleware(self):
        """Setup request/response middleware"""
        
        @self.app.before_request
        def before_request():
            """Pre-request processing"""
            g.start_time = time.time()
            g.client_ip = request.remote_addr
            
            # Track request statistics
            self.request_stats['total_requests'] += 1
            if request.path.startswith('/api/'):
                self.request_stats['api_requests'] += 1
            
            # Track client activity
            self._track_client_activity(g.client_ip)
        
        @self.app.after_request
        def after_request(response):
            """Post-request processing"""
            if hasattr(g, 'start_time'):
                duration = time.time() - g.start_time
                if duration > 1.0:  # Log slow requests
                    logger.warning(f"Slow request: {request.path} took {duration:.2f}s")
            
            # Add security headers
            response.headers.update({
                'X-Content-Type-Options': 'nosniff',
                'X-Frame-Options': 'DENY',
                'X-XSS-Protection': '1; mode=block',
                'Content-Security-Policy': "default-src 'self' 'unsafe-inline' 'unsafe-eval' https://fonts.googleapis.com https://fonts.gstatic.com"
            })
            
            return response

    def _setup_routes(self):
        """Setup all application routes"""
        
        # =====================================================================
        # Frontend Routes (React SPA)
        # =====================================================================
        
        @self.app.route('/')
        @self.app.route('/client')
        @self.app.route('/admin')
        def serve_react_app():
            """Serve React application for all frontend routes"""
            try:
                return send_from_directory('frontend/build', 'index.html')
            except FileNotFoundError:
                return self._create_fallback_page(), 503
            except Exception as e:
                logger.error(f"Error serving React app: {e}")
                return "Application error", 500

        @self.app.route('/<filename>')
        def serve_build_files(filename):
            """Serve React build files (manifest, favicon, etc.)"""
            allowed_files = ['manifest.json', 'favicon.ico', 'robots.txt', 'sw.js']
            if filename in allowed_files:
                try:
                    return send_from_directory('frontend/build', filename)
                except FileNotFoundError:
                    return "File not found", 404
            
            # Redirect unknown files to React app
            return serve_react_app()

        # =====================================================================
        # API Routes - Chat & AI
        # =====================================================================
        
        @self.app.route('/api/generate', methods=['POST'])
        @self.app.route('/api/chat/message', methods=['POST'])
        def handle_chat_message():
            """Process chat message and generate AI response"""
            try:
                # Validate request
                data = request.get_json()
                if not data or 'message' not in data:
                    return self._api_error("Missing 'message' in request", 400)
                
                user_message = data['message'].strip()
                if not user_message:
                    return self._api_error("Message cannot be empty", 400)
                
                if len(user_message) > 5000:
                    return self._api_error("Message too long (max 5000 characters)", 400)
                
                # Generate AI response
                logger.info(f"Processing message from {g.client_ip}: {user_message[:100]}...")
                
                start_time = time.time()
                response = self.model_manager.generate_response_sync(user_message)
                generation_time = time.time() - start_time
                
                if not response:
                    return self._api_error("Failed to generate response", 500)
                
                # Format response with markdown
                formatted_response = self._format_markdown_safely(response)
                
                return self._api_success({
                    'response': formatted_response,
                    'message_id': int(time.time() * 1000),
                    'generation_time': round(generation_time, 2),
                    'timestamp': datetime.now(timezone.utc).isoformat()
                })
                
            except Exception as e:
                logger.error(f"Chat message error: {e}")
                return self._api_error("Internal server error", 500)

        # =====================================================================
        # API Routes - Device & Network Management
        # =====================================================================
        
        @self.app.route('/api/devices', methods=['GET'])
        def get_network_devices():
            """Discover and return network devices"""
            try:
                # Parse query parameters
                ekatra_only = request.args.get('ekatra_only', 'true').lower() == 'true'
                deep_scan = request.args.get('deep', 'false').lower() == 'true'
                timeout = min(int(request.args.get('timeout', '5')), 15)  # Reduced max timeout
                
                logger.info(f"Device discovery: ekatra_only={ekatra_only}, deep={deep_scan}, timeout={timeout}")
                
                # Perform device discovery
                devices = self.network_discovery.discover_all_subnets(
                    filter_self_network_only=ekatra_only,
                    deep_scan=deep_scan,
                    timeout=timeout
                )
                
                return self._api_success({
                    'devices': devices,
                    'count': len(devices),
                    'scan_parameters': {
                        'ekatra_only': ekatra_only,
                        'deep_scan': deep_scan,
                        'timeout': timeout
                    },
                    'timestamp': datetime.now(timezone.utc).isoformat()
                })
                
            except Exception as e:
                logger.error(f"Device discovery error: {e}")
                return self._api_error("Device discovery failed", 500)

        @self.app.route('/api/network/wifi', methods=['GET'])
        def get_wifi_status():
            """Get WiFi network status and information"""
            try:
                wifi_info = self.wifi_manager.get_detailed_status()
                return self._api_success(wifi_info)
            except Exception as e:
                logger.error(f"WiFi status error: {e}")
                return self._api_error("Failed to get WiFi status", 500)

        # =====================================================================
        # API Routes - System Management
        # =====================================================================
        
        @self.app.route('/api/system/status', methods=['GET'])
        @self.app.route('/api/system/info', methods=['GET'])
        def get_system_status():
            """Get comprehensive system status"""
            try:
                system_stats = self.system_monitor.get_system_stats()
                
                # Add server-specific stats
                uptime = time.time() - self.request_stats['start_time']
                system_stats.update({
                    'server': {
                        'uptime': round(uptime, 2),
                        'version': '3.0',
                        'total_requests': self.request_stats['total_requests'],
                        'api_requests': self.request_stats['api_requests'],
                        'error_count': self.request_stats['error_count'],
                        'active_clients': len(self.active_clients)
                    }
                })
                
                return self._api_success(system_stats)
                
            except Exception as e:
                logger.error(f"System status error: {e}")
                return self._api_error("Failed to get system status", 500)

        @self.app.route('/api/clients/active', methods=['GET'])
        def get_active_clients():
            """Get list of currently active clients"""
            try:
                # Clean up expired clients
                self._cleanup_inactive_clients()
                
                clients_list = [
                    {
                        'ip': ip,
                        'connected_at': datetime.fromtimestamp(info['first_seen'], timezone.utc).isoformat(),
                        'last_activity': datetime.fromtimestamp(info['last_seen'], timezone.utc).isoformat(),
                        'request_count': info['request_count'],
                        'active_duration': round(time.time() - info['first_seen'], 2)
                    }
                    for ip, info in self.active_clients.items()
                ]
                
                return self._api_success({
                    'clients': clients_list,
                    'count': len(clients_list),
                    'timestamp': datetime.now(timezone.utc).isoformat()
                })
                
            except Exception as e:
                logger.error(f"Active clients error: {e}")
                return self._api_error("Failed to get active clients", 500)

        @self.app.route('/api/health', methods=['GET'])
        def health_check():
            """Health check endpoint for monitoring"""
            health_status = {
                'status': 'healthy',
                'timestamp': datetime.now(timezone.utc).isoformat(),
                'components': {
                    'model': self.model_manager.model_loaded if hasattr(self.model_manager, 'model_loaded') else False,
                    'wifi': True,  # WiFi manager is always available
                    'network_discovery': True,
                    'system_monitor': True
                }
            }
            
            # Check if any component is unhealthy
            if not all(health_status['components'].values()):
                health_status['status'] = 'degraded'
                return jsonify(health_status), 503
            
            return jsonify(health_status)

        # =====================================================================
        # Catch-all route for React Router
        # =====================================================================
        
        @self.app.route('/<path:path>')
        def catch_all_routes(path):
            """Handle all other routes for React Router"""
            # Don't interfere with API routes
            if path.startswith('api/'):
                return self._api_error("API endpoint not found", 404)
            
            # Serve React app for all frontend routes
            return serve_react_app()

    def _setup_error_handlers(self):
        """Setup comprehensive error handling"""
        
        @self.app.errorhandler(404)
        def handle_not_found(error):
            """Handle 404 errors"""
            self.request_stats['error_count'] += 1
            
            if request.path.startswith('/api/'):
                return self._api_error("Endpoint not found", 404)
            
            # For frontend routes, serve React app
            return send_from_directory('frontend/build', 'index.html')

        @self.app.errorhandler(500)
        def handle_internal_error(error):
            """Handle 500 errors"""
            self.request_stats['error_count'] += 1
            logger.error(f"Internal server error: {error}")
            
            if request.path.startswith('/api/'):
                return self._api_error("Internal server error", 500)
            
            return "Internal server error", 500

        @self.app.errorhandler(413)
        def handle_payload_too_large(error):
            """Handle file too large errors"""
            return self._api_error("Request too large", 413)

    # =========================================================================
    # Helper Methods
    # =========================================================================
    
    def _track_client_activity(self, client_ip: str):
        """Track client activity for monitoring"""
        current_time = time.time()
        
        if client_ip in self.active_clients:
            self.active_clients[client_ip]['last_seen'] = current_time
            self.active_clients[client_ip]['request_count'] += 1
        else:
            self.active_clients[client_ip] = {
                'first_seen': current_time,
                'last_seen': current_time,
                'request_count': 1
            }
            logger.info(f"New client connected: {client_ip}")

    def _cleanup_inactive_clients(self, timeout: int = 300):
        """Remove clients inactive for more than timeout seconds"""
        current_time = time.time()
        inactive_clients = [
            ip for ip, info in self.active_clients.items()
            if current_time - info['last_seen'] > timeout
        ]
        
        for ip in inactive_clients:
            del self.active_clients[ip]
            logger.info(f"Removed inactive client: {ip}")

    def _format_markdown_safely(self, text: str) -> str:
        """Safely format markdown text with security measures"""
        try:
            # Convert markdown to HTML
            html = markdown.markdown(
                text,
                extensions=['fenced_code', 'tables', 'toc', 'nl2br'],
                tab_length=2
            )
            
            # Sanitize HTML for security
            allowed_tags = [
                'p', 'br', 'strong', 'em', 'u', 'code', 'pre',
                'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
                'ul', 'ol', 'li', 'blockquote',
                'table', 'thead', 'tbody', 'tr', 'td', 'th',
                'div', 'span', 'a'
            ]
            
            allowed_attributes = {
                '*': ['class', 'id'],
                'a': ['href', 'title', 'target'],
                'code': ['class'],
                'pre': ['class']
            }
            
            clean_html = bleach.clean(
                html,
                tags=allowed_tags,
                attributes=allowed_attributes,
                strip=True
            )
            
            return clean_html
            
        except Exception as e:
            logger.error(f"Markdown formatting error: {e}")
            return text  # Return original text if formatting fails

    def _api_success(self, data: Any) -> Tuple[Dict, int]:
        """Format successful API response"""
        return jsonify({
            'success': True,
            'data': data,
            'timestamp': datetime.now(timezone.utc).isoformat()
        }), 200

    def _api_error(self, message: str, code: int = 400) -> Tuple[Dict, int]:
        """Format error API response"""
        return jsonify({
            'success': False,
            'error': message,
            'timestamp': datetime.now(timezone.utc).isoformat()
        }), code

    def _create_fallback_page(self) -> str:
        """Create fallback HTML page when React build is not available"""
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <title>Ekatra AI - Building...</title>
            <style>
                body { font-family: Arial, sans-serif; text-align: center; padding: 50px; }
                .container { max-width: 600px; margin: 0 auto; }
                .spinner { border: 4px solid #f3f3f3; border-top: 4px solid #3498db; 
                          border-radius: 50%; width: 50px; height: 50px; 
                          animation: spin 2s linear infinite; margin: 20px auto; }
                @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
            </style>
        </head>
        <body>
            <div class="container">
                <h1>🚀 Ekatra AI</h1>
                <div class="spinner"></div>
                <p>Frontend is building... Please wait a moment and refresh.</p>
                <p>If this persists, run: <code>npm run build</code> in the frontend directory.</p>
            </div>
        </body>
        </html>
        """

    # =========================================================================
    # Server Management
    # =========================================================================
    
    def run(self, host: str = '0.0.0.0', port: int = 5000, debug: bool = False):
        """Run the Flask development server"""
        logger.info(f"🚀 Starting Ekatra AI v3.0 on {host}:{port}")
        logger.info(f"🎯 Client Interface: http://{host}:{port}/client")
        logger.info(f"🛡️ Admin Dashboard: http://{host}:{port}/admin")
        logger.info(f"🔧 Debug mode: {debug}")
        
        try:
            self.app.run(
                host=host,
                port=port,
                debug=debug,
                threaded=True,
                use_reloader=False  # Disable reloader to prevent double initialization
            )
        except KeyboardInterrupt:
            logger.info("Server shutdown requested")
        except Exception as e:
            logger.error(f"Server error: {e}")
            raise

    def shutdown_gracefully(self):
        """Graceful shutdown handler"""
        logger.info("Initiating graceful shutdown...")
        
        # Cleanup resources
        if hasattr(self, 'model_manager'):
            try:
                # Add any model cleanup if needed
                pass
            except Exception as e:
                logger.error(f"Model cleanup error: {e}")
        
        logger.info("Server shutdown completed")


def setup_signal_handlers(server: EkatraAIServer):
    """Setup signal handlers for graceful shutdown"""
    def signal_handler(signum, frame):
        logger.info(f"Received signal {signum}")
        server.shutdown_gracefully()
        sys.exit(0)
    
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)


def main():
    """Main entry point"""
    try:
        # Create server instance
        server = EkatraAIServer()
        
        # Setup signal handlers
        setup_signal_handlers(server)
        
        # Get configuration from environment
        host = os.getenv('EKATRA_HOST', '0.0.0.0')
        port = int(os.getenv('EKATRA_PORT', '5000'))
        debug = os.getenv('EKATRA_DEBUG', 'false').lower() == 'true'
        
        # Run server
        server.run(host=host, port=port, debug=debug)
        
    except Exception as e:
        logger.error(f"Failed to start server: {e}")
        sys.exit(1)


if __name__ == '__main__':
    main()
