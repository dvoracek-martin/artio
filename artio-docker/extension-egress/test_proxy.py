import unittest
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


if __name__ == "__main__":
    unittest.main()
