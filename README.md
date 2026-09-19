# 🤖 Edu AI Local

> **Private, native, offline AI chat for Android.**

Edu AI Local is a native Android app for running compatible GGUF language models directly on the phone. It combines a familiar chat experience with a model manager for downloading, importing, loading and removing local models.

## ✨ Current Features

- 💬 Native ChatGPT-style chat UI
- 🧠 On-device inference through llama.cpp
- 📦 Local GGUF model library
- ⬇️ Download supported models
- 📁 Import GGUF models from Android storage
- 🔄 Load and unload local models
- 📊 Download progress
- 🌙 Light / dark theme
- 🔒 Offline-first chat

### Supported download catalog

- **Llama 3.2 3B Instruct — Q4_K_M** (~2.02 GB)
- **Gemma 3 1B Instruct — Q4_K_M** (~806 MB)
- **Qwen 3 4B — Q4_K_M** (~2.50 GB)

Users can also import other compatible GGUF files from device storage.

## 🏗️ Technology

- **Platform:** Native Android
- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Inference:** llama.cpp / Arm AI Chat Android binding
- **Model format:** GGUF
- **Storage:** App-private local model storage
- **Minimum Android:** 13 (API 33)

The app uses the Android llama.cpp binding for local GGUF model loading and token generation.

## 🔐 Privacy

The chat inference path is designed to keep prompts and generated responses on the device. Internet access is used only for explicitly online operations such as downloading a model.

Once a model is installed, it can be loaded and used without an internet connection.

## 🚧 Status

**Early functional build**

The current codebase contains the native UI, model manager, GGUF import flow, model downloads and local inference integration. Further work will refine the UI, model metadata, chat persistence, performance controls and device-specific tuning.

## 🎯 Goal

> **Make local AI on Android as simple as opening a chat app.**

---

Built with ❤️ for private, accessible, on-device AI.
