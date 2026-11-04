import unittest

from refresh_ikev2_directory import (
    HOST,
    INDEX,
    build_directory,
    extract_country_servers,
    extract_index_pages,
    is_provider_url,
)


class Ikev2DirectoryScraperTests(unittest.TestCase):
    def test_provider_origin_rejects_userinfo_ports_and_external_hosts(self):
        self.assertTrue(is_provider_url(f"https://{HOST}/free-ikev2"))
        self.assertTrue(is_provider_url(f"https://{HOST}:443/free-ikev2"))
        self.assertFalse(is_provider_url(f"https://attacker@{HOST}/free-ikev2"))
        self.assertFalse(is_provider_url("https://www.vpnjantit.com.evil.test/free-ikev2"))
        self.assertFalse(is_provider_url("http://www.vpnjantit.com/free-ikev2"))
        self.assertFalse(is_provider_url(f"https://{HOST}:8443/free-ikev2"))
        self.assertFalse(is_provider_url(f"https://{HOST}/free-ikev2#fragment"))

    def test_index_parser_ignores_external_and_unexpected_paths(self):
        html = f"""
        <a href="/free-ikev2-turkey">IKEV2 MSCHPv2 Turkey</a>
        <a href="https://evil.test/free-ikev2-germany">Germany</a>
        <a href="/free-ikev2/other">Other</a>
        <a href="https://user@{HOST}/free-ikev2-france">France</a>
        """
        pages = extract_index_pages(html)
        self.assertEqual(pages, [("Turkey", f"https://{HOST}/free-ikev2-turkey")])

    def test_country_parser_matches_gateway_to_provider_account_link(self):
        page = f"https://{HOST}/free-ikev2-turkey"
        html = f"""
        <p>Gateway: tr01.vpnjantit.com</p>
        <p>Status: Available</p>
        <a href="/create-free-account?server=tr01">Create account</a>
        """
        servers = extract_country_servers("Turkey", page, html)
        self.assertEqual(len(servers), 1)
        self.assertEqual(servers[0]["host"], "tr01.vpnjantit.com")
        self.assertEqual(servers[0]["accountUrl"], f"https://{HOST}/create-free-account?server=tr01")
        self.assertTrue(servers[0]["available"])

    def test_unavailable_label_is_not_reported_as_available(self):
        page = f"https://{HOST}/free-ikev2-turkey"
        html = """
        <p>Gateway: tr01.vpnjantit.com</p>
        <p>Status: Not Available</p>
        <a href="/create-free-account?server=tr01">Create account</a>
        """
        servers = extract_country_servers("Turkey", page, html)
        self.assertEqual(len(servers), 1)
        self.assertFalse(servers[0]["available"])

    def test_account_link_on_external_origin_is_rejected(self):
        page = f"https://{HOST}/free-ikev2-turkey"
        html = """
        <p>Gateway: tr01.vpnjantit.com</p>
        <a href="https://evil.test/create-free-account?server=tr01">Create account</a>
        """
        self.assertEqual(extract_country_servers("Turkey", page, html), [])

    def test_partial_scrape_fails_instead_of_publishing_incomplete_snapshot(self):
        index = f'<a href="/free-ikev2-turkey">IKEV2 MSCHPv2 Turkey</a>'
        def fail(_url):
            raise RuntimeError("simulated network failure")
        with self.assertRaisesRegex(RuntimeError, "Incomplete provider scrape"):
            build_directory(index, fetch_country=fail)

    def test_rejects_country_page_url_outside_provider_origin(self):
        with self.assertRaises(RuntimeError):
            extract_country_servers("Turkey", "https://evil.test/page", "")


if __name__ == "__main__":
    unittest.main()
