#!/bin/bash
# Script de teste rápido da API do MyLocal.AI Server

HOST="${1:-localhost:8080}"
TOKEN="${2}"

AUTH_HEADER=""
if [ -n "$TOKEN" ]; then
  AUTH_HEADER="-H Authorization: Bearer $TOKEN"
fi

echo "========================================="
echo "Testando MyLocal.AI Server em: http://$HOST"
if [ -n "$TOKEN" ]; then
  echo "Autenticação: Bearer $TOKEN"
fi
echo "========================================="

echo -e "\n1. Verificando Healthcheck:"
curl -s $AUTH_HEADER "http://$HOST/health" | jq . || curl -s $AUTH_HEADER "http://$HOST/health"

echo -e "\n\n2. Listando Modelos (/v1/models):"
curl -s $AUTH_HEADER "http://$HOST/v1/models" | jq . || curl -s $AUTH_HEADER "http://$HOST/v1/models"

echo -e "\n\n3. Testando Chat Completion (Streaming):"
curl -N -X POST "http://$HOST/v1/chat/completions" \
  -H "Content-Type: application/json" \
  $AUTH_HEADER \
  -d '{
    "messages": [
      {"role": "user", "content": "Diga olá em uma frase."}
    ],
    "temperature": 0.7,
    "stream": true
  }'

echo -e "\n\nTeste concluído!"
