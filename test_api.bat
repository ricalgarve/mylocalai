@echo off
chcp 65001 >nul
setlocal

echo ==============================================================================
echo             MYLOCALAI - TESTE DE COMUNICAÇÃO HTTP COM A API
echo ==============================================================================
echo.

set /p TARGET_IP="Digite o IP e porta do celular (ex: 192.168.1.100:8080 ou Enter para localhost:8080): "
if "%TARGET_IP%"=="" set TARGET_IP=localhost:8080

set /p API_TOKEN="Digite o Token de Autenticação (ou Enter se não configurou token): "
if not "%API_TOKEN%"=="" (
    set "AUTH_HEADER=-H Authorization: Bearer %API_TOKEN%"
) else (
    set "AUTH_HEADER="
)

echo.
echo [*] Alvo selecionado: http://%TARGET_IP%
echo.

echo ------------------------------------------------------------------------------
echo 1. Testando Healthcheck (/health):
echo ------------------------------------------------------------------------------
curl -s %AUTH_HEADER% "http://%TARGET_IP%/health"
echo.
echo.

echo ------------------------------------------------------------------------------
echo 2. Listando Modelos Ativos (/v1/models):
echo ------------------------------------------------------------------------------
curl -s %AUTH_HEADER% "http://%TARGET_IP%/v1/models"
echo.
echo.

echo ------------------------------------------------------------------------------
echo 3. Enviando Chat Completion (Streaming SSE em tempo real):
echo ------------------------------------------------------------------------------
curl -N -X POST "http://%TARGET_IP%/v1/chat/completions" ^
  -H "Content-Type: application/json" ^
  %AUTH_HEADER% ^
  -d "{\"messages\": [{\"role\": \"user\", \"content\": \"Olá! Diga quem você é em uma frase curta.\"}], \"temperature\": 0.7, \"stream\": true}"

echo.
echo.
echo ==============================================================================
echo Teste concluído com sucesso!
echo ==============================================================================
pause
