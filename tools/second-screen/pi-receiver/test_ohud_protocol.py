"""Run with: python3 -m unittest test_ohud_protocol (from this directory)."""

import struct
import unittest

import ohud_protocol as p


def hexbytes(s):
    return bytes(int(x, 16) for x in s.split())


class InfoTest(unittest.TestCase):
    def test_info_vector_matches_the_app(self):
        # Same vector as UsbDisplayProtocolTest: 1024x600, 160 dpi, 30 fps, keyframe requests on.
        expected = hexbytes("4F 48 55 44 01 01 00 04 58 02 A0 00 1E 01") + bytes(18)
        self.assertEqual(expected, p.encode_info(1024, 600, 160, 30, True))


class StreamSizeTest(unittest.TestCase):
    def test_the_stream_is_the_smallest_standard_size_holding_the_panel(self):
        self.assertEqual((800, 480), p.stream_size(800, 480))
        self.assertEqual((1280, 720), p.stream_size(1024, 600))
        self.assertEqual((1920, 1080), p.stream_size(1366, 768))
        self.assertEqual((1920, 1080), p.stream_size(2560, 1440))


class FrameParserTest(unittest.TestCase):
    def frame(self, payload, ts=0x01020304, flags=p.FRAME_FLAG_KEYFRAME):
        return p.HEADER.pack(p.FRAME_MAGIC, len(payload), ts, flags, 0) + payload

    def test_header_vector_matches_the_app(self):
        self.assertEqual(hexbytes("4F 48 55 46 D2 04 00 00 04 03 02 01 01 00 00 00"),
                         p.HEADER.pack(p.FRAME_MAGIC, 1234, 0x01020304, 1, 0))

    def test_a_frame_split_across_reads_comes_out_whole(self):
        data = self.frame(b"\x00\x00\x00\x01\x65abc")
        parser = p.FrameParser()
        self.assertEqual([], parser.feed(data[:7]))
        frames = parser.feed(data[7:])
        self.assertEqual(1, len(frames))
        self.assertEqual(b"\x00\x00\x00\x01\x65abc", frames[0].payload)
        self.assertTrue(frames[0].keyframe)
        self.assertFalse(frames[0].config)

    def test_merged_frames_and_a_zero_length_read(self):
        parser = p.FrameParser()
        frames = parser.feed(self.frame(b"one") + self.frame(b"two", flags=p.FRAME_FLAG_CONFIG))
        self.assertEqual([b"one", b"two"], [f.payload for f in frames])
        self.assertTrue(frames[1].config)
        self.assertEqual([], parser.feed(b""))

    def test_garbage_is_skipped_to_the_next_magic_and_counted(self):
        parser = p.FrameParser()
        frames = parser.feed(b"junkjunkjunkjunkjunk" + self.frame(b"ok"))
        self.assertEqual([b"ok"], [f.payload for f in frames])
        self.assertGreater(parser.resyncs, 0)

    def test_an_impossible_length_is_treated_as_corruption(self):
        parser = p.FrameParser()
        bogus = p.HEADER.pack(p.FRAME_MAGIC, p.MAX_PAYLOAD + 1, 0, 0, 0)
        frames = parser.feed(bogus + self.frame(b"after"))
        self.assertEqual([b"after"], [f.payload for f in frames])


if __name__ == "__main__":
    unittest.main()
