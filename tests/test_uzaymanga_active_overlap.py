import base64
import unittest

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from tools.uzaymanga_active_overlap import (
        MANGACIX_KEY,
        normalize_title,
        signed_mangacix_url,
        title_matches,
    )
except ModuleNotFoundError:
    AESGCM = MANGACIX_KEY = normalize_title = signed_mangacix_url = title_matches = None


@unittest.skipIf(title_matches is None, 'active overlap dependencies are not installed')
class ActiveTitleOverlapTests(unittest.TestCase):
    def test_normalization_handles_turkish_and_punctuation(self):
        self.assertEqual('yalnizseviyeatlama', normalize_title('Yalnız: Seviye-Atlama'))

    def test_exact_containment_and_alias_matches(self):
        self.assertTrue(title_matches('Solo Leveling', {'title': 'Solo Leveling', 'aliases': []}))
        self.assertTrue(title_matches('Solo Leveling', {'title': 'Other', 'aliases': ['Solo Leveling Ragnarok']}))
        self.assertFalse(title_matches('Solo Leveling', {'title': 'One Piece', 'aliases': []}))

    def test_mangacix_signature_covers_the_exact_query(self):
        url, headers = signed_mangacix_url('/secure/titles', [('query', 'Kule Çiftçisi'), ('page', 1)])
        encrypted, encoded_iv = headers['X-E-H'].split('.')
        iv = base64.b64decode(encoded_iv)
        self.assertEqual(12, len(iv))
        plaintext = AESGCM(MANGACIX_KEY).decrypt(iv, base64.b64decode(encrypted), None)
        query = url.split('?', 1)[1]
        self.assertEqual(b'{version}' + query.encode(), plaintext)


if __name__ == '__main__':
    unittest.main()
