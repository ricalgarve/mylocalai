#!/usr/bin/env python3
"""
==============================================================================
 MyLocal.AI - Servidor Python Local & Gateway OpenAI
==============================================================================
Funcionalidades:
 1. Servidor HTTP nativo em Python (Zero dependências obrigatórias).
 2. Interface Web de Chat moderna integrada (acesse http://localhost:8080).
 3. API 100% compatível com OpenAI (/v1/chat/completions, /v1/models, /health).
 4. Modo Gateway/Proxy: Conecta ao celular Android rodando o MyLocal.AI.
 5. Modo PC Local: Roda modelos .gguf direto no PC via llama-cpp-python.
==============================================================================
"""

import sys
import os
import json
import time
import argparse
import urllib.request
import urllib.error
from http.server import HTTPServer, BaseHTTPRequestHandler
import socketserver

# Tentativa de importação opcional de llama_cpp para inferência local no PC
try:
    from llama_cpp import Llama
    HAS_LLAMA_CPP = True
except ImportError:
    HAS_LLAMA_CPP = False

# Estado global do servidor
SERVER_CONFIG = {
    "phone_url": "",      # Ex: http://192.168.1.100:8080
    "model_path": "",     # Caminho para arquivo .gguf local
    "model_name": "MyLocal.AI",
    "llama_instance": None,
    "port": 8080,
    "host": "0.0.0.0"
}

