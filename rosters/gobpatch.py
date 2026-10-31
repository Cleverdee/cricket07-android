"""Replace entries of an EA BIG archive (data.gob) in place: the new data is appended at the end of the file
and the entry's offset/size in the index are repointed; the old data stays as dead space.
New data is RefPack-wrapped with literal-only blocks, so the game reads it through its usual decompressor."""
import hashlib
import os
import struct
import sys


def name_hash(path):
    p = path.lower().replace("\\", "/")
    stem, dot, ext = p.rpartition(".")
    return hashlib.md5(stem.encode()).hexdigest() + "." + ext


def refpack_literal(data):
    """RefPack stream holding only literal blocks (valid, no compression)."""
    n = len(data)
    out = bytearray()
    if n >= 1 << 24:
        out += bytes([0x90, 0xFB]) + n.to_bytes(4, "big")
    else:
        out += bytes([0x10, 0xFB]) + n.to_bytes(3, "big")
    pos = 0
    while n - pos > 3:
        run = min(112, (n - pos) & ~3)       # 0xE0-0xFB: 4..112 literals, multiple of 4
        out.append(0xE0 + (run >> 2) - 1)
        out += data[pos:pos + run]
        pos += run
    rest = n - pos                            # 0xFC-0xFF: end of stream with 0-3 literals
    out.append(0xFC + rest)
    out += data[pos:]
    return bytes(out)


def read_index(f):
    f.seek(0)
    head = f.read(16)
    assert head[:4] in (b"BIGF", b"BIG4"), head[:4]
    count = struct.unpack_from(">I", head, 8)[0]
    idx_len = struct.unpack_from(">I", head, 12)[0]
    f.seek(0)
    raw = f.read(idx_len + 16 + 4096)
    pos, entries = 16, {}
    for _ in range(count):
        off, size = struct.unpack_from(">II", raw, pos)
        end = raw.index(b"\0", pos + 8)
        entries[raw[pos + 8:end].decode("latin1")] = (pos, off, size)
        pos = end + 1
    return entries


def patch(gob, replacements):
    """replacements: {entry name or game path: new uncompressed bytes}"""
    with open(gob, "r+b") as f:
        entries = read_index(f)
        f.seek(0, os.SEEK_END)
        end = f.tell()
        for key, data in replacements.items():
            name = key if key in entries else name_hash(key)
            ipos, _, _ = entries[name]
            blob = refpack_literal(data)
            end = (end + 0xFF) & ~0xFF
            f.seek(end)
            f.write(blob)
            f.seek(ipos)
            f.write(struct.pack(">II", end, len(blob)))
            print(f"  {name}: {len(data)} bytes at {end:#x}")
            end += len(blob)
        f.truncate(end)
        f.seek(4)
        f.write(struct.pack("<I", end))      # archive size (little-endian)


if __name__ == "__main__":
    # gobpatch.py <data.gob> <entry-or-path>=<file> ...
    patch(sys.argv[1], {a.split("=", 1)[0]: open(a.split("=", 1)[1], "rb").read() for a in sys.argv[2:]})
