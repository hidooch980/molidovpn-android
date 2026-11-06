import unittest

from agent_ci_triage import classify


class AgentCiTriageTests(unittest.TestCase):
    def test_network_timeout_is_retryable(self):
        result = classify("curl: Operation timed out")
        self.assertEqual(result["category"], "transient")
        self.assertTrue(result["retry_recommended"])

    def test_http_503_is_retryable(self):
        self.assertTrue(
            classify("HTTP 503 Service Unavailable")["retry_recommended"]
        )

    def test_compile_failure_is_not_retryable(self):
        result = classify("Unresolved reference: missingMethod")
        self.assertEqual(result["category"], "deterministic")
        self.assertFalse(result["retry_recommended"])

    def test_test_failure_overrides_network_noise(self):
        result = classify("download timeout warning\\nTests failed: AssertionError")
        self.assertEqual(result["category"], "deterministic")
        self.assertFalse(result["retry_recommended"])

    def test_unclassified_failure_does_not_retry(self):
        result = classify("process exited with code 1")
        self.assertEqual(result["category"], "unclassified")
        self.assertFalse(result["retry_recommended"])

    def test_bearer_token_is_redacted(self):
        result = classify("ERROR Authorization: Bearer abcdef123456")
        self.assertNotIn("abcdef123456", "\\n".join(result["excerpt"]))

    def test_query_secret_is_redacted(self):
        result = classify("ERROR request failed https://example.test/?token=secret-value")
        self.assertNotIn("secret-value", "\\n".join(result["excerpt"]))

    def test_github_token_is_redacted(self):
        token = "ghp_" + ("A" * 36)
        result = classify(f"ERROR request failed with {token}")
        self.assertNotIn(token, "\\n".join(result["excerpt"]))


if __name__ == "__main__":
    unittest.main()
