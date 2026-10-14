import unittest
import socket
import threading
from unittest.mock import patch

import proxy


class EgressPolicyTests(unittest.TestCase):
    def setUp(self):
        self.previous = proxy.ALLOWED_HOSTS
        proxy.ALLOWED_HOSTS = frozenset({"api.stripe.com", "api.brevo.com"})

    def tearDown(self):
        proxy.ALLOWED_HOSTS = self.previous

    def test_allows_only_exact_provider_host_on_tls_port(self):
        self.assertEqual("api.stripe.com", proxy.valid_target("api.stripe.com:443"))
        self.assertEqual("api.brevo.com", proxy.valid_target("API.BREVO.COM:443"))
        self.assertIsNone(proxy.valid_target("attacker.example:443"))
        self.assertIsNone(proxy.valid_target("api.stripe.com:80"))
        self.assertIsNone(proxy.valid_target("api.stripe.com.evil.test:443"))

    @patch.object(proxy.socket, "getaddrinfo")
    def test_dns_results_cannot_reach_private_or_loopback_addresses(self, getaddrinfo):
        getaddrinfo.return_value = [
            (proxy.socket.AF_INET, proxy.socket.SOCK_STREAM, 6, "", ("127.0.0.1", 443)),
            (proxy.socket.AF_INET, proxy.socket.SOCK_STREAM, 6, "", ("10.0.0.2", 443)),
        ]
        self.assertEqual([], proxy.resolve_public_addresses("api.stripe.com"))

    def test_connection_limit_rejects_before_spawning_another_worker(self):
        old_slots = proxy.SLOTS
        proxy.SLOTS = threading.BoundedSemaphore(0)
        server = proxy.ThreadedProxyServer(("127.0.0.1", 0), proxy.ProxyHandler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            with socket.create_connection(server.server_address, timeout=2) as client:
                response = client.recv(256)
            self.assertIn(b"503 Service Unavailable", response)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=2)
            proxy.SLOTS = old_slots


if __name__ == "__main__":
    unittest.main()
