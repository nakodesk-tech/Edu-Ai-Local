# 🤖 Edu AI Local

> **Private, native, offline AI chat for Android.**

Edu AI Local is a native Android application focused on running compatible AI models **locally on the device**. It combines a familiar chat experience with a model-first design, so users can choose, download, or import the local model they want to use.

## ✨ Highlights

- 💬 Familiar ChatGPT-style chat experience
- 📦 Local model library and model selection
- ⬇️ Download supported models
- 📁 Import already-downloaded **GGUF** models
- 🔄 Load and unload models when needed
- 🔒 Private, on-device AI inference
- 🌙 Light and dark themes
- 💾 Local conversation history
- 📱 Native Android experience

## 🧠 Model-first

Edu AI Local is **not tied to a single model**.

The architecture is intended to support compatible local models such as:

- Llama
- Gemma
- Qwen
- Phi
- Mistral
- Other supported GGUF models

Model compatibility and device performance will be verified before models are added to the supported catalog.

## 🏗️ Technology

- **Platform:** Native Android
- **Language:** Kotlin
- **UI:** Modern Android UI
- **Local inference:** llama.cpp
- **Model format:** GGUF
- **Storage:** Android local/app-private storage

## 🔐 Privacy

The core chat experience is designed for **on-device inference**. Once a compatible model is available locally, chat can continue without an internet connection or cloud AI API.

Internet access is only needed for features such as downloading models or other explicitly online functionality.

## 🚧 Project Status

**Early development**

Current development focus:

1. Native Android foundation
2. Chat interface
3. Model Manager
4. GGUF import
5. Local model loading
6. Offline inference
7. Model download/catalog experience

## 🎯 Goal

> **Make local AI on Android as simple as opening a chat app.**

---

Built with ❤️ for private, accessible, on-device AI.
