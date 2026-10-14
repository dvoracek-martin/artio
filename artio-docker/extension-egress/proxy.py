"""Allowlisted HTTPS CONNECT gateway for isolated Artio extension containers."""

import ipaddress
import os
import select
import socket
import socketserver
import threading

MAX_HEADER = 8192
CONNECT_TIMEOUT = 5
IDLE_TIMEOUT = 60
MAX_CONNECTIONS = 64
ALLOWED_HOSTS = frozenset(filter(None, os.environ.get("ARTIO_EGRESS_ALLOWED_HOSTS", "").lower().split(",")))
SLOTS = threading.BoundedSemaphore(MAX_CONNECTIONS)


def resolve_public_addresses(host, port=443):
    try:
        answers = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError:
        return []
    public = []
    for family, socktype, proto, _canonname, address in answers:
        try:
            ip = ipaddress.ip_address(address[0])
        except ValueError:
            continue
        if ip.is_global:
            public.append((family, socktype, proto, address))
    return public


def valid_target(authority):
    if not isinstance(authority, str) or ":" not in authority:
        return None
    host, port_text = authority.rsplit(":", 1)
    host = host.rstrip(".").lower()
    if not host or port_text != "443" or host not in ALLOWED_HOSTS:
        return None
    return host


class ProxyHandler(socketserver.BaseRequestHandler):
    def handle(self):
        if not SLOTS.acquire(blocking=False):
            self.request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n")
            return
        try:
            self._handle_connect()
        finally:
            SLOTS.release()

    def _handle_connect(self):
        self.request.settimeout(3)
        header = bytearray()
        while b"\r\n\r\n" not in header and len(header) <= MAX_HEADER:
            chunk = self.request.recv(1024)
            if not chunk:
                return
            header.extend(chunk)
        if len(header) > MAX_HEADER:
            return self._reply(431, "Request Header Fields Too Large")
        try:
            first_line = bytes(header).split(b"\r\n", 1)[0].decode("ascii")
            method, authority, version = first_line.split(" ")
        except (UnicodeDecodeError, ValueError):
            return self._reply(400, "Bad Request")
        if method != "CONNECT" or version not in ("HTTP/1.0", "HTTP/1.1"):
            return self._reply(405, "Method Not Allowed")
        host = valid_target(authority)
        if host is None:
            return self._reply(403, "Destination Not Allowed")
        upstream = None
        for family, socktype, proto, address in resolve_public_addresses(host):
            upstream = socket.socket(family, socktype, proto)
            upstream.settimeout(CONNECT_TIMEOUT)
            try:
                upstream.connect(address)
                break
            except OSError:
                upstream.close()
                upstream = None
        if upstream is None:
            return self._reply(502, "Upstream Unavailable")
        try:
            self.request.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            self._tunnel(upstream)
        finally:
            upstream.close()

    def _tunnel(self, upstream):
        upstream.settimeout(IDLE_TIMEOUT)
        self.request.settimeout(IDLE_TIMEOUT)
        peers = (self.request, upstream)
        while True:
            readable, _, exceptional = select.select(peers, (), peers, IDLE_TIMEOUT)
            if exceptional or not readable:
                return
            for source in readable:
                target = upstream if source is self.request else self.request
                try:
                    data = source.recv(65536)
                    if not data:
                        return
                    target.sendall(data)
                except (OSError, BlockingIOError):
                    return

    def _reply(self, status, reason):
        self.request.sendall((f"HTTP/1.1 {status} {reason}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").encode("ascii"))


class ThreadedProxyServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = MAX_CONNECTIONS


if __name__ == "__main__":
    ThreadedProxyServer(("0.0.0.0", int(os.environ.get("ARTIO_EGRESS_PORT", "3128"))), ProxyHandler).serve_forever()
