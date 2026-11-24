#!/usr/bin/env python3
"""Xbox-style button prompts for Bloodborne (CUSA03173 v1.09) as a mod made from your own game files.

Usage: button_icons.py <dvdroot_ps4/menu/common.tpf.dcx> <output common.tpf.dcx>

Every key guide and prompt of the game's menus draws its button from the KG_* textures of menu/common.tpf.dcx
(32x32 BC7, tiled for the PS4's GPU), and the prompts in the game world ("View Notes", "Open") from three 28x28 areas
of its MENU_Common_00091 (the HUD, fe.gfx). This writes a copy of that file with the PlayStation symbols replaced by
the icons in tools/android/button_icons/ (A, B, X, Y, LB, RB, LT, RT, the stick clicks LS and RS, Menu and View; the
sticks, the d-pad and the trademark signs stay). The textures keep their size and format, so the file's layout does
not change. The pad bridge maps the Kishi's (or any Xbox-layout pad's) A B X Y to cross circle square triangle, which
is what the icons show. Install the output as a mod: <mods folder>/Xbox button icons/menu/common.tpf.dcx (the app's
default mods folder is /sdcard/Bloodborne/mods); the Mods menu turns it off again. The output is made from your game
files: do not share it. The icons are made by tools/android/button_icons/make_icons.py (Pillow, only to remake them).

Python standard library only: a PNG reader, a BC7 encoder (per 4x4 block the better of mode 6, colour and alpha on
one line with 4-bit indices, and mode 5, colour and alpha on lines of their own with 2-bit indices, which the badges'
edges need: transparent black outside, a dark shadow, the lit rim and the fill do not lie on one line) and the DCX
(DFLT) and TPF (PS4) layouts as SoulsFormats reads them.
"""
import struct
import sys
import zlib
from pathlib import Path

ICONS = Path(__file__).resolve().parent / 'button_icons'
# texture -> icon file: the PlayStation symbol each texture shows -> the Xbox button the pad bridge maps to it.
# (The names follow the Japanese layout: KG_OK is the circle, KG_Cancel the cross.)
REPLACE = {
    'KG_Cancel': 'a.png',     # cross
    'KG_OK': 'b.png',         # circle
    'KG_R_L': 'x.png',        # square
    'KG_R_U': 'y.png',        # triangle
    'KG_L1': 'lb.png', 'KG_R1': 'rb.png', 'KG_L2': 'lt.png', 'KG_R2': 'rt.png',
    'KG_L3': 'ls.png', 'KG_R3': 'rs.png',
    'KG_Start': 'menu.png',   # Options
    'KG_TP_L': 'view.png', 'KG_TP_R': 'view.png',  # the touchpad: the pad bridge maps View (Select) to it
}
# The HUD's buttons: (texture, x, y, icon, sha256 of the original blocks that cover the area) for 28x28 areas, drawn as
# the 32x32 icons without their outer 2 pixels (the same 26-pixel badge). The blocks around an area are transparent in
# the 1.09 file; the checksum makes sure that is the file being patched, so nothing else is overwritten.
AREAS = [
    ('MENU_Common_00091', 660, 122, 'b.png', '8f0a9c62aaba7b1e51dc44e5625ec46c0d6b159b4bd85043b6af8ede45442ae7'),
    ('MENU_Common_00091', 693, 122, 'a.png', '7d6b800a6cf87cc42054f3ab4fa0a6666a88f76cd2c20d3c2c77c429669357a8'),
    ('MENU_Common_00091', 725, 122, 'ls.png', 'ee734a0153c6753d7843ad5310ec7b84fb40739de772d10051842b1eb01d0653'),
]
AREA_SIZE = 28
BC7_FORMAT, BC7_DXGI, PS4 = 102, 98, 4


# ---- PNG (8-bit RGBA, not interlaced: what make_icons.py writes) ----