WEB_CHAT_HTML = """<!DOCTYPE html>
<html lang="pt-BR">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>MyLocal.AI - Web Chat & API Gateway</title>
    <style>
        :root {
            --bg-dark: #0f172a;
            --bg-card: #1e293b;
            --bg-bubble-user: #3b82f6;
            --bg-bubble-ai: #334155;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --accent: #10b981;
            --border: #334155;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
        body { background-color: var(--bg-dark); color: var(--text-main); height: 100vh; display: flex; flex-direction: column; }
        header { background: var(--bg-card); padding: 14px 20px; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--border); }
        .logo-box { display: flex; align-items: center; gap: 10px; }
        .logo-badge { background: #6366f1; color: white; padding: 4px 10px; border-radius: 8px; font-weight: bold; font-size: 14px; }
        .status-badge { font-size: 12px; padding: 4px 8px; border-radius: 6px; background: rgba(16, 185, 129, 0.2); color: var(--accent); border: 1px solid var(--accent); }
        #chat-container { flex: 1; overflow-y: auto; padding: 20px; display: flex; flex-direction: column; gap: 14px; }
        .msg { max-width: 80%; padding: 12px 16px; border-radius: 12px; line-height: 1.5; font-size: 15px; word-break: break-word; white-space: pre-wrap; }
        .msg-user { align-self: flex-end; background: var(--bg-bubble-user); color: white; border-bottom-right-radius: 2px; }
        .msg-ai { align-self: flex-start; background: var(--bg-bubble-ai); border-bottom-left-radius: 2px; border: 1px solid var(--border); }
        .input-bar { background: var(--bg-card); padding: 14px 20px; display: flex; gap: 10px; border-top: 1px solid var(--border); }
        input[type="text"] { flex: 1; background: #0f172a; border: 1px solid var(--border); color: white; padding: 12px 16px; border-radius: 8px; outline: none; font-size: 15px; }
        input[type="text"]:focus { border-color: #6366f1; }
        button { background: #6366f1; color: white; border: none; padding: 0 24px; border-radius: 8px; font-weight: bold; cursor: pointer; transition: background 0.2s; }
        button:hover { background: #4f46e5; }
        button:disabled { background: #475569; cursor: not-allowed; }
        .config-bar { background: #162032; padding: 8px 20px; font-size: 12px; color: var(--text-muted); display: flex; gap: 20px; border-bottom: 1px solid var(--border); }
    </style>
</head>
<body>
    <header>
        <div class="logo-box">
            <span class="logo-badge">🤖 MyLocal.AI</span>
            <span style="font-size: 14px; color: var(--text-muted);">Servidor Python &amp; Gateway OpenAI</span>
        </div>
        <div class="status-badge" id="backend-status">Carregando status...</div>
    </header>
    <div class="config-bar">
        <span>Endpoint API: <b>http://localhost:8080/v1</b></span>
        <span id="target-info">Alvo: Detectando...</span>
    </div>
    <div id="chat-container">
        <div class="msg msg-ai">Olá! Eu sou o <b>MyLocal.AI</b> rodando via servidor Python. Digite uma mensagem abaixo para iniciar a conversa!</div>
    </div>
    <form class="input-bar" id="chat-form" onsubmit="sendMessage(event)">
        <input type="text" id="user-input" placeholder="Digite sua mensagem para a IA..." autocomplete="off" autofocus />
        <button type="submit" id="send-btn">Enviar</button>
    </form>

    <script>
        const chatContainer = document.getElementById('chat-container');
        const userInput = document.getElementById('user-input');
        const sendBtn = document.getElementById('send-btn');
        let chatHistory = [];

        async function fetchHealth() {
            try {
                const res = await fetch('/health');
                const data = await res.json();
                document.getElementById('backend-status').innerText = 'Online (' + data.mode + ')';
                document.getElementById('target-info').innerText = 'Modelo: ' + (data.current_model || 'none');
            } catch (e) {
                document.getElementById('backend-status').innerText = 'Offline';
            }
        }
        fetchHealth();

        async function sendMessage(e) {
            e.preventDefault();
            const text = userInput.value.trim();
            if (!text) return;

            // Renderiza mensagem do usuário
            appendMessage(text, 'user');
            userInput.value = '';
            userInput.disabled = true;
            sendBtn.disabled = true;

            chatHistory.push({ role: "user", content: text });

            // Cria balão do assistente vazio
            const aiMsgDiv = appendMessage('', 'ai');

            try {
                const res = await fetch('/v1/chat/completions', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        messages: chatHistory,
                        stream: true,
                        temperature: 0.7
                    })
                });

                if (!res.ok) {
                    const errJson = await res.json().catch(() => ({ error: 'Falha na requisição' }));
                    aiMsgDiv.innerText = '[Erro: ' + (errJson.error || res.statusText) + ']';
                    return;
                }

                const reader = res.body.getReader();
                const decoder = new TextDecoder("utf-8");
                let fullAiText = '';

                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;

                    const chunk = decoder.decode(value);
                    const lines = chunk.split('\\n');

                    for (const line of lines) {
                        if (line.startsWith('data: ')) {
                            const dataStr = line.slice(6).trim();
                            if (dataStr === '[DONE]') break;
                            try {
                                const parsed = JSON.parse(dataStr);
                                const delta = parsed.choices?.[0]?.delta?.content || '';
                                fullAiText += delta;
                                aiMsgDiv.innerText = fullAiText;
                                chatContainer.scrollTop = chatContainer.scrollHeight;
                            } catch (_) {}
                        }
                    }
                }

                chatHistory.push({ role: "assistant", content: fullAiText });
            } catch (err) {
                aiMsgDiv.innerText = '[Erro de conexão: ' + err.message + ']';
            } finally {
                userInput.disabled = false;
                sendBtn.disabled = false;
                userInput.focus();
            }
        }

        function appendMessage(text, sender) {
            const div = document.createElement('div');
            div.className = 'msg msg-' + sender;
            div.innerText = text;
            chatContainer.appendChild(div);
            chatContainer.scrollTop = chatContainer.scrollHeight;
            return div;
        }
    </script>
</body>
</html>
"""

