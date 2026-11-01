# Vydra

<p align="center">
  <strong>"Your voiceover + your images = a finished video."</strong>
</p>

<p align="center">
  <a href="https://android.com">
    <img src="https://img.shields.io/badge/Platform-Android_8.0+-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Platform: Android" />
  </a>
  <a href="https://kotlinlang.org">
    <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Language: Kotlin" />
  </a>
  <a href="https://opensource.org/licenses/MIT">
    <img src="https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square" alt="License: MIT" />
  </a>
  <a href="https://github.com/ReachifyHub/Ontic-Cut/releases/latest">
    <img src="https://img.shields.io/badge/Status-v1.0.0--beta1-007AFF?style=flat-square" alt="Status: v1.0.0-beta1" />
  </a>
</p>

---

## 📖 Introduction

**Vydra** is a high-performance native Android video editor engineered specifically for faceless YouTube creators, documentary channels, history essayists, and digital storytellers.

Creating long-form or short-form documentary-style content traditionally takes hours of dragging and matching images to speech on a desktop timeline. Vydra turns this entire workflow on its head: **paste your narration script, drop in hundreds of images or clips, and let Auto Assemble instantly build your timeline** complete with Ken Burns motion dynamics, transitions, and synced auto-captions.

Everything edits and renders directly on your Android device with zero watermarks.

---

## 🔗 Quick Links

* 🌐 **Website:** [vydra-editor.netlify.app](https://vydra-editor.netlify.app/)
* 📥 **Download APK:** [Latest GitHub Release](https://github.com/ReachifyHub/Ontic-Cut/releases/latest)
* 💬 **WhatsApp Community:** [Join the Vydra Creator Group](https://chat.whatsapp.com/L5LJK0HMcbLI9zS3CX2S38)
* 🐛 **Report a Bug:** [vydra-editor.netlify.app/bug-report](https://vydra-editor.netlify.app/bug-report)

---

## ✨ Core Features

* ⚡ **Auto Assemble:** Align script-map scenes to voiceover speech boundaries automatically using word-level Grok AI transcription.
* 💬 **Auto Captions:** Generate word-perfect, styled, and customizable animated subtitle overlays directly from narration audio.
* 🖼️ **Bulk Folder Import:** Select and import entire photo batches and document folders with one tap; images are sequenced and prepared on the fly.
* 🎛️ **Pro Manual Editor:** Full multi-track timeline featuring precision keyframes, custom mask shapes, chroma key (green screen), volume envelopes with audio ducking, speed ramps, and 20+ animated transitions.
* 📐 **Flexible Canvas:** Effortlessly switch and frame content in 16:9 (Landscape), 9:16 (Shorts/Reels/TikTok), 1:1 (Square), or 4:5 with render resolutions up to 4K Ultra HD.

---

## 📱 Screenshots

<div align="center">
  <table border="0">
    <tr>
      <td align="center" width="33%">
        <img src="assets/screenshot-1.png" alt="Auto Assemble & Script Map" width="100%" />
        <br />
        <sub><b>Auto Assemble &amp; Script Mapping</b></sub>
      </td>
      <td align="center" width="33%">
        <img src="assets/screenshot-2.png" alt="Multi-Track Video Editor" width="100%" />
        <br />
        <sub><b>Multi-Track Timeline Editor</b></sub>
      </td>
      <td align="center" width="33%">
        <img src="assets/screenshot-3.png" alt="Auto Captions & Export" width="100%" />
        <br />
        <sub><b>Synced Captions &amp; High-Speed Export</b></sub>
      </td>
    </tr>
  </table>
</div>

---

## 🛠️ Tech Stack & Legal Attribution

### Architecture & Libraries
* **Language & Runtime:** Kotlin (Coroutines, Flow, StateFlow, AndroidX Lifecycle & ViewModel)
* **Video Rendering:** [FFmpegKit (Full GPL)](https://github.com/arthenica/ffmpeg-kit) for high-efficiency timeline compositing, filter chains, and encoding
* **Media Playback:** ExoPlayer / AndroidX Media3 for real-time preview and responsive seeking
* **Cloud & Auth:** Supabase Auth & Storage
* **Speech-to-Text:** Grok STT via NanoGPT API proxy

### Attribution & Heritage
> **Vydra is originally based on the LibreCuts project by Tharun Birla, licensed under the MIT License. We gratefully acknowledge this foundation.**

---

## 💻 Local Development

### Prerequisites
* Android Studio Hedgehog (2023.1.1) or newer
* Android SDK 34 (API Level 34)
* JDK 17+

### Getting Started

1. **Clone the repository:**
   ```bash
   git clone https://github.com/ReachifyHub/Ontic-Cut.git
   cd Ontic-Cut
   ```

2. **Open in Android Studio:**
   - Launch Android Studio and select **Open**.
   - Navigate to the cloned folder and let Gradle sync dependencies.

3. **Configure Environment Keys:**
   - Create a `local.properties` file in the project root if one does not exist.
   - For cloud transcription and account sync features, configure your Supabase endpoint and NanoGPT transcription credentials:
     ```properties
     SUPABASE_URL=https://your-project.supabase.co
     SUPABASE_ANON_KEY=your-anon-key
     ```

4. **Build and Run:**
   - Select the `app` run configuration.
   - Deploy to an Android device or emulator running **Android 8.0 (API 26)** or higher.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
