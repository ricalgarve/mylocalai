#!/usr/bin/env bash
# Script para iniciar o servidor Python MyLocal.AI no Linux/macOS

cd "$(dirname "$0")"

echo "=============================================================================="
echo "                     MYLOCAL.AI - SERVIDOR PYTHON LOCAL                       "
echo "=============================================================================="
echo ""
echo "Escolha o modo de inicialização:"
echo "1) Servidor Web & Gateway Padrão (Porta 8080)"
echo "2) Gateway conectado ao Celular Android"
echo "3) Inferência local no PC com arquivo .gguf"
echo ""
read -p "Opção [1-3]: " OPT

if [ "$OPT" = "2" ]; then
    read -p "Digite o IP e porta do celular (ex: 192.168.1.100:8080): " PHONE_IP
    python3 server.py --port 8080 --phone "$PHONE_IP"
elif [ "$OPT" = "3" ]; then
    read -p "Caminho do arquivo .gguf: " MODEL_PATH
    python3 server.py --port 8080 --model "$MODEL_PATH"
else
    python3 server.py --port 8080
fi
