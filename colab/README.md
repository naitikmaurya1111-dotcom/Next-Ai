# AGY Chat Colab Bridge

This project acts as a bridge between the AGY Chat Android app and the Antigravity CLI (`agy`) running in Google Colab.

## Setup Instructions

1. **Upload Files:** Upload all files in this directory to your Google Colab instance (e.g., into `/content/agy-chat-colab/`).
2. **Open Notebook:** Open `setup_colab.ipynb` in Google Colab.
3. **Get Ngrok Token:** Sign up at [ngrok.com](https://ngrok.com/) and get your authentication token.
4. **Configure Token:** Paste your ngrok token into the designated cell in `setup_colab.ipynb`.
5. **Run Cells:** Run all cells in the notebook.
6. **Get URL:** The notebook will display a WebSocket URL (starting with `wss://`) and a QR code.
7. **Configure App:** Copy the WebSocket URL and paste it into your Android app's Settings, or scan the QR code if your app supports it.
8. **Start Chatting!** Your app will now communicate directly with the `agy` CLI via this bridge server.

## Features
- Real-time WebSocket communication
- Streaming subprocess execution of `agy`
- Support for slash commands (`/goal`, `/plan`, etc.)
- File uploads (`/upload`)
- History retrieval (`/history`)
- Google Drive backup integration (`drive_backup.py`)
