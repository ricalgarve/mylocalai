@echo off
chcp 65001 >nul
setlocal

title MyLocal.AI - Servidor Python & Gateway OpenAI

echo ==============================================================================
echo                      MYLOCAL.AI - SERVIDOR PYTHON
echo ==============================================================================
echo.

where python >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERRO] Python não foi encontrado no PATH do sistema.
    echo        Instale o Python 3 (marque 'Add python.exe to PATH' no instalador).
    echo.
    pause
    exit /b 1
)

echo Escolha como deseja iniciar o servidor Python:
echo [1] Iniciar Servidor Web / Gateway Padrão (Porta 8080)
echo [2] Conectar e fazer Gateway para o Celular Android (digite o IP do celular)
echo [3] Carregar modelo .gguf local no PC (Requer llama-cpp-python)
echo.
set /p OPT="Escolha uma opção [1-3]: "

if "%OPT%"=="1" (
    echo [*] Iniciando servidor em http://localhost:8080 ...
    python server.py --port 8080
) else if "%OPT%"=="2" (
    set /p PHONE_IP="Digite o IP do celular (ex: 192.168.1.100:8080): "
    echo [*] Conectando ao celular em !PHONE_IP! ...
    python server.py --port 8080 --phone !PHONE_IP!
) else if "%OPT%"=="3" (
    set /p MODEL_FILE="Arraste ou digite o caminho do arquivo .gguf: "
    python server.py --port 8080 --model !MODEL_FILE!
) else (
    python server.py --port 8080
)

pause
