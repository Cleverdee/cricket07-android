"""Cricket 07 player database (frontend/database/cricket.0.0.12.0.cdf) helpers."""
import re
import struct

CDF_ENTRY = "113964e05e69ab31d264fb8f15c58e5c.cdf"   # its name inside data.gob
R = 360
T0, TS, NT = 0x69780, 2808, 63


def load(path):
    return bytearray(open(path, 'rb').read())


def players(b):
    """{name: (record index, id)} for named records"""
    out = {}
    for k in range(len(b) // R):
        r = b[k * R:(k + 1) * R]
        m = re.match(rb"[A-Z][a-zA-Z'. -]{1,30}\x00", bytes(r[8:]))
        if m and k * R < T0:
            out[m.group()[:-1].decode('latin1')] = (k, struct.unpack_from('<H', r, 4)[0])
    return out


def rec(b, k):
    return bytes(b[k * R:(k + 1) * R])


def bits(r, start, end):
    """the record bytes start..end as an int (little-endian)"""
    return int.from_bytes(r[start:end], 'little')
