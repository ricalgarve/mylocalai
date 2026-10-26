#!/usr/bin/env bash
# Script para iniciar o servidor de compartilhamento do APK via Wi-Fi no Linux/macOS

cd "$(dirname "$0")"

python3 serve_apk.py --port 8000
