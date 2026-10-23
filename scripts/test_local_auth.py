#!/usr/bin/env python3
"""Regression tests use only temporary credential files, never the user's private auth.env."""
import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import local_auth


class LocalCredentialsTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / 'auth.env'
        self.override = patch.object(local_auth, 'KEY_FILE', self.path)
        self.override.start()
        self.addCleanup(self.override.stop)

    def test_fresh_file_contains_only_platform_password_and_is_stable(self):
        local_auth.ensure_credentials()
        first = self.path.read_text()
        self.assertEqual(set(local_auth.load_credentials()), {local_auth.PASSWORD})
        self.assertEqual(len(first.splitlines()), 1)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        local_auth.ensure_credentials()
        self.assertEqual(first, self.path.read_text())

    def test_existing_retired_keys_are_preserved_but_not_returned(self):
        previous = 'CLIMATE_READ_KEY=' + 'a' * 64 + '\nCLIMATE_ADMIN_KEY=' + 'b' * 64
        self.path.write_text(previous)
        local_auth.ensure_credentials()
        self.assertTrue(self.path.read_text().startswith(previous + '\n'))
        self.assertEqual(set(local_auth.load_credentials()), {local_auth.PASSWORD})
        first = self.path.read_text()
        local_auth.ensure_credentials()
        self.assertEqual(first, self.path.read_text())

    def test_duplicate_credentials_are_rejected_without_rewriting_file(self):
        previous = (local_auth.PASSWORD + '=' + 'a' * 64 + '\n') * 2
        self.path.write_text(previous)
        with self.assertRaises(SystemExit):
            local_auth.ensure_credentials()
        self.assertEqual(self.path.read_text(), previous)


if __name__ == '__main__':
    unittest.main()
