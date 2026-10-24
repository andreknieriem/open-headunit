import tempfile
import unittest
import zipfile
from pathlib import Path

from check_audio_notices import ROOT, expected_notices, verify


class AudioNoticePackagingTest(unittest.TestCase):
    def package(self, notice):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        apk = Path(directory.name) / "test.apk"
        with zipfile.ZipFile(apk, "w") as package:
            package.writestr("assets/LICENSE", (ROOT / "LICENSE").read_bytes())
            if notice is not None:
                package.writestr("assets/audio-third-party.txt", notice)
        return apk

    def test_complete_verbatim_text_is_accepted(self):
        verify(self.package(expected_notices()))

    def test_missing_notice_fails(self):
        with self.assertRaises(KeyError):
            verify(self.package(None))

    def test_truncated_or_stale_notice_fails(self):
        with self.assertRaises(ValueError):
            verify(self.package(expected_notices()[:-1]))

    def test_project_license_is_also_required(self):
        apk = self.package(expected_notices())
        with zipfile.ZipFile(apk, "w") as package:
            package.writestr("assets/audio-third-party.txt", expected_notices())
        with self.assertRaises(KeyError):
            verify(apk)


if __name__ == "__main__":
    unittest.main()