class OpenAiHttpHandler(BaseHTTPRequestHandler):

    def send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With")

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_cors_headers()
        self.end_headers()

    def do_GET(self):
        if self.path == "/" or self.path == "/chat":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(WEB_CHAT_HTML.encode("utf-8"))
            return

        elif self.path == "/health":
            mode_desc = "Local GGUF (llama-cpp)" if SERVER_CONFIG["llama_instance"] else (
                f"Gateway Celular ({SERVER_CONFIG['phone_url']})" if SERVER_CONFIG["phone_url"] else "Standby (Sem modelo)"
            )
            data = {
                "status": "online",
                "app": "MyLocal.AI Python Server",
                "mode": mode_desc,
                "current_model": SERVER_CONFIG["model_name"],
                "has_llama_cpp": HAS_LLAMA_CPP
            }
            self.send_json(200, data)
            return

        elif self.path == "/v1/models":
            data = {
                "object": "list",
                "data": [
                    {
                        "id": SERVER_CONFIG["model_name"],
                        "object": "model",
                        "created": int(time.time()),
                        "owned_by": "local"
                    }
                ]
            }
            self.send_json(200, data)
            return

        else:
            self.send_json(404, {"error": "Endpoint não encontrado"})

    def do_POST(self):
        if self.path in ["/v1/chat/completions", "/chat/completions"]:
            self.handle_chat_completions()
        elif self.path in ["/v1/completions", "/completions"]:
            self.handle_completions()
        else:
            self.send_json(404, {"error": "Endpoint não encontrado"})

    def handle_chat_completions(self):
        content_length = int(self.headers.get("Content-Length", 0))
        raw_body = self.rfile.read(content_length).decode("utf-8")

        try:
            req_data = json.loads(raw_body)
        except Exception as e:
            self.send_json(400, {"error": f"JSON inválido: {str(e)}"})
            return

        messages = req_data.get("messages", [])
        stream = req_data.get("stream", False)
        temperature = req_data.get("temperature", 0.7)
        max_tokens = req_data.get("max_tokens", 512)

        # 1. Se configurado para proxy/gateway para o celular Android
        if SERVER_CONFIG["phone_url"]:
            self.proxy_to_phone(raw_body, stream)
            return

        # 2. Se configurado com modelo GGUF local usando llama-cpp-python
        if SERVER_CONFIG["llama_instance"]:
            self.infer_local_llama(messages, stream, temperature, max_tokens)
            return

        # 3. Fallback inteligente: simulação informativa se nenhum backend estiver ativo
        self.handle_fallback_demo(messages, stream)

    def proxy_to_phone(self, raw_body, stream):
        target_url = f"{SERVER_CONFIG['phone_url']}/v1/chat/completions"
        try:
            req = urllib.request.Request(
                target_url,
                data=raw_body.encode("utf-8"),
                headers={"Content-Type": "application/json"}
            )
            with urllib.request.urlopen(req, timeout=120) as resp:
                self.send_response(resp.status)
                self.send_cors_headers()
                for header, val in resp.getheaders():
                    if header.lower() in ["content-type", "cache-control", "connection"]:
                        self.send_header(header, val)
                self.end_headers()

                # Streaming chunk a chunk
                while True:
                    chunk = resp.read(1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
        except urllib.error.URLError as e:
            self.send_json(502, {"error": f"Falha ao conectar com o celular em {target_url}: {str(e)}"})

    def infer_local_llama(self, messages, stream, temperature, max_tokens):
        llm = SERVER_CONFIG["llama_instance"]
        if stream:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.send_cors_headers()
            self.end_headers()

            stream_res = llm.create_chat_completion(
                messages=messages,
                temperature=temperature,
                max_tokens=max_tokens,
                stream=True
            )
            for chunk in stream_res:
                sse_line = f"data: {json.dumps(chunk)}\n\n"
                self.wfile.write(sse_line.encode("utf-8"))
                self.wfile.flush()

            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        else:
            response = llm.create_chat_completion(
                messages=messages,
                temperature=temperature,
                max_tokens=max_tokens,
                stream=False
            )
            self.send_json(200, response)

    def handle_fallback_demo(self, messages, stream):
        last_user_msg = messages[-1]["content"] if messages else "Olá"
        answer = (
            f"[MyLocal.AI Servidor Python Ativo]\n"
            f"Recebi sua mensagem: '{last_user_msg}'.\n\n"
            f"Para ativar inferência real:\n"
            f" 1. Conecte ao seu celular Android: python3 server.py --phone http://<IP-DO-CELULAR>:8080\n"
            f" 2. Ou rode um modelo .gguf local no PC: python3 server.py --model caminho/do/modelo.gguf"
        )
        if stream:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_cors_headers()
            self.end_headers()

            # Emite token a token
            words = answer.split(" ")
            for w in words:
                chunk = {
                    "id": f"chatcmpl-{int(time.time())}",
                    "object": "chat.completion.chunk",
                    "created": int(time.time()),
                    "model": SERVER_CONFIG["model_name"],
                    "choices": [{"delta": {"content": w + " "}, "index": 0, "finish_reason": None}]
                }
                self.wfile.write(f"data: {json.dumps(chunk)}\n\n".encode("utf-8"))
                self.wfile.flush()
                time.sleep(0.04)

            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        else:
            resp = {
                "id": f"chatcmpl-{int(time.time())}",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": SERVER_CONFIG["model_name"],
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": answer},
                    "finish_reason": "stop"
                }]
            }
            self.send_json(200, resp)

    def send_json(self, status_code, data):
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_cors_headers()
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False, indent=2).encode("utf-8"))

    def log_message(self, format, *args):
        # Log simplificado e limpo no terminal
        print(f"[{time.strftime('%H:%M:%S')}] {args[0]} {args[1]}")

