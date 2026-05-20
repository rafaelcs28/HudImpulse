#!/usr/bin/env python3
"""
HudImpulse – servidor de logs remoto.

USO LOCAL (casa, mesma rede WiFi):
    python3 tools/log_server.py
    → anote o IP do Mac Mini (ex: 192.168.1.10) e coloque em local.properties

USO REMOTO (carro com dados móveis):
    1. Deixe o servidor rodando (passo acima)
    2. Em outro terminal: ssh -R 80:localhost:9876 serveo.net
    3. O serveo vai exibir a URL pública (ex: https://abc123.serveo.net)
    4. Coloque essa URL em local.properties: log.server.url=https://abc123.serveo.net
    5. Recompile e instale o app
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import datetime, sys, socket

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9876

COLORS = {
    'E': '\033[91m',   # vermelho
    'W': '\033[93m',   # amarelo
    'I': '\033[92m',   # verde
    'D': '\033[94m',   # azul
    'V': '\033[37m',   # cinza
}
RESET = '\033[0m'

class LogHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body   = self.rfile.read(length).decode('utf-8', errors='replace').strip()
        ts     = datetime.datetime.now().strftime('%H:%M:%S.%f')[:-3]
        level  = body[1] if len(body) > 1 else 'V'
        color  = COLORS.get(level, RESET)
        print(f"{color}[{ts}] {body}{RESET}", flush=True)
        self.send_response(200)
        self.end_headers()

    def log_message(self, *args):
        pass  # suprime log de cada requisição HTTP


def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return '127.0.0.1'


if __name__ == '__main__':
    ip = get_local_ip()
    print(f"\n{'='*60}")
    print(f"  HudImpulse Log Server")
    print(f"{'='*60}")
    print(f"  Local (mesma WiFi): http://{ip}:{PORT}")
    print(f"  Remoto:             ssh -R 80:localhost:{PORT} serveo.net")
    print(f"\n  Adicione em local.properties:")
    print(f"  log.server.url=http://{ip}:{PORT}")
    print(f"{'='*60}\n")
    HTTPServer(('0.0.0.0', PORT), LogHandler).serve_forever()
