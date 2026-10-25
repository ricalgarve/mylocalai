#!/bin/bash
# Script de teste rápido da API do PocketPal Server

HOST="${1:-localhost:8080}"

echo "========================================="
echo "Testando PocketPal Server em: http://$HOST"
echo "========================================="

echo -e "\n1. Verificando Healthcheck:"
curl -s "http://$HOST/health" | jq . || curl -s "http://$HOST/health"

echo -e "\n\n2. Listando Modelos (/v1/models):"
curl -s "http://$HOST/v1/models" | jq . || curl -s "http://$HOST/v1/models"

echo -e "\n\n3. Testando Chat Completion (Streaming):"
curl -N -X POST "http://$HOST/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "user", "content": "Diga olá em uma frase."}
    ],
    "temperature": 0.7,
    "stream": true
  }'

echo -e "\n\nTeste concluído!"
