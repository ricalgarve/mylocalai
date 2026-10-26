@echo off
chcp 65001 >nul
setlocal

title MyLocal.AI - Compartilhar APK via Wi-Fi

echo ==============================================================================
echo           📲  MYLOCAL.AI - COMPARTILHAR APK VIA WI-FI
echo ==============================================================================
echo.

where python >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERRO] Python não foi encontrado no PATH do sistema.
    echo        Instale o Python 3 para poder executar o servidor.
    pause
    exit /b 1
)

python serve_apk.py --port 8000
pause
