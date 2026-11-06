import unittest
from agent_ci_triage import classify


class AgentCiTriageTests(unittest.TestCase):
    def test_network_timeout_is_retryable(self):
        r = classify("curl: Operation timed out"); self.assertEqual(r["category"], "transient"); self.assertTrue(r["retry_recommended"])
    def test_http_503_is_retryable(self):
        self.assertTrue(classify("HTTP 503 Service Unavailable")["retry_recommended"])
    def test_compile_failure_is_not_retryable(self):
        r = classify("Unresolved reference: missingMethod"); self.assertEqual(r["category"], "deterministic"); self.assertFalse(r["retry_recommended"])
    def test_test_failure_overrides_network_noise(self):
        r = classify("download timeout warning\nTests failed: AssertionError"); self.assertEqual(r["category"], "deterministic"); self.assertFalse(r["retry_recommended"])
    def test_unclassified_failure_does_not_retry(self):
        r = classify("process exited with code 1"); self.assertEqual(r["category"], "unclassified"); self.assertFalse(r["retry_recommended"])
    def test_secrets_are_redacted(self):
        r = classify("ERROR Authorization: Bearer abcdef123456"); self.assertNotIn("abcdef123456", "\n".join(r["excerpt"]))
if __name__ == "__main__": unittest.main()
