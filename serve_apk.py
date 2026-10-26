#!/usr/bin/env python3
"""
==============================================================================
 MyLocal.AI - Servidor de Compartilhamento e Download do APK via Wi-Fi
==============================================================================
Inicia um servidor local na rede Wi-Fi para você baixar e instalar o APK
em qualquer celular, tablet ou outro dispositivo sem precisar de cabo USB.
==============================================================================
"""

import os
import sys
import socket
import argparse
from http.server import HTTPServer, BaseHTTPRequestHandler

try:
    import qrcode
    HAS_QRCODE = True
except ImportError:
    HAS_QRCODE = False

def get_local_ip():
    """Descobre o IP real da máquina na rede local Wi-Fi / Ethernet."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # Não precisa ser alcançável, apenas determina a interface de rota padrão
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = "127.0.0.1"
    finally:
        s.close()
    return ip

def find_apk_file():
    """Localiza o arquivo APK mais recente no projeto."""
    candidates = [
        "MyLocalAI.apk",
        "app/build/outputs/apk/release/app-release.apk",
        "app/build/outputs/apk/debug/app-debug.apk",
        "PocketPalServer.apk"
    ]
    base_dir = os.path.dirname(os.path.abspath(__file__))
    for rel_path in candidates:
        full_path = os.path.join(base_dir, rel_path)
        if os.path.isfile(full_path):
            return full_path
    return None

class ApkDownloadHandler(BaseHTTPRequestHandler):

    def do_HEAD(self):
        apk_path = find_apk_file()
        if not apk_path:
            self.send_error(404, "Arquivo APK não encontrado no servidor.")
            return

        file_size = os.path.getsize(apk_path)
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(file_size))
        self.send_header("Content-Disposition", 'attachment; filename="MyLocalAI.apk"')
        self.end_headers()

    def do_GET(self):
        apk_path = find_apk_file()
        filename = "MyLocalAI.apk"
        file_size = os.path.getsize(apk_path) if apk_path else 0
        size_mb = f"{file_size / (1024 * 1024):.1f} MB" if file_size else "Desconhecido"

        # Rota de download direto do arquivo APK
        if self.path in ["/download", "/MyLocalAI.apk", "/app-release.apk", "/PocketPalServer.apk"]:
            if not apk_path:
                self.send_error(404, "Arquivo APK não encontrado.")
                return

            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(file_size))
            self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()

            with open(apk_path, "rb") as f:
                while True:
                    chunk = f.read(64 * 1024)
                    if not chunk:
                        break
                    try:
                        self.wfile.write(chunk)
                    except BrokenPipeError:
                        break
            return

        # Página Web de Apresentação e Download para o Celular
        local_ip = get_local_ip()
        download_url = f"http://{local_ip}:{self.server.server_port}/download"

        html_content = f"""<!DOCTYPE html>
