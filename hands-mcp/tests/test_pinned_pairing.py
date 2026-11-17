"""Local TLS fixtures only; no Android device or external network is contacted."""

import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import ssl
import stat
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import hands_mcp as hands


TOKEN = 'A' * 43
CODE = '12345678'
CLIENT_ID = '4f1565d4-b26a-4bc5-bf3a-cefd48cdfed1'


class CountedReader:
    def __init__(self, reader, phone):
        self.reader, self.phone = reader, phone

    def read(self, *args):
        value = self.reader.read(*args)
        self.phone.received_bytes += len(value)
        return value

    def readline(self, *args):
        value = self.reader.readline(*args)
        self.phone.received_bytes += len(value)
        return value

    def __getattr__(self, key):
        return getattr(self.reader, key)


class TlsPhone:
    def __init__(self, cert, key):
        self.requests = []
        self.received_bytes = 0
        self.override = None
        self.closed_connection = threading.Event()
        phone = self
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(str(cert), str(key))

        class Server(ThreadingHTTPServer):
            daemon_threads = True

            def get_request(self):
                client, address = super().get_request()
                client.settimeout(3)
                try:
                    return context.wrap_socket(client, server_side=True), address
                except Exception:
                    client.close()
                    raise

        class Handler(BaseHTTPRequestHandler):
            def handle(self):
                try:
                    super().handle()
                except (ConnectionResetError, ssl.SSLError):
                    pass  # A pin-refusing client closes immediately after TLS.

            def setup(self):
                super().setup()
                self.rfile = CountedReader(self.rfile, phone)

            def finish(self):
                try:
                    super().finish()
                finally:
                    phone.closed_connection.set()

            def log_message(self, *_):
                pass

            def do_GET(self):
                self.respond(None)

            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                self.respond(json.loads(raw))

            def respond(self, body):
                request = {'path': self.path, 'authorization': self.headers.get('Authorization'), 'body': body}
                phone.requests.append(request)
                if phone.override:
                    status, response, headers = phone.override(request)
                elif self.path == '/v1/pair':
                    status, response, headers = 200, {'ok': True, 'result': {
                        'token': TOKEN, 'client_id': CLIENT_ID, 'label': body['client_name']}}, {}
                else:
                    status, response, headers = 200, {'ok': True, 'result': {'armed': False, 'target': 'agent'}}, {}
                raw = response if isinstance(response, bytes) else json.dumps(response).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                for key, value in headers.items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(raw)

        self.server = Server(('127.0.0.1', 0), Handler)
        self.endpoint = 'https://127.0.0.1:' + str(self.server.server_port)
        self.thread = threading.Thread(target=lambda: self.server.serve_forever(poll_interval=0.02), daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


@unittest.skipUnless(shutil.which('openssl'), 'openssl is required only to generate the temporary TLS test fixture')
class PinnedPairingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cert_directory = tempfile.TemporaryDirectory(prefix='hands-test-cert-')
        cls.cert = Path(cls.cert_directory.name) / 'server-cert.pem'
        cls.key = Path(cls.cert_directory.name) / 'server-key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1',
            '-nodes', '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1',
            '-keyout', str(cls.key), '-out', str(cls.cert)], check=True, capture_output=True, timeout=15)
        der = ssl.PEM_cert_to_DER_cert(cls.cert.read_text())
        cls.pin = hashlib.sha256(der).hexdigest()

    @classmethod
    def tearDownClass(cls):
        cls.cert_directory.cleanup()

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='hands-pair-test-')
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.token_file = self.path / 'hands-token'
        self.phone = TlsPhone(self.cert, self.key)
        self.addCleanup(self.phone.close)

    def pair(self, **kwargs):
        return hands.pair_client(self.phone.endpoint, kwargs.pop('pin', self.pin), CODE,
            'TLS fixture client', kwargs.pop('token_file', self.token_file), **kwargs)

    def cli(self, *, pin=None):
        code_file = self.path / 'pair-code'
        code_file.write_text(CODE + '\n')
        code_file.chmod(0o600)
        return subprocess.run([sys.executable, str(Path(hands.__file__).resolve()), '--pair',
            '--endpoint', self.phone.endpoint, '--server-fingerprint', pin or self.pin,
            '--client-name', 'TLS fixture client', '--pairing-code-file', str(code_file),
            '--token-file', str(self.token_file)], capture_output=True, text=True, timeout=10,
            env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'))

    def test_pinned_self_signed_tls_authenticates_before_bearer(self):
        client = hands.HandsClient(self.phone.endpoint, TOKEN, server_fingerprint=self.pin)
        self.addCleanup(client.close)
        self.assertTrue(client.call('mobile_status', {})['ok'])
        self.assertEqual(self.phone.requests[0]['authorization'], 'Bearer ' + TOKEN)

    def test_pin_mismatch_sends_zero_http_bytes_and_no_credentials(self):
        client = hands.HandsClient(self.phone.endpoint, TOKEN, server_fingerprint='0' * 64)
        self.addCleanup(client.close)
        with self.assertRaises(hands.BridgeError) as error:
            client.call('mobile_status', {})
        self.assertEqual(error.exception.code, 'server_identity_mismatch')
        self.assertTrue(self.phone.closed_connection.wait(2))
        self.assertEqual(self.phone.received_bytes, 0)
        self.assertEqual(self.phone.requests, [])

    def test_pairing_pin_mismatch_sends_zero_code_or_http_body_bytes(self):
        with self.assertRaises(hands.BridgeError) as error:
            self.pair(pin='0' * 64)
        self.assertEqual(error.exception.code, 'server_identity_mismatch')
        self.assertTrue(self.phone.closed_connection.wait(2))
        self.assertEqual(self.phone.received_bytes, 0)
        self.assertFalse(self.token_file.exists())

    def test_unpinned_self_signed_peer_still_fails_ca_validation(self):
        client = hands.HandsClient(self.phone.endpoint, TOKEN)
        self.addCleanup(client.close)
        with self.assertRaises(hands.BridgeError) as error:
            client.call('mobile_status', {})
        self.assertEqual(error.exception.code, 'certificate_untrusted')
        self.assertEqual(self.phone.requests, [])
        self.assertEqual(ssl.create_default_context().verify_mode, ssl.CERT_REQUIRED)

    def test_unpinned_https_preserves_normal_ca_and_hostname_validation(self):
        trusted = ssl.create_default_context(cafile=str(self.cert))
        with patch('ssl._create_default_https_context', return_value=trusted):
            client = hands.HandsClient(self.phone.endpoint, TOKEN)
            self.addCleanup(client.close)
            self.assertTrue(client.call('mobile_status', {})['ok'])
        self.assertTrue(trusted.check_hostname)
        self.assertEqual(trusted.verify_mode, ssl.CERT_REQUIRED)

    def test_pin_normalizes_phone_grouping_and_refuses_insecure_configuration(self):
        spaced = ' '.join(self.pin.upper()[offset:offset + 8] for offset in range(0, 64, 8))
        colon = ':'.join(self.pin[offset:offset + 2] for offset in range(0, 64, 2))
        self.assertEqual(hands.normalize_fingerprint(spaced), self.pin)
        self.assertEqual(hands.normalize_fingerprint(colon), self.pin)
        for invalid in ('', '0' * 63, 'x' * 64, self.pin + '00', self.pin[:8] + '\n' + self.pin[8:]):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                hands.normalize_fingerprint(invalid)
        for endpoint in ('http://127.0.0.1:8765', 'http://100.80.1.2:8766', 'http://192.168.1.2:8766'):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                hands.HandsClient(endpoint, TOKEN, server_fingerprint=self.pin)
        with self.assertRaises(ValueError):
            hands.HandsClient(self.phone.endpoint, TOKEN, opener=object(), server_fingerprint=self.pin)
        self.assertEqual(self.phone.requests, [])

    def test_pairing_saves_complete_private_token_without_bearer_or_secret_metadata(self):
        result = self.pair()
        self.assertEqual(self.token_file.read_text(), TOKEN + '\n')
        if os.name == 'posix':
            self.assertEqual(stat.S_IMODE(self.token_file.stat().st_mode), 0o600)
        self.assertEqual(result['client_id'], CLIENT_ID)
        self.assertNotIn(TOKEN, json.dumps(result))
        self.assertNotIn(CODE, json.dumps(result))
        self.assertEqual(self.phone.requests, [{'path': '/v1/pair', 'authorization': None,
            'body': {'code': CODE, 'client_name': 'TLS fixture client'}}])
        self.assertEqual(list(self.path.glob('.hands-token-*.tmp')), [])

    def test_existing_token_is_rejected_before_any_pair_request(self):
        self.token_file.write_text('existing-credential')
        with self.assertRaises(ValueError):
            self.pair()
        self.assertEqual(self.phone.requests, [])
        self.assertEqual(self.token_file.read_text(), 'existing-credential')

    def test_destination_parent_and_repository_are_validated_before_pairing(self):
        git = self.path / 'repository'
        git.mkdir()
        (git / '.git').mkdir()
        for destination in (self.path / 'missing' / 'token', git / 'token'):
            with self.subTest(destination=destination), self.assertRaises(ValueError):
                self.pair(token_file=destination)
        self.assertEqual(self.phone.requests, [])

    def test_denial_is_not_retried_and_does_not_create_token(self):
        self.phone.override = lambda _: (403, {'ok': False, 'error': {
            'code': 'pairing_denied', 'message': TOKEN + CODE}}, {})
        with self.assertRaises(hands.BridgeError) as error:
            self.pair()
        self.assertEqual(error.exception.code, 'pairing_denied')
        self.assertNotIn(TOKEN, str(error.exception))
        self.assertNotIn(CODE, str(error.exception))
        self.assertEqual(len(self.phone.requests), 1)
        self.assertFalse(self.token_file.exists())

    def test_redirect_does_not_forward_pairing_code(self):
        self.phone.override = lambda _: (302, b'', {'Location': self.phone.endpoint + '/second-server'})
        with self.assertRaises(hands.BridgeError) as error:
            self.pair()
        self.assertEqual(error.exception.code, 'redirect_rejected')
        self.assertEqual(len(self.phone.requests), 1)
        self.assertFalse(self.token_file.exists())

    def test_output_race_does_not_overwrite_and_temporary_secret_is_removed(self):
        def reply(request):
            self.token_file.write_text('occupied-during-approval')
            return 200, {'ok': True, 'result': {'token': TOKEN, 'client_id': CLIENT_ID,
                'label': request['body']['client_name']}}, {}
        self.phone.override = reply
        with self.assertRaises(hands.BridgeError) as error:
            self.pair()
        self.assertEqual(error.exception.code, 'credential_not_saved')
        self.assertEqual(self.token_file.read_text(), 'occupied-during-approval')
        self.assertEqual(list(self.path.glob('.hands-token-*.tmp')), [])
        self.assertEqual(len(self.phone.requests), 1)

    def test_real_pair_cli_prints_config_but_never_code_or_token(self):
        process = self.cli()
        self.assertEqual(process.returncode, 0, process.stderr)
        summary = json.loads(process.stdout)
        self.assertIn('mcpServers', summary)
        self.assertEqual(set(summary['mcpServers']), {'hands'})
        self.assertEqual(summary['server_fingerprint'], self.pin)
        self.assertNotIn(TOKEN, process.stdout + process.stderr)
        self.assertNotIn(CODE, process.stdout + process.stderr)
        self.assertEqual(self.token_file.read_text().strip(), TOKEN)

    def test_real_pair_cli_denial_does_not_log_server_secret_message(self):
        self.phone.override = lambda _: (200, {'ok': False, 'error': {
            'code': 'pairing_denied', 'message': TOKEN + CODE}}, {})
        process = self.cli()
        self.assertEqual(process.returncode, 1)
        self.assertEqual(process.stdout, '')
        self.assertIn('pairing_denied', process.stderr)
        self.assertNotIn(TOKEN, process.stderr)
        self.assertNotIn(CODE, process.stderr)
        self.assertFalse(self.token_file.exists())

    def test_pairing_response_and_secret_file_inputs_are_bounded(self):
        self.phone.override = lambda _: (200, b' ' * (hands.MAX_PAIR_RESPONSE_BYTES + 1), {})
        with self.assertRaises(hands.BridgeError) as error:
            self.pair()
        self.assertEqual(error.exception.code, 'response_too_large')
        self.assertFalse(self.token_file.exists())
        code_file = self.path / 'code'
        code_file.write_text(CODE)
        code_file.chmod(0o600)
        self.assertEqual(hands.read_pairing_code(str(code_file)), CODE)
        code_file.write_text('１２３４５６７８')
        with self.assertRaises(ValueError):
            hands.read_pairing_code(str(code_file))
        if os.name == 'posix':
            code_file.write_text(CODE)
            code_file.chmod(0o644)
            with self.assertRaises(ValueError):
                hands.read_pairing_code(str(code_file))


if __name__ == '__main__':
    unittest.main()
