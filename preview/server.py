import http.server, socketserver, os
os.chdir(os.path.dirname(__file__))
with socketserver.TCPServer(("", 8765), http.server.SimpleHTTPRequestHandler) as h:
    h.serve_forever()
