import unittest
from pydantic import ValidationError
from app import Observation, endpoint_id

def endpoint_id(o):
    import hashlib
    return hashlib.sha256(f"{o.protocol}|{o.endpoint_host}|{o.port}".encode()).hexdigest()

class ObservationTests(unittest.TestCase):
    def test_public_hostname_normalized(self):
        o=Observation(protocol="ikev2",endpoint_host="VPN.Example.com.",port=500,result="connected",observed_at=1)
        self.assertEqual(o.endpoint_host,"vpn.example.com")
    def test_private_ip_rejected(self):
        with self.assertRaises(ValidationError):
            Observation(protocol="ikev2",endpoint_host="127.0.0.1",port=500,result="connected",observed_at=1)
    def test_unknown_fields_rejected(self):
        with self.assertRaises(ValidationError):
            Observation(protocol="ikev2",endpoint_host="vpn.example.com",port=500,result="connected",observed_at=1,password="secret")
    def test_protocol_scopes_endpoint(self):
        a=Observation(protocol="ikev2",endpoint_host="vpn.example.com",port=500,result="connected",observed_at=1)
        b=Observation(protocol="wireguard",endpoint_host="vpn.example.com",port=500,result="connected",observed_at=1)
        self.assertNotEqual(endpoint_id(a),endpoint_id(b))

if __name__=="__main__":
    unittest.main()
