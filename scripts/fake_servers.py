"""Stand-ins for Maven Central, the family's Maven repository and the GitHub API, for
scripts/test-release.sh, over HTTPS on localhost only. Everything is served read-only from ROOT:

  /central/<path>   Maven Central: a file, or a directory (200, as Central lists one)
  /site/<path>      the family's repository on GitHub Pages: a file only (a directory is 404)
  /moved/<path>     a site that has moved: 301 to /site/<path>
  /api/<path>       the GitHub API: the JSON file ROOT/api/<path>
  /download/<path>  a release asset: the file ROOT/download/<path>

Every request is logged as "<method> <path> auth=<none|present>", never a credential itself.

  python3 -I fake_servers.py ROOT PORT CERT KEY LOG
"""

import http.server
import os
import ssl
import sys

root, port, cert, key, log = sys.argv[1:6]


def local(path):
    full = os.path.normpath(os.path.join(root, path.split("?", 1)[0].lstrip("/")))
    if full != os.path.normpath(root) and not full.startswith(os.path.normpath(root) + os.sep):
        raise ValueError(path)
    return full


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def answer(self, code, body=b"", headers=()):
        self.send_response(code)
        for k, v in headers:
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        with open(log, "a") as f:
            f.write(f"GET {self.path} auth={'present' if self.headers.get('Authorization') else 'none'}\n")
        target = local(self.path)
        if self.path.startswith("/moved/"):
            return self.answer(301, headers=[("Location", "/site/" + self.path[len("/moved/"):])])
        if self.path.startswith("/central/") and os.path.isdir(target):
            return self.answer(200, b"<html>listing</html>")
        if os.path.isfile(target):
            with open(target, "rb") as f:
                return self.answer(200, f.read())
        self.answer(404)

    def do_PUT(self):
        with open(log, "a") as f:
            f.write(f"PUT {self.path}\n")
        self.answer(405)

    do_POST = do_PUT


server = http.server.ThreadingHTTPServer(("localhost", int(port)), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(cert, key)
server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