class ThreadedHTTPServer(socketserver.ThreadingMixIn, HTTPServer):
    daemon_threads = True

def main():
    parser = argparse.ArgumentParser(description="MyLocal.AI - Servidor Python & Gateway OpenAI")
    parser.add_argument("--port", type=int, default=8080, help="Porta HTTP (padrão: 8080)")
    parser.add_argument("--host", type=str, default="0.0.0.0", help="Host/Interface (padrão: 0.0.0.0)")
    parser.add_argument("--phone", type=str, default="", help="Endereço do celular Android (ex: http://192.168.1.100:8080)")
    parser.add_argument("--model", type=str, default="", help="Caminho para arquivo .gguf para rodar no PC com llama-cpp")
    parser.add_argument("--threads", type=int, default=4, help="Número de threads CPU para modelo local")
    parser.add_argument("--ctx", type=int, default=2048, help="Tamanho da janela de contexto")

    args = parser.parse_args()

    SERVER_CONFIG["port"] = args.port
    SERVER_CONFIG["host"] = args.host

    if args.phone:
        phone_url = args.phone if args.phone.startswith("http") else f"http://{args.phone}"
        SERVER_CONFIG["phone_url"] = phone_url.rstrip("/")
        SERVER_CONFIG["model_name"] = f"phone-gateway ({SERVER_CONFIG['phone_url']})"
        print(f"[*] Modo Gateway ativado: Encaminhando requisições para o celular em {SERVER_CONFIG['phone_url']}")

    elif args.model:
        if not HAS_LLAMA_CPP:
            print("[AVISO] Para carregar modelos .gguf diretamente no PC com Python, instale o llama-cpp-python:")
            print("        pip install llama-cpp-python")
            print("        Iniciando servidor em modo Standby/Demo.")
        elif not os.path.exists(args.model):
            print(f"[ERRO] Arquivo de modelo não encontrado: {args.model}")
            sys.exit(1)
        else:
            print(f"[*] Carregando modelo local no PC: {args.model} ({args.threads} threads)...")
            SERVER_CONFIG["llama_instance"] = Llama(
                model_path=args.model,
                n_threads=args.threads,
                n_ctx=args.ctx
            )
            SERVER_CONFIG["model_name"] = os.path.basename(args.model)
            print("[OK] Modelo carregado com sucesso na memória do PC!")

    server = ThreadedHTTPServer((args.host, args.port), OpenAiHttpHandler)
    print("==============================================================================")
    print("                      MYLOCAL.AI - SERVIDOR PYTHON ATIVO                      ")
    print("==============================================================================")
    print(f" 🌐 Interface Web Chat : http://localhost:{args.port}/")
    print(f" 📡 Endpoint OpenAI    : http://localhost:{args.port}/v1/chat/completions")
    print(f" 🩺 Healthcheck        : http://localhost:{args.port}/health")
    print("==============================================================================")
    print(" Pressione Ctrl + C para encerrar o servidor a qualquer momento.\n")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[*] Servidor encerrado.")
        server.server_close()

if __name__ == "__main__":
    main()
