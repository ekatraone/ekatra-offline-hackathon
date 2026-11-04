#!/usr/bin/env python3
"""
Model downloader script for Ekatra AI system
"""
import os
import sys
from huggingface_hub import snapshot_download, login

def download_model(model_name, local_dir, token=None):
    """Download a model from HuggingFace"""
    try:
        print(f"🔑 Authenticating with token...")
        if token:
            login(token=token)
        
        print(f"📥 Downloading {model_name}...")
        print(f"📂 Saving to: {local_dir}")
        
        # Create directory if it doesn't exist
        os.makedirs(local_dir, exist_ok=True)
        
        # Download the model
        snapshot_download(
            repo_id=model_name,
            local_dir=local_dir,
            token=token,
            force_download=False,  # Don't re-download if already exists
            resume_download=True   # Resume if interrupted
        )
        
        print(f"✅ Model downloaded successfully to {local_dir}")
        return True
        
    except Exception as e:
        print(f"❌ Error downloading model: {str(e)}")
        return False

if __name__ == "__main__":
    # Configuration
    HF_TOKEN = "your_huggingface_token_here"
    
    # Available models to download
    models = {
        "1": {
            "name": "meta-llama/Llama-3.2-1B-Instruct",
            "dir": "./models/Llama-3.2-1B-Instruct",
            "description": "Llama 3.2 1B (Best, requires gated access)"
        },
        "2": {
            "name": "microsoft/DialoGPT-medium", 
            "dir": "./models/DialoGPT-medium",
            "description": "DialoGPT Medium (Good for conversations)"
        },
        "3": {
            "name": "distilgpt2",
            "dir": "./models/distilgpt2", 
            "description": "DistilGPT2 (Small, fast, no auth required)"
        }
    }
    
    print("🤖 Ekatra AI Model Downloader")
    print("=" * 40)
    
    # Show available models
    for key, model in models.items():
        print(f"{key}. {model['description']}")
    
    print("\nWhich model would you like to download?")
    choice = input("Enter choice (1-3): ").strip()
    
    if choice in models:
        model_info = models[choice]
        print(f"\nDownloading {model_info['name']}...")
        
        # Use token for gated models
        token = HF_TOKEN if choice in ["1", "2"] else None
        
        success = download_model(
            model_name=model_info["name"],
            local_dir=model_info["dir"],
            token=token
        )
        
        if success:
            print(f"\n🎉 Success! To use this model, update your .env file:")
            print(f"MODEL_NAME={model_info['dir']}")
        else:
            print(f"\n💡 Try a different model or check your token permissions")
    else:
        print("Invalid choice!")
