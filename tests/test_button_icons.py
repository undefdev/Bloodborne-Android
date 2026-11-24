"""Tests for tools/android/button_icons.py (run: python3 -I tests/test_button_icons.py)."""
import hashlib
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock
import zlib

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / 'tools/android/button_icons.py'
spec = importlib.util.spec_from_file_location('button_icons', SCRIPT)
bi = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bi)


def write_png(width, height, rgba):
    """An 8-bit RGBA PNG whose rows use filters 0-4 in turn (so the reader's unfiltering is exercised)."""
    stride, raw, prev = width * 4, bytearray(), bytes(width * 4)
    for y in range(height):
        row, f = rgba[y * stride:(y + 1) * stride], y % 5
        out = bytearray()
        for x in range(stride):
            a = row[x - 4] if x >= 4 else 0
            b, c = prev[x], prev[x - 4] if x >= 4 else 0
            pred = 0 if f == 0 else a if f == 1 else b if f == 2 else (a + b) // 2 if f == 3 else \
                (a if abs(a + b - c - a) <= abs(a + b - c - b) and abs(a + b - c - a) <= abs(a + b - c - c)
                 else b if abs(a + b - c - b) <= abs(a + b - c - c) else c)
            out.append((row[x] - pred) & 255)
        raw += bytes([f]) + out
        prev = row

    def chunk(kind, body):
        return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(bytes(raw))) + chunk(b'IEND', b''))


def tpf(textures):
    """A PS4 TPF (UTF-16 names) of [(name, fmt, dxgi, w, h, data)]."""
    head = 0x10 + 36 * len(textures)
    names = b''.join(n.encode('utf-16-le') + b'\0\0' for n, *_ in textures)
    data_at = head + len(names)
    entries, blobs, name_at, at = b'', b'', head, data_at
    for name, fmt, dxgi, w, h, data in textures:
        entries += struct.pack('<IiBBBBhhiiIiI', at, len(data), fmt, 0, 1, 0, w, h, 1, 0xD, name_at, 0, dxgi)
        name_at += len(name.encode('utf-16-le')) + 2
        blobs += data
        at += len(data)
    return b'TPF\0' + struct.pack('<iiBBBB', len(blobs), len(textures), 4, 3, 1, 0) + entries + names + blobs


def dcx(content):
    header = bytearray(bi.DCX_DATA)
    header[0:4] = b'DCX\0'
    header[0x18:0x1c] = b'DCS\0'
    header[0x24:0x28] = b'DCP\0'
    header[0x28:0x2c] = b'DFLT'
    header[0x44:0x48] = b'DCA\0'
    return bi.write_dcx(bytes(header), content)


def max_visible_error(src, dec):
    return max(bi.visible_error([p], [q]) ** 0.5 for p, q in zip(src, dec))