def read_png(path):
    data = Path(path).read_bytes()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError(f'{path}: not a PNG')
    pos, idat, width = 8, b'', None
    while pos < len(data):
        length, kind = struct.unpack_from('>I4s', data, pos)
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b'IHDR':
            width, height, depth, color, _, _, interlace = struct.unpack('>IIBBBBB', body)
            if (depth, color, interlace) != (8, 6, 0):
                raise ValueError(f'{path}: needs 8-bit RGBA without interlace')
        elif kind == b'IDAT':
            idat += body
        elif kind == b'IEND':
            break
    raw, stride, bpp = zlib.decompress(idat), width * 4, 4
    rows, prev = [], bytearray(stride)
    for y in range(height):
        f, line = raw[y * (stride + 1)], bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b, c = prev[x], prev[x - bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
            elif f != 0:
                raise ValueError(f'{path}: bad PNG filter {f}')
        rows.append(bytes(line))
        prev = line
    return width, height, b''.join(rows)


# ---- BC7 (modes 5 and 6) ----

WEIGHTS4 = (0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64)
WEIGHTS2 = (0, 21, 43, 64)


def _lerp(e0, e1, w):
    return ((64 - w) * e0 + w * e1 + 32) >> 6


def visible_error(src, dec):
    """What shows on screen: alpha-premultiplied colour error plus alpha error (a transparent pixel's colour is free)."""
    err = 0
    for (r, g, b, a), (r2, g2, b2, a2) in zip(src, dec):
        for c, c2 in ((r, r2), (g, g2), (b, b2)):
            d = (c * a - c2 * a2) / 255
            err += d * d
        err += (a - a2) * (a - a2)
    return err


class _Bits:
    def __init__(self):
        self.value, self.pos = 0, 0

    def put(self, value, count):
        self.value |= value << self.pos
        self.pos += count

    def block(self):
        assert self.pos == 128, self.pos
        return self.value.to_bytes(16, 'little')


def _principal_line(points, weights):
    """The weighted mean and principal axis of points (any dimension), and the extent of the points along it."""
    total = sum(weights) or 1.0
    dims = len(points[0])
    mean = [sum(w * p[c] for p, w in zip(points, weights)) / total for c in range(dims)]
    cov = [[sum(w * (p[i] - mean[i]) * (p[j] - mean[j]) for p, w in zip(points, weights)) for j in range(dims)]
           for i in range(dims)]
    axis = [1.0] * dims
    for _ in range(8):
        v = [sum(cov[i][j] * axis[j] for j in range(dims)) for i in range(dims)]
        norm = max(abs(x) for x in v)
        if norm < 1e-9:
            break
        axis = [x / norm for x in v]
    scale = sum(a * a for a in axis) or 1.0
    ts = [sum((p[c] - mean[c]) * axis[c] for c in range(dims)) / scale for p, w in zip(points, weights) if w > 0]
    lo, hi = (min(ts), max(ts)) if ts else (0.0, 0.0)
    clamp = lambda v: min(255.0, max(0.0, v))
    return [clamp(mean[c] + axis[c] * lo) for c in range(dims)], [clamp(mean[c] + axis[c] * hi) for c in range(dims)]


def _least_squares(points, weights, idx, table):
    """The endpoints that fit these indices best (weighted, per channel)."""
    aa = ab = bb = 0.0
    dims = len(points[0])
    ax, bx = [0.0] * dims, [0.0] * dims
    for p, wt, i in zip(points, weights, idx):
        w = table[i] / 64
        a, b = 1 - w, w
        aa += wt * a * a; ab += wt * a * b; bb += wt * b * b
        for c in range(dims):
            ax[c] += wt * a * p[c]; bx[c] += wt * b * p[c]
    det = aa * bb - ab * ab
    if abs(det) < 1e-9:
        return None
    clamp = lambda v: min(255.0, max(0.0, v))
    return ([clamp((bb * ax[c] - ab * bx[c]) / det) for c in range(dims)],
            [clamp((aa * bx[c] - ab * ax[c]) / det) for c in range(dims)])


# mode 6: RGBA endpoints of 7 bits plus a shared low bit (p) per endpoint, 4-bit indices

def _quantize6(e):
    best = None
    for p in (0, 1):
        q = [min(127, max(0, int(round((v - p) / 2)))) for v in e]
        c = tuple(x << 1 | p for x in q)
        err = sum((a - b) * (a - b) for a, b in zip(c, e))
        if best is None or err < best[0]:
            best = (err, q, p, c)
    return best[1:]


def _fit6(pixels, e0, e1):
    q0, p0, c0 = _quantize6(e0)
    q1, p1, c1 = _quantize6(e1)
    pal = [tuple(_lerp(a, b, w) for a, b in zip(c0, c1)) for w in WEIGHTS4]
    idx = [min(range(16), key=lambda j: visible_error([px], [pal[j]])) for px in pixels]
    return visible_error(pixels, [pal[i] for i in idx]), (q0, p0, q1, p1, idx)


def _mode6(pixels):
    weights = [1.0] * 16
    best = _fit6(pixels, *_principal_line(pixels, weights))
    for _ in range(3):
        refined = _least_squares(pixels, weights, best[1][4], WEIGHTS4)
        if refined is None:
            break
        fit = _fit6(pixels, *refined)
        if fit[0] >= best[0]:
            break
        best = fit
    err, (q0, p0, q1, p1, idx) = best
    if idx[0] >= 8:  # the anchor index has no top bit: swap the endpoints
        q0, p0, q1, p1 = q1, p1, q0, p0
        idx = [15 - i for i in idx]
    bits = _Bits()
    bits.put(1 << 6, 7)
    for c in range(4):
        bits.put(q0[c], 7)
        bits.put(q1[c], 7)
    bits.put(p0, 1)
    bits.put(p1, 1)
    for k, i in enumerate(idx):
        bits.put(i, 3 if k == 0 else 4)
    return err, bits.block()


# mode 5: RGB endpoints of 7 bits and alpha endpoints of 8 bits, each with its own 2-bit indices (no rotation)

def _expand7(q):
    return q << 1 | q >> 6


def _mode5(pixels):
    alphas = [p[3] for p in pixels]
    # alpha: every endpoint pair near the extremes, the best one
    lo, hi = min(alphas), max(alphas)
    best_a = None
    for a0 in range(max(0, lo - 6), min(255, lo + 6) + 1):
        for a1 in range(max(0, hi - 6), min(255, hi + 6) + 1):
            pal = [_lerp(a0, a1, w) for w in WEIGHTS2]
            idx = [min(range(4), key=lambda j: abs(a - pal[j])) for a in alphas]
            err = sum((a - pal[i]) ** 2 for a, i in zip(alphas, idx))
            if best_a is None or err < best_a[0]:
                best_a = (err, a0, a1, idx, pal)
    _, a0, a1, aidx, apal = best_a
    dec_alpha = [apal[i] for i in aidx]
    # colour: a line through the visible pixels (weighted by alpha), refined
    rgb = [p[:3] for p in pixels]
    weights = [max(p[3], d) / 255 + 1e-3 for p, d in zip(pixels, dec_alpha)]

    def fit(e0, e1):
        q0 = [min(127, max(0, int(round(v / 2)))) for v in e0]
        q1 = [min(127, max(0, int(round(v / 2)))) for v in e1]
        c0, c1 = [_expand7(x) for x in q0], [_expand7(x) for x in q1]
        pal = [tuple(_lerp(a, b, w) for a, b in zip(c0, c1)) for w in WEIGHTS2]
        idx, dec = [], []
        for px, da in zip(pixels, dec_alpha):
            j = min(range(4), key=lambda j: visible_error([px], [pal[j] + (da,)]))
            idx.append(j)
            dec.append(pal[j] + (da,))
        return visible_error(pixels, dec), (q0, q1, idx)
    best = fit(*_principal_line(rgb, weights))
    for _ in range(3):
        refined = _least_squares(rgb, [w * w for w in weights], best[1][2], WEIGHTS2)
        if refined is None:
            break
        f = fit(*refined)
        if f[0] >= best[0]:
            break
        best = f
    err, (q0, q1, cidx) = best
    if cidx[0] >= 2:
        q0, q1 = q1, q0
        cidx = [3 - i for i in cidx]
    if aidx[0] >= 2:
        a0, a1 = a1, a0
        aidx = [3 - i for i in aidx]
    bits = _Bits()
    bits.put(1 << 5, 6)
    bits.put(0, 2)  # no rotation
    for c in range(3):
        bits.put(q0[c], 7)
        bits.put(q1[c], 7)
    bits.put(a0, 8)
    bits.put(a1, 8)
    for k, i in enumerate(cidx):
        bits.put(i, 1 if k == 0 else 2)
    for k, i in enumerate(aidx):
        bits.put(i, 1 if k == 0 else 2)
    return err, bits.block()


def encode_block(pixels):
    """16 RGBA tuples (row-major 4x4) -> one 16-byte BC7 block: mode 6 or mode 5, whichever shows less error."""
    return min(_mode6(pixels), _mode5(pixels))[1]


def decode_block(block):
    """A BC7 mode 5 or 6 block -> 16 RGBA tuples (for the checks)."""
    bits = int.from_bytes(block, 'little')
    pos = 0

    def get(count):
        nonlocal pos
        v = (bits >> pos) & ((1 << count) - 1)
        pos += count
        return v
    if bits & 0x7f == 1 << 6:
        pos = 7
        q = [[0] * 4, [0] * 4]
        for c in range(4):
            q[0][c] = get(7)
            q[1][c] = get(7)
        p = (get(1), get(1))
        c0, c1 = [x << 1 | p[0] for x in q[0]], [x << 1 | p[1] for x in q[1]]
        return [tuple(_lerp(a, b, WEIGHTS4[i]) for a, b in zip(c0, c1)) for i in [get(3 if k == 0 else 4) for k in range(16)]]
    if bits & 0x3f == 1 << 5:
        pos = 6
        if get(2):
            raise ValueError('mode 5 with rotation')
        q = [[0] * 3, [0] * 3]
        for c in range(3):
            q[0][c] = get(7)
            q[1][c] = get(7)
        a0, a1 = get(8), get(8)
        c0, c1 = [_expand7(x) for x in q[0]], [_expand7(x) for x in q[1]]
        cidx = [get(1 if k == 0 else 2) for k in range(16)]
        aidx = [get(1 if k == 0 else 2) for k in range(16)]
        return [tuple(_lerp(a, b, WEIGHTS2[ci]) for a, b in zip(c0, c1)) + (_lerp(a0, a1, WEIGHTS2[ai]),)
                for ci, ai in zip(cidx, aidx)]
    raise ValueError('not a mode 5 or 6 block')


def encode_bc7(width, height, rgba):
    """RGBA bytes -> BC7 blocks in row-major block order."""
    out = bytearray()
    for by in range(height // 4):
        for bx in range(width // 4):
            px = []
            for y in range(4):
                o = ((by * 4 + y) * width + bx * 4) * 4
                px += [tuple(rgba[o + 4 * x:o + 4 * x + 4]) for x in range(4)]
            out += encode_block(px)
    return bytes(out)


# ---- PS4 tiling of block-compressed textures: 8x8-block tiles in row order, Morton order inside ----

def _morton(i):
    x = y = 0
    for b in range(8):
        x |= ((i >> (2 * b)) & 1) << b
        y |= ((i >> (2 * b + 1)) & 1) << b
    return x, y


def swizzle(linear, bw, bh, size=16):
    """Row-major blocks -> the PS4's tiled order (bw, bh: the texture in blocks; whole 8x8 tiles)."""
    if bw % 8 or bh % 8:
        raise ValueError('only whole 8x8-block tiles')
    out = bytearray()
    for ty in range(bh // 8):
        for tx in range(bw // 8):
            for m in range(64):
                x, y = _morton(m)
                o = ((ty * 8 + y) * bw + tx * 8 + x) * size
                out += linear[o:o + size]
    return bytes(out)


def tiled_index(bx, by, bw):
    """Where block (bx, by) of a texture bw blocks wide is in the PS4's tiled order."""
    m = 0
    for b in range(3):
        m |= ((bx % 8) >> b & 1) << (2 * b) | ((by % 8) >> b & 1) << (2 * b + 1)
    return ((by // 8) * (bw // 8) + bx // 8) * 64 + m


def unswizzle(tiled, bw, bh, size=16):
    out = bytearray(len(tiled))
    i = 0
    for ty in range(bh // 8):
        for tx in range(bw // 8):
            for m in range(64):
                x, y = _morton(m)
                o = ((ty * 8 + y) * bw + tx * 8 + x) * size
                out[o:o + size] = tiled[i * size:(i + 1) * size]
                i += 1
    return bytes(out)


# ---- DCX (DFLT) and TPF ----

DCX_DATA = 0x4c


def read_dcx(data):
    """The DCX's (header, content)."""
    if data[:4] != b'DCX\0' or data[0x18:0x1c] != b'DCS\0' or data[0x28:0x2c] != b'DFLT' or data[0x44:0x48] != b'DCA\0':
        raise ValueError('not a DFLT DCX file')
    usize, csize = struct.unpack_from('>II', data, 0x1c)
    content = zlib.decompress(data[DCX_DATA:DCX_DATA + csize])
    if len(content) != usize:
        raise ValueError('DCX: wrong uncompressed size')
    return data[:DCX_DATA], content


def write_dcx(header, content):
    packed = zlib.compress(content, 9)
    head = bytearray(header)
    struct.pack_into('>II', head, 0x1c, len(content), len(packed))
    return bytes(head) + packed


def tpf_textures(tpf):
    """{name: (offset, size, format, dxgi, width, height, mipmaps)} of a PS4 TPF."""
    if tpf[:4] != b'TPF\0':
        raise ValueError('not a TPF')
    _, count, platform, _, encoding = struct.unpack_from('<iiBBB', tpf, 4)
    if platform != PS4:
        raise ValueError(f'TPF platform {platform}, not PS4')
    pos, textures = 0x10, {}
    for _ in range(count):
        off, size, fmt, _, mips, _ = struct.unpack_from('<IiBBBB', tpf, pos)
        w, h = struct.unpack_from('<hh', tpf, pos + 12)
        name_off, has_float, dxgi = struct.unpack_from('<IiI', tpf, pos + 24)
        pos += 36
        if has_float:
            pos += 8 + struct.unpack_from('<i', tpf, pos + 4)[0]
        if encoding == 1:
            end = name_off
            while tpf[end:end + 2] != b'\0\0':
                end += 2
            name = tpf[name_off:end].decode('utf-16-le')
        else:
            name = tpf[name_off:tpf.index(b'\0', name_off)].decode('shift_jis')
        textures[name] = (off, size, fmt, dxgi, w, h, mips)
    return textures


def icon_blocks(path):
    w, h, rgba = read_png(path)
    if (w, h) != (32, 32):
        raise ValueError(f'{path}: {w}x{h}, not 32x32')
    return swizzle(encode_bc7(w, h, rgba), 8, 8)


def patch_area(out, texture, x, y, icon_path, checksum):
    """Draws the icon (its middle AREA_SIZE pixels) at (x, y) of a BC7 texture in the TPF bytes `out`."""
    import hashlib
    off, size, fmt, dxgi, w, h, mips = texture
    if (fmt, dxgi, mips) != (BC7_FORMAT, BC7_DXGI, 1) or size != w * h or w % 32 or h % 32:
        raise ValueError(f'HUD texture: unexpected layout ({w}x{h} format {fmt}/{dxgi})')
    bw = w // 4
    bx0, by0 = x // 4, y // 4
    bx1, by1 = -(-(x + AREA_SIZE) // 4), -(-(y + AREA_SIZE) // 4)
    spots = [off + tiled_index(bx, by, bw) * 16 for by in range(by0, by1) for bx in range(bx0, bx1)]
    if hashlib.sha256(b''.join(out[p:p + 16] for p in spots)).hexdigest() != checksum:
        raise ValueError(f'HUD button at ({x},{y}): not the 1.09 texture')
    iw, ih, rgba = read_png(icon_path)
    cut = (iw - AREA_SIZE) // 2
    pw, ph = (bx1 - bx0) * 4, (by1 - by0) * 4
    canvas = bytearray(pw * ph * 4)  # transparent
    for row in range(AREA_SIZE):
        src = ((row + cut) * iw + cut) * 4
        dst = ((y - by0 * 4 + row) * pw + x - bx0 * 4) * 4
        canvas[dst:dst + AREA_SIZE * 4] = rgba[src:src + AREA_SIZE * 4]
    blocks = encode_bc7(pw, ph, bytes(canvas))
    for i, p in enumerate(spots):
        out[p:p + 16] = blocks[i * 16:(i + 1) * 16]


def patch(dcx_bytes, icons=ICONS):
    """The DCX with the KG_* textures and the HUD's buttons replaced; raises ValueError for a file that is not the one
    expected."""
    header, tpf = read_dcx(dcx_bytes)
    textures = tpf_textures(tpf)
    out = bytearray(tpf)
    for name, x, y, icon, checksum in AREAS:
        if name not in textures:
            raise ValueError(f'{name} is not in this file (not v1.09 menu/common.tpf.dcx?)')
        patch_area(out, textures[name], x, y, Path(icons) / icon, checksum)
    encoded = {}
    for name, icon in REPLACE.items():
        if name not in textures:
            raise ValueError(f'{name} is not in this file (not v1.09 menu/common.tpf.dcx?)')
        off, size, fmt, dxgi, w, h, mips = textures[name]
        if (fmt, dxgi, w, h, mips, size) != (BC7_FORMAT, BC7_DXGI, 32, 32, 1, 1024):
            raise ValueError(f'{name}: unexpected texture ({w}x{h} format {fmt}/{dxgi}, {mips} mips, {size} bytes)')
        if icon not in encoded:
            encoded[icon] = icon_blocks(Path(icons) / icon)
        out[off:off + size] = encoded[icon]
    return write_dcx(header, bytes(out))


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    source, target = Path(argv[1]), Path(argv[2])
    try:
        result = patch(source.read_bytes())
    except (OSError, ValueError, zlib.error) as e:
        print(f'button_icons: {e}', file=sys.stderr)
        return 1
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(result)
    print(f'{target}: {len(REPLACE)} key guide buttons and {len(AREAS)} HUD buttons replaced')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
