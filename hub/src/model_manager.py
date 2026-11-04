"""
AI Model Management Module
Handles local AI model initialization and inference
"""
import os
import time
import torch
import platform
from transformers import AutoTokenizer, AutoModelForCausalLM
from huggingface_hub import login, snapshot_download
from huggingface_hub.utils import RepositoryNotFoundError, RevisionNotFoundError
import requests
import json
from typing import Optional, Dict, Any, Tuple
from config.settings import Config

class ModelManager:
    def __init__(self, config: Config):
        self.config = config
        self.model = None
        self.tokenizer = None
        self.model_loaded = False
        self.response_count = 0
        self.total_response_time = 0
        
    def get_optimal_device(self) -> Tuple[str, str]:
        """
        Detect and return the optimal device for model loading
        Returns tuple of (device_type, device_name)
        """
        try:
            # Check for NVIDIA GPU first
            if torch.cuda.is_available():
                gpu_name = torch.cuda.get_device_name(0)
                gpu_memory = torch.cuda.get_device_properties(0).total_memory
                gpu_memory_gb = gpu_memory / (1024**3)
                
                # Check if we have enough GPU memory (at least 4GB recommended)
                if gpu_memory_gb < 4:
                    print(f"⚠️ GPU memory ({gpu_memory_gb:.1f}GB) might be insufficient, consider CPU fallback")
                
                return "cuda", f"NVIDIA {gpu_name} ({gpu_memory_gb:.1f}GB)"
            
            # Check for Apple Silicon GPU (MPS)
            elif hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
                try:
                    # Test MPS backend functionality
                    test_tensor = torch.zeros(1).to("mps")
                    del test_tensor  # Clean up test tensor
                    
                    # Get system info for Apple Silicon
                    cpu_info = platform.processor() or platform.machine()
                    return "mps", f"Apple Silicon GPU ({cpu_info})"
                except Exception as e:
                    print(f"⚠️ MPS backend test failed: {e}, falling back to CPU")
                    return "cpu", platform.processor() or "CPU"
            
            # Fallback to CPU
            else:
                cpu_info = platform.processor() or platform.machine() or "CPU"
                return "cpu", cpu_info
                
        except Exception as e:
            print(f"⚠️ Error detecting device: {str(e)}, using CPU fallback")
            return "cpu", "CPU (fallback)"

    def check_model_availability(self, model_name: str) -> Tuple[bool, Optional[str]]:
        """Check if model is available locally"""
        # First check our local models directory
        local_model_path = os.path.join(self.config.MODEL_CACHE_DIR, model_name.split('/')[-1])
        if os.path.exists(local_model_path):
            # Check if essential model files exist
            config_file = os.path.join(local_model_path, "config.json")
            model_file = os.path.join(local_model_path, "model.safetensors")
            tokenizer_file = os.path.join(local_model_path, "tokenizer.json")
            
            if all(os.path.exists(f) for f in [config_file, model_file, tokenizer_file]):
                print(f"✅ Found local model at: {local_model_path}")
                return True, local_model_path
        
        # Fallback to Hugging Face cache
        try:
            cache_dir = snapshot_download(
                model_name, 
                token=self.config.HF_TOKEN, 
                local_files_only=True
            )
            return True, cache_dir
        except (RepositoryNotFoundError, RevisionNotFoundError):
            return False, None
        except Exception as e:
            print(f"⚠️ Error checking model availability: {str(e)}")
            return False, None
    
    def initialize_local_model(self) -> bool:
        """Initialize the local Hugging Face model"""
        if not self.config.USE_LOCAL_MODEL:
            print("🌐 Using API mode. No local model initialization required.")
            return True
        
        try:
            print("🤖 Initializing local AI model...")
            
            # Login to Hugging Face if token provided
            if self.config.HF_TOKEN:
                print("🔑 Authenticating with Hugging Face...")
                login(self.config.HF_TOKEN, add_to_git_credential=True)
            
            # Check if model is available locally
            is_available, cache_dir = self.check_model_availability(self.config.MODEL_NAME)
            
            if not is_available:
                print(f"📥 Model {self.config.MODEL_NAME} not found locally. Downloading...")
                print("⏳ This may take several minutes depending on your internet speed...")
                cache_dir = snapshot_download(
                    self.config.MODEL_NAME, 
                    token=self.config.HF_TOKEN
                )
                print("✅ Model downloaded successfully!")
            else:
                print(f"✅ Model found in local cache: {cache_dir}")
            
            # Use the resolved model path for loading
            model_path = cache_dir
            
            # Load tokenizer
            print("🔤 Loading tokenizer...")
            self.tokenizer = AutoTokenizer.from_pretrained(
                model_path, 
                token=self.config.HF_TOKEN,
                local_files_only=True
            )
            self.tokenizer.pad_token = self.tokenizer.eos_token
            
            # Detect optimal device
            device, device_name = self.get_optimal_device()
            print(f"🎯 Using device: {device_name}")
            
            # Load model based on device type with optimizations
            try:
                if device == "cuda":
                    print("⚡ Loading model on NVIDIA GPU...")
                    self.model = AutoModelForCausalLM.from_pretrained(
                        model_path,
                        torch_dtype=torch.float16,
                        low_cpu_mem_usage=True,
                        device_map="auto",
                        token=self.config.HF_TOKEN,
                        local_files_only=True
                    )
                elif device == "mps":
                    print("⚡ Loading model on Apple Silicon GPU...")
                    self.model = AutoModelForCausalLM.from_pretrained(
                        model_path,
                        torch_dtype=torch.float16,
                        low_cpu_mem_usage=True,
                        token=self.config.HF_TOKEN,
                        local_files_only=True
                    ).to(device)
                else:
                    print("💻 Loading model on CPU...")
                    self.model = AutoModelForCausalLM.from_pretrained(
                        model_path,
                        torch_dtype=torch.float32,
                        low_cpu_mem_usage=True,
                        token=self.config.HF_TOKEN,
                        local_files_only=True
                    ).to(device)
                    
            except RuntimeError as e:
                if "out of memory" in str(e).lower():
                    print("⚠️ GPU memory insufficient, falling back to CPU...")
                    device = "cpu"
                    device_name = platform.processor() or "CPU"
                    self.model = AutoModelForCausalLM.from_pretrained(
                        model_path,
                        torch_dtype=torch.float32,
                        low_cpu_mem_usage=True,
                        token=self.config.HF_TOKEN,
                        local_files_only=True
                    ).to(device)
                else:
                    raise e
            
            self.model_loaded = True
            print("✅ AI model initialized successfully!")
            return True
            
        except Exception as e:
            print(f"❌ Error initializing local model: {str(e)}")
            print("💡 Try using API mode by setting USE_LOCAL_MODEL=false in .env")
            return False
    
    def generate_response_local(self, prompt: str) -> str:
        """Generate response using local model"""
        if not self.model_loaded or not self.model or not self.tokenizer:
            return "❌ Local AI model not available. Please check server configuration."
        
        try:
            start_time = time.time()
            
            # Prepare conversation
            conversation = [
                {"role": "system", "content": self.config.SYSTEM_PROMPT},
                {"role": "user", "content": prompt}
            ]
            
            # Apply chat template if available
            try:
                formatted_prompt = self.tokenizer.apply_chat_template(
                    conversation, 
                    tokenize=False, 
                    add_generation_prompt=True
                )
            except Exception:
                # Fallback if chat template not available
                formatted_prompt = f"User: {prompt}\n\nAssistant:"
        
            # Tokenize input
            inputs = self.tokenizer(
                formatted_prompt, 
                return_tensors="pt", 
                padding=True,
                truncation=True,
                max_length=2048
            ).to(self.model.device)
            
            # Generate response with proper parameters
            with torch.no_grad():
                output = self.model.generate(
                    **inputs,
                    max_new_tokens=self.config.MAX_TOKENS,
                    temperature=self.config.TEMPERATURE,
                    top_p=self.config.TOP_P,
                    do_sample=True,
                    num_beams=1,
                    pad_token_id=self.tokenizer.eos_token_id,
                    eos_token_id=self.tokenizer.eos_token_id,
                    repetition_penalty=1.2
                )
            
            # Decode and format response
            full_response = self.tokenizer.decode(output[0], skip_special_tokens=True)
            
            # DEBUG: Print full response details
            print(f"\n{'='*50}")
            print(f"🔍 LLM DEBUG OUTPUT")
            print(f"{'='*50}")
            print(f"📥 Input prompt length: {len(formatted_prompt)} chars")
            print(f"📤 Full response length: {len(full_response)} chars")
            print(f"⚙️  Max tokens setting: {self.config.MAX_TOKENS}")
            print(f"📝 Raw full response:")
            print(f"{'—'*50}")
            print(full_response)
            print(f"{'—'*50}")
            
            # Extract only the assistant's response
            response = ""
            if "<|start_header_id|>assistant<|end_header_id|>" in full_response:
                response = full_response.split("<|start_header_id|>assistant<|end_header_id|>")[-1]
                response = response.split("<|eot_id|>")[0].strip()
                print(f"✂️  Extracted using Llama format")
            elif "assistant\n\n" in full_response:
                response = full_response.split("assistant\n\n")[-1].strip()
                print(f"✂️  Extracted using 'assistant\\n\\n' delimiter")
            elif "Assistant:" in full_response:
                response = full_response.split("Assistant:")[-1].strip()
                print(f"✂️  Extracted using 'Assistant:' delimiter")
            elif formatted_prompt in full_response:
                response = full_response.replace(formatted_prompt, "").strip()
                print(f"✂️  Extracted by removing input prompt")
            else:
                # Look for patterns like "assistant\n" followed by content
                lines = full_response.split('\n')
                start_found = False
                response_lines = []
                for line in lines:
                    if line.strip() == "assistant":
                        start_found = True
                        continue
                    elif start_found:
                        response_lines.append(line)
                response = '\n'.join(response_lines).strip()
                if response:
                    print(f"✂️  Extracted by finding 'assistant' line")
                else:
                    response = full_response.strip()
                    print(f"✂️  Using full response as fallback")
            
            print(f"📋 Extracted response length: {len(response)} chars")
            print(f"📋 Extracted response:")
            print(f"{'—'*30}")
            print(response)
            print(f"{'—'*30}")
            
            # Format response with proper spacing and line breaks
            formatted_response = self.format_response(response)
            
            print(f"🎨 Formatted response length: {len(formatted_response)} chars")
            print(f"🎨 Final formatted response:")
            print(f"{'—'*30}")
            print(formatted_response)
            print(f"{'='*50}")
            print()
            
            # Update statistics
            response_time = time.time() - start_time
            self.response_count += 1
            self.total_response_time += response_time
            
            return formatted_response if formatted_response else "I'm sorry, I couldn't generate a proper response. Please try rephrasing your question."
            
        except Exception as e:
            print(f"❌ Error generating local response: {str(e)}")
            return f"❌ Error processing your request: {str(e)}"
    
    def format_response(self, response: str) -> str:
        """Format response for better readability"""
        # Add line breaks between numbered points
        response = response.replace("\n1.", "\n\n1.")
        response = response.replace("\n2.", "\n\n2.")
        
        # Add space after bullet points
        response = response.replace("\n-", "\n\n-")
        
        # Ensure proper spacing around sections
        response = response.replace("\nSection", "\n\nSection")
        response = response.replace("\nSummary", "\n\nSummary")
        
        # Fix multiple consecutive line breaks
        while "\n\n\n" in response:
            response = response.replace("\n\n\n", "\n\n")
        
        return response.strip()
    
    def generate_response_api(self, prompt: str) -> str:
        """Generate response using Tune AI API"""
        try:
            payload = {
                "model": self.config.TUNE_MODEL_NAME,
                "messages": [
                    {"content": self.config.SYSTEM_PROMPT, "role": "system"},
                    {"content": prompt, "role": "user"}
                ],
                "max_tokens": self.config.MAX_TOKENS,
                "temperature": self.config.TEMPERATURE,
                "top_p": 0.95
            }
            
            headers = {
                "Authorization": f"Bearer {self.config.TUNE_API_KEY}",
                "X-Org-Id": self.config.TUNE_ORG_ID,
                "Content-Type": "application/json"
            }
            
            start_time = time.time()
            
            response = requests.post(
                self.config.TUNE_API_URL, 
                json=payload, 
                headers=headers, 
                timeout=30
            )
            response.raise_for_status()
            
            response_data = response.json()
            
            if 'choices' in response_data and len(response_data['choices']) > 0:
                ai_response = response_data['choices'][0]['message']['content'].strip()
                
                # Update statistics
                response_time = time.time() - start_time
                self.response_count += 1
                self.total_response_time += response_time
                
                return ai_response
            else:
                print("❌ Unexpected response format from Tune AI API")
                return "❌ Unexpected API response format. Please try again."
                
        except requests.RequestException as e:
            print(f"❌ API request error: {str(e)}")
            return "❌ Unable to connect to AI service. Please check your internet connection."
        except json.JSONDecodeError as e:
            print(f"❌ JSON decode error: {str(e)}")
            return "❌ Error processing AI response. Please try again."
        except Exception as e:
            print(f"❌ Unexpected API error: {str(e)}")
            return "❌ An unexpected error occurred. Please try again."
    
    def generate_response(self, prompt: str) -> str:
        """Generate response using configured method (local or API)"""
        if self.config.USE_LOCAL_MODEL:
            return self.generate_response_local(prompt)
        else:
            return self.generate_response_api(prompt)
    
    async def generate_response_async(self, prompt: str) -> Dict[str, Any]:
        """Async wrapper for generate_response with enhanced error handling"""
        import asyncio
        import concurrent.futures
        
        try:
            # Run the synchronous generate_response in a thread pool
            loop = asyncio.get_event_loop()
            with concurrent.futures.ThreadPoolExecutor() as executor:
                response = await loop.run_in_executor(
                    executor, 
                    self.generate_response, 
                    prompt
                )
            
            return {
                "response": response,
                "model_name": getattr(self, 'model_name', self.config.MODEL_NAME)
            }
            
        except Exception as e:
            return {
                "error": str(e),
                "model_name": getattr(self, 'model_name', self.config.MODEL_NAME)
            }
    
    def generate_response_sync(self, prompt: str) -> str:
        """Synchronous wrapper for generate_response_async for compatibility"""
        import asyncio
        
        try:
            # Try to get existing event loop
            loop = asyncio.get_event_loop()
            if loop.is_running():
                # If loop is already running, use run_in_executor
                import concurrent.futures
                with concurrent.futures.ThreadPoolExecutor() as executor:
                    future = executor.submit(self.generate_response, prompt)
                    return future.result(timeout=30)  # 30 second timeout
            else:
                # Run async method in new event loop
                result = loop.run_until_complete(self.generate_response_async(prompt))
                return result.get('response', '')
        except Exception as e:
            # Fallback to synchronous method
            return self.generate_response(prompt)
    
    def get_model_stats(self) -> Dict[str, Any]:
        """Get model performance statistics"""
        avg_response_time = (
            self.total_response_time / self.response_count 
            if self.response_count > 0 else 0
        )
        
        # Get current device info
        if self.config.USE_LOCAL_MODEL and self.model_loaded:
            device, device_name = self.get_optimal_device()
        else:
            device, device_name = self.get_optimal_device()
        
        return {
            "model_name": self.config.MODEL_NAME,
            "loaded": self.model_loaded if self.config.USE_LOCAL_MODEL else True,
            "response_count": self.response_count,
            "average_response_time": round(avg_response_time, 2),
            "device": device,
            "device_name": device_name,
            "mode": "local" if self.config.USE_LOCAL_MODEL else "api",
            "total_response_time": round(self.total_response_time, 2)
        }
    
    def get_available_models(self) -> Dict[str, Any]:
        """Get list of available models"""
        models = {
            # Llama Models (require authentication)
            "meta-llama/Llama-3.2-1B-Instruct": {
                "name": "Llama 3.2 1B Instruct",
                "size": "~2.5GB", 
                "description": "Latest Meta AI model, excellent for education",
                "requires_token": True,
                "category": "Advanced"
            },
            "meta-llama/Llama-3.2-3B-Instruct": {
                "name": "Llama 3.2 3B Instruct", 
                "size": "~6GB",
                "description": "Larger Llama model, more capable responses",
                "requires_token": True,
                "category": "Advanced"
            },
            
            # Microsoft Models
            "microsoft/DialoGPT-small": {
                "name": "DialoGPT Small",
                "size": "~300MB",
                "description": "Small conversational model, fast responses",
                "requires_token": False,
                "category": "Conversation"
            },
            "microsoft/DialoGPT-medium": {
                "name": "DialoGPT Medium", 
                "size": "~700MB",
                "description": "Medium conversational model, good balance",
                "requires_token": False,
                "category": "Conversation"
            },
            "microsoft/DialoGPT-large": {
                "name": "DialoGPT Large",
                "size": "~1.5GB", 
                "description": "Large conversational model, best quality",
                "requires_token": False,
                "category": "Conversation"
            },
            
            # OpenAI GPT Models
            "gpt2": {
                "name": "GPT-2",
                "size": "~500MB",
                "description": "Classic OpenAI model, reliable and fast",
                "requires_token": False,
                "category": "General"
            },
            "distilgpt2": {
                "name": "DistilGPT-2",
                "size": "~300MB", 
                "description": "Compressed GPT-2, very fast and lightweight",
                "requires_token": False,
                "category": "General"
            },
            
            # Google Models
            "google/flan-t5-small": {
                "name": "FLAN-T5 Small",
                "size": "~300MB",
                "description": "Google's instruction-tuned model, good for Q&A",
                "requires_token": False,
                "category": "Educational"
            },
            "google/flan-t5-base": {
                "name": "FLAN-T5 Base",
                "size": "~900MB", 
                "description": "Larger FLAN-T5, better at following instructions",
                "requires_token": False,
                "category": "Educational"
            },
            
            # EleutherAI Models  
            "EleutherAI/gpt-neo-125M": {
                "name": "GPT-Neo 125M",
                "size": "~500MB",
                "description": "Small open-source GPT model",
                "requires_token": False,
                "category": "General"
            },
            "EleutherAI/gpt-neo-1.3B": {
                "name": "GPT-Neo 1.3B", 
                "size": "~5GB",
                "description": "Large open-source GPT model, very capable",
                "requires_token": False,
                "category": "General"
            }
        }
        
        return models
    
    def switch_model(self, new_model_name: str) -> bool:
        """Switch to a different model"""
        try:
            print(f"🔄 Switching to model: {new_model_name}")
            
            # Clear current model
            if self.model is not None:
                del self.model
                self.model = None
            
            if self.tokenizer is not None:
                del self.tokenizer
                self.tokenizer = None
            
            # Clear GPU cache if using CUDA
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            
            # Update model name
            self.config.MODEL_NAME = new_model_name
            self.model_loaded = False
            
            # Initialize new model
            return self.initialize_local_model()
            
        except Exception as e:
            print(f"❌ Error switching model: {str(e)}")
            return False
    def restart_model(self) -> bool:
        """Restart the model (for local models)"""
        if not self.config.USE_LOCAL_MODEL:
            return True  # API mode doesn't need restart
        
        try:
            print("🔄 Restarting AI model...")
            
            # Clear current model
            if self.model is not None:
                del self.model
                self.model = None
            
            if self.tokenizer is not None:
                del self.tokenizer
                self.tokenizer = None
            
            # Clear GPU cache if using CUDA
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            
            self.model_loaded = False
            
            # Reinitialize
            return self.initialize_local_model()
            
        except Exception as e:
            print(f"❌ Error restarting model: {str(e)}")
            return False
