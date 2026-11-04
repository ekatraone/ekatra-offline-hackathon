#!/bin/bash

# Ekatra AI Frontend Build and Integration Script

set -e  # Exit on any error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
FRONTEND_DIR="$PROJECT_ROOT/frontend"
BACKEND_STATIC_DIR="$PROJECT_ROOT/static"
BACKEND_TEMPLATES_DIR="$PROJECT_ROOT/templates"

echo "🚀 Starting Ekatra AI Frontend Build Process..."

# Check if we're in the right directory
if [[ ! -f "$PROJECT_ROOT/server.py" ]]; then
    echo "❌ Error: server.py not found. Please run this script from the project root."
    exit 1
fi

# Navigate to frontend directory
cd "$FRONTEND_DIR"

echo "📦 Installing frontend dependencies..."
if command -v npm &> /dev/null; then
    npm install
elif command -v yarn &> /dev/null; then
    yarn install
else
    echo "❌ Error: Neither npm nor yarn found. Please install Node.js and npm."
    exit 1
fi

echo "🔨 Building React application..."
if command -v npm &> /dev/null; then
    npm run build
elif command -v yarn &> /dev/null; then
    yarn build
fi

# Check if build was successful
if [[ ! -d "$FRONTEND_DIR/build" ]]; then
    echo "❌ Error: Build failed. No build directory found."
    exit 1
fi

echo "📁 Integrating build with Flask backend..."

# Backup existing static files
if [[ -d "$BACKEND_STATIC_DIR" ]]; then
    echo "🔄 Backing up existing static files..."
    cp -r "$BACKEND_STATIC_DIR" "$BACKEND_STATIC_DIR.backup.$(date +%Y%m%d_%H%M%S)"
fi

# Copy built React files to Flask static directory
echo "📋 Copying React build to Flask static directory..."
cp -r "$FRONTEND_DIR/build/static/"* "$BACKEND_STATIC_DIR/" 2>/dev/null || true

# Copy index.html to templates directory for Flask to serve
echo "📋 Copying React index.html to Flask templates..."
cp "$FRONTEND_DIR/build/index.html" "$BACKEND_TEMPLATES_DIR/react_index.html"

# Create a route update file for Flask
cat > "$PROJECT_ROOT/flask_react_routes.py" << 'EOF'
"""
Additional Flask routes for React frontend integration
Add these routes to your main Flask application
"""

from flask import render_template, send_from_directory
import os

def add_react_routes(app):
    """Add React frontend routes to Flask app"""
    
    @app.route('/')
    @app.route('/client')
    @app.route('/admin')
    def react_app():
        """Serve React application for all frontend routes"""
        return render_template('react_index.html')
    
    @app.route('/static/<path:filename>')
    def react_static(filename):
        """Serve React static files"""
        return send_from_directory('static', filename)
    
    # Catch-all route for React Router
    @app.route('/<path:path>')
    def catch_all(path):
        """Catch all other routes and serve React app"""
        # Don't intercept API routes
        if path.startswith('api/'):
            return "API endpoint not found", 404
        return render_template('react_index.html')

EOF

echo ""
echo "✅ Frontend build and integration complete!"
echo ""
echo "📝 Next steps:"
echo "1. Add the React routes to your Flask app by importing and calling add_react_routes(app)"
echo "2. Update server.py to include the new routes if you still need the React integration helper"
echo "3. Start your Flask server to test the React frontend"
echo ""
echo "🌐 Frontend URLs:"
echo "   - Client Interface: http://localhost:5000/client"
echo "   - Admin Dashboard: http://localhost:5000/admin"
echo ""
echo "🔧 To update the frontend in the future, just run this script again!"

cd "$PROJECT_ROOT"