class PngTests(unittest.TestCase):
    def test_reads_every_filter(self):
        rgba = bytes((x * 7 + y * 13 + c * 51) & 255 for y in range(10) for x in range(6) for c in range(4))
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'p.png'
            path.write_bytes(write_png(6, 10, rgba))
            self.assertEqual(bi.read_png(path), (6, 10, rgba))

    def test_rejects_other_pngs(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'p.png'
            data = bytearray(write_png(4, 4, bytes(64)))
            data[8 + 8 + 9] = 2  # colour type RGB
            path.write_bytes(bytes(data))
            with self.assertRaises(ValueError):
                bi.read_png(path)


class Bc7Tests(unittest.TestCase):
    def test_flat_block(self):
        px = [(46, 46, 46, 255)] * 16
        self.assertLessEqual(max_visible_error(px, bi.decode_block(bi.encode_block(px))), 2)

    def test_gradient_block(self):
        px = [(10 + 15 * i, 200 - 10 * i, 30 + 5 * i, 255) for i in range(16)]
        self.assertLessEqual(max_visible_error(px, bi.decode_block(bi.encode_block(px))), 8)

    def test_badge_edge_block(self):
        # transparent black outside, a dark shadow, the lit rim and the fill: not one line in RGBA. Seven alpha levels
        # are more than either mode has, so some error is left (mode 5's 4 levels: about 36).
        px = [(0, 0, 0, 0), (0, 0, 0, 0), (0, 0, 0, 60), (0, 0, 0, 150),
              (0, 0, 0, 0), (0, 0, 0, 120), (118, 118, 118, 248), (100, 100, 100, 248),
              (0, 0, 0, 90), (118, 118, 118, 248), (46, 46, 46, 255), (46, 46, 46, 255),
              (110, 110, 110, 248), (46, 46, 46, 255), (46, 46, 46, 255), (46, 46, 46, 255)]
        self.assertLessEqual(max_visible_error(px, bi.decode_block(bi.encode_block(px))), 40)

    def test_icons_have_no_stray_pixels(self):
        # Every pixel of every icon stays close to its source (an encoder that put colour and alpha on one line with
        # plain RGBA distances left bright dots around the badges: 203).
        for icon in sorted(set(bi.REPLACE.values())):
            w, h, rgba = bi.read_png(bi.ICONS / icon)
            for by in range(h // 4):
                for bx in range(w // 4):
                    px = [tuple(rgba[((by * 4 + y) * w + bx * 4 + x) * 4:][:4]) for y in range(4) for x in range(4)]
                    self.assertLessEqual(max_visible_error(px, bi.decode_block(bi.encode_block(px))), 45, (icon, bx, by))

    def test_anchor_index_fits(self):
        # a block whose first pixel is at the far end of its line: the encoder swaps the endpoints so the anchor's
        # index fits its shorter field, and the block still decodes to the same colours.
        px = [(255, 255, 255, 255)] + [(0, 0, 0, 255)] * 15
        self.assertLessEqual(max_visible_error(px, bi.decode_block(bi.encode_block(px))), 2)


class LayoutTests(unittest.TestCase):
    def test_swizzle_round_trip(self):
        linear = bytes(i % 251 for i in range(16 * 16 * 16))  # 16x16 blocks
        tiled = bi.swizzle(linear, 16, 16)
        self.assertNotEqual(tiled, linear)
        self.assertEqual(bi.unswizzle(tiled, 16, 16), linear)

    def test_tiled_index_matches_swizzle(self):
        bw, bh = 24, 16
        linear = b''.join(struct.pack('<HH', bx, by) + bytes(12) for by in range(bh) for bx in range(bw))
        tiled = bi.swizzle(linear, bw, bh)
        for bx, by in ((0, 0), (1, 0), (0, 1), (7, 7), (8, 0), (23, 15), (13, 9)):
            i = bi.tiled_index(bx, by, bw)
            self.assertEqual(struct.unpack_from('<HH', tiled, i * 16), (bx, by))

    def test_dcx_round_trip(self):
        content = bytes(range(256)) * 100
        header, back = bi.read_dcx(dcx(content))
        self.assertEqual(back, content)
        self.assertEqual(struct.unpack_from('>II', header, 0x1c)[0], len(content))


class PatchTests(unittest.TestCase):
    def build(self, kg_format=bi.BC7_FORMAT):
        textures = [(name, kg_format, bi.BC7_DXGI, 32, 32, bytes(1024)) for name in bi.REPLACE]
        textures.append(('KG_LS', bi.BC7_FORMAT, bi.BC7_DXGI, 32, 32, bytes(range(256)) * 4))
        textures.append(('MENU_Common_00091', bi.BC7_FORMAT, bi.BC7_DXGI, 1024, 512, bytes(1024 * 512)))
        return dcx(tpf(textures))

    def zero_checksums(self):
        """AREAS with the checksums of all-zero blocks (the synthetic texture)."""
        areas = []
        for name, x, y, icon, _ in bi.AREAS:
            n = (-(-(x + bi.AREA_SIZE) // 4) - x // 4) * (-(-(y + bi.AREA_SIZE) // 4) - y // 4)
            areas.append((name, x, y, icon, hashlib.sha256(bytes(16 * n)).hexdigest()))
        return areas

    def test_patches_the_buttons_and_nothing_else(self):
        source = self.build()
        with mock.patch.object(bi, 'AREAS', self.zero_checksums()):
            result = bi.patch(source)
        _, before = bi.read_dcx(source)
        _, after = bi.read_dcx(result)
        self.assertEqual(len(after), len(before))
        textures = bi.tpf_textures(after)
        self.assertEqual(textures, bi.tpf_textures(before))
        off, size = textures['KG_LS'][:2]
        self.assertEqual(after[off:off + size], before[off:off + size], 'the sticks stay')
        for name, icon in bi.REPLACE.items():
            off, size = textures[name][:2]
            blocks = bi.unswizzle(after[off:off + size], 8, 8)
            decoded = [p for i in range(64) for p in bi.decode_block(blocks[i * 16:(i + 1) * 16])]
            w, h, rgba = bi.read_png(bi.ICONS / icon)
            # block order -> pixel order
            src = []
            for b in range(64):
                bx, by = b % 8, b // 8
                src += [tuple(rgba[((by * 4 + y) * 32 + bx * 4 + x) * 4:][:4]) for y in range(4) for x in range(4)]
            err = sum(bi.visible_error([p], [q]) for p, q in zip(src, decoded)) / len(src) / 4
            self.assertLess(err, 30, name)  # mean squared visible error: about 33 dB or better
        off, size, _, _, w, _, _ = textures['MENU_Common_00091']
        changed = {i for i in range(size // 16) if after[off + i * 16:off + i * 16 + 16] != bytes(16)}
        allowed = {bi.tiled_index(bx, by, w // 4) for _, x, y, _, _ in bi.AREAS
                   for by in range(y // 4, -(-(y + bi.AREA_SIZE) // 4)) for bx in range(x // 4, -(-(x + bi.AREA_SIZE) // 4))}
        self.assertTrue(changed)
        self.assertLessEqual(changed, allowed, 'only the blocks of the HUD buttons change')

    def test_refuses_unknown_hud_bytes(self):
        with self.assertRaisesRegex(ValueError, 'not the 1.09 texture'):
            bi.patch(self.build())

    def test_refuses_an_unexpected_texture(self):
        with mock.patch.object(bi, 'AREAS', self.zero_checksums()):
            with self.assertRaisesRegex(ValueError, 'unexpected texture'):
                bi.patch(self.build(kg_format=0))

    def test_refuses_a_file_without_the_buttons(self):
        with self.assertRaisesRegex(ValueError, 'not in this file'):
            bi.patch(dcx(tpf([('MENU_Common_00001', 102, 98, 32, 32, bytes(1024))])))


if __name__ == '__main__':
    unittest.main()
