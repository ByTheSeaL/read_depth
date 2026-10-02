"""Generate the extension icons: a teal tile with lines of text, one word picked out in amber.

Pure stdlib PNG writing so there is no image-library dependency just for four
icons. The Android launcher icon (res/drawable/ic_launcher_foreground.xml)
draws the same shapes as a vector.

    python3 scripts/make_icons.py
"""

import struct
import zlib

from pathlib import Path


TILE      = (0x0E, 0x7C, 0x7B)
LINE      = (0x7F, 0xC4, 0xC3)
HIGHLIGHT = (0xFF, 0xC8, 0x57)
OUT_DIR   = Path(__file__).resolve().parent.parent / "chrome-extension" / "icons"

# (top, bottom, left, right) as fractions of the tile, and the fill.
# Three lines of text; on the middle one, a single word is highlighted.
SHAPES = [
	(0.25, 0.33, 0.20, 0.80, LINE),
	(0.42, 0.56, 0.20, 0.30, LINE),
	(0.40, 0.58, 0.35, 0.65, HIGHLIGHT),
	(0.42, 0.56, 0.70, 0.80, LINE),
	(0.65, 0.73, 0.20, 0.62, LINE),
]

CORNER_RADIUS_FRACTION = 0.22


def outside_rounded_square(x, y, size, radius):
	for cx, cy in ((radius, radius), (size - radius, radius), (radius, size - radius), (size - radius, size - radius)):
		in_x = x < radius if cx == radius else x > size - radius
		in_y = y < radius if cy == radius else y > size - radius

		if in_x and in_y and (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 > radius ** 2:
			return True

	return False


def render(size: int) -> bytes:
	radius = max(2.0, size * CORNER_RADIUS_FRACTION)
	rows   = []

	for y in range(size):
		row = bytearray([0])

		for x in range(size):
			if outside_rounded_square(x, y, size, radius):
				row += bytes((0, 0, 0, 0))
				continue

			colour = TILE

			for top, bottom, left, right, fill in SHAPES:
				if top * size <= y + 0.5 < bottom * size and left * size <= x + 0.5 < right * size:
					colour = fill

			row += bytes(colour) + b"\xff"

		rows.append(bytes(row))

	def chunk(kind: bytes, data: bytes) -> bytes:
		body = kind + data
		return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

	header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)

	return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")


OUT_DIR.mkdir(parents=True, exist_ok=True)

for size in (16, 32, 48, 128):
	path = OUT_DIR / f"icon{size}.png"
	path.write_bytes(render(size))
	print(f"wrote {path} ({path.stat().st_size} bytes)")