<html lang="pt-BR">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Baixar MyLocal.AI - APK Android</title>
    <style>
        :root {{
            --bg: #090d16;
            --card: #131c2e;
            --accent: #6366f1;
            --accent-hover: #4f46e5;
            --success: #10b981;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --border: #1e293b;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }}
        body {{
            background: var(--bg);
            color: var(--text-main);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }}
        .container {{
            background: var(--card);
            border: 1px solid var(--border);
            border-radius: 20px;
            max-width: 440px;
            width: 100%;
            padding: 32px 24px;
            text-align: center;
            box-shadow: 0 20px 40px rgba(0, 0, 0, 0.5);
        }}
        .icon-circle {{
            width: 96px;
            height: 96px;
            margin: 0 auto 18px;
            border-radius: 24px;
            background: linear-gradient(135deg, #4f46e5, #06b6d4);
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 46px;
            box-shadow: 0 10px 25px rgba(99, 102, 241, 0.4);
        }}
        h1 {{ font-size: 24px; font-weight: 800; margin-bottom: 6px; letter-spacing: -0.5px; }}
        .badge {{
            display: inline-block;
            background: rgba(16, 185, 129, 0.15);
            color: var(--success);
            border: 1px solid var(--success);
            font-size: 11px;
            font-weight: 700;
            padding: 3px 10px;
            border-radius: 20px;
            margin-bottom: 14px;
        }}
        p.desc {{
            color: var(--text-muted);
            font-size: 14px;
            line-height: 1.5;
            margin-bottom: 24px;
        }}
        .info-grid {{
            background: rgba(15, 23, 42, 0.6);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 12px;
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 8px;
            margin-bottom: 24px;
            text-align: left;
            font-size: 13px;
        }}
        .info-label {{ color: var(--text-muted); font-size: 11px; }}
        .info-value {{ font-weight: 600; color: var(--text-main); }}
        .btn-download {{
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 10px;
            background: linear-gradient(135deg, var(--accent), #4338ca);
            color: white;
            text-decoration: none;
            padding: 16px 24px;
            border-radius: 14px;
            font-size: 16px;
            font-weight: 700;
            transition: all 0.2s ease;
            box-shadow: 0 8px 20px rgba(99, 102, 241, 0.35);
        }}
        .btn-download:hover, .btn-download:active {{
            background: var(--accent-hover);
            transform: translateY(-2px);
            box-shadow: 0 12px 25px rgba(99, 102, 241, 0.5);
        }}
        .install-guide {{
            margin-top: 24px;
            padding-top: 20px;
            border-top: 1px solid var(--border);
            text-align: left;
            font-size: 12px;
            color: var(--text-muted);
            line-height: 1.6;
        }}
        .install-guide b {{ color: var(--text-main); }}
    </style>
</head>
<body>
    <div class="container">
        <div class="icon-circle">🤖</div>
        <h1>MyLocal.AI</h1>
        <div class="badge">ARM64-v8a • APK Oficial</div>
        <p class="desc">Servidor de IA Local para Android com API OpenAI integrada e aceleração de hardware.</p>

        <div class="info-grid">
            <div>
                <div class="info-label">Tamanho do Arquivo</div>
                <div class="info-value">{size_mb}</div>
            </div>
            <div>
                <div class="info-label">Versão</div>
                <div class="info-value">1.0.0 (Release)</div>
            </div>
            <div>
                <div class="info-label">Compatibilidade</div>
                <div class="info-value">Android 8.0+ (ARM64)</div>
            </div>
            <div>
                <div class="info-label">Assinatura</div>
                <div class="info-value">Pronto p/ instalar</div>
            </div>
        </div>

        <a href="{download_url}" class="btn-download">
            <span>📥</span> Baixar MyLocalAI.apk
        </a>

        <div class="install-guide">
            <b>💡 Como instalar no seu aparelho:</b>
            <ol style="margin-left: 18px; margin-top: 6px;">
                <li>Toque no botão acima para iniciar o download.</li>
                <li>Ao abrir o arquivo, se o Android solicitar, ative a opção <b>"Permitir desta fonte"</b>.</li>
                <li>Toque em <b>Instalar</b> e abra o app!</li>
            </ol>
        </div>
    </div>
</body>
</html>"""

        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(html_content.encode("utf-8"))))
        self.end_headers()
        self.wfile.write(html_content.encode("utf-8"))

    def log_message(self, format, *args):
        # Mostra downloads no terminal com clareza
        print(f"[{self.log_date_time_string()}] {args[0]} - {args[1]}")

def main():
    parser = argparse.ArgumentParser(description="Servidor de Compartilhamento do APK MyLocal.AI via Wi-Fi")
    parser.add_argument("--port", type=int, default=8000, help="Porta HTTP (padrão: 8000)")
    args = parser.parse_args()

    apk_path = find_apk_file()
    if not apk_path:
        print("[ERRO] Arquivo APK não encontrado! Compile o projeto primeiro ou coloque o MyLocalAI.apk nesta pasta.")
        sys.exit(1)

    file_size_mb = os.path.getsize(apk_path) / (1024 * 1024)
    local_ip = get_local_ip()
    url = f"http://{local_ip}:{args.port}"

    print("==============================================================================")
    print("           📲  MYLOCAL.AI - SERVIDOR DE DOWNLOAD DO APK VIA WI-FI            ")
    print("==============================================================================")
    print(f" [*] Arquivo APK : {os.path.basename(apk_path)} ({file_size_mb:.1f} MB)")
    print(f" [*] Endereço    : {url}")
    print("==============================================================================")
    print("\nNo seu celular (conectado no mesmo Wi-Fi), acesse:")
    print(f"👉 \033[1;32m{url}\033[0m\n")

    if HAS_QRCODE:
        print("Ou aponte a câmera do celular para o QR Code abaixo:")
        qr = qrcode.QRCode(border=1)
        qr.add_data(url)
        qr.print_ascii(invert=True)
        print("==============================================================================")

    print(f"Servidor ativo na porta {args.port}. Pressione Ctrl + C para encerrar.\n")

    server = HTTPServer(("0.0.0.0", args.port), ApkDownloadHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[*] Servidor encerrado.")
        server.server_close()

if __name__ == "__main__":
    main()
