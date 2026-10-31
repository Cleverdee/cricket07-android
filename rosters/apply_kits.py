"""Coloured kits for the league sides, painted from one template (New South Wales' two-tone kit, from your game).

Sky blue on the template becomes the team's main colour, navy its second, keeping the cloth shading. The template's
badges and sponsors are cleared, and the team's logo goes on the chest if rosters/logos/ has one (apply_logos.py).
Colours are in research/kits.json and only approximate the real kits. White and sweater kits are left as they are."""
import io
import json
import os
import re
import struct

import numpy as np
from PIL import Image

import apply_logos
import ea_assets
import gobpatch
import roster_build

TEMPLATE = "nsw"
PARTS = ("colouredkit1", "colouredkit2", "colouredkit3")
ENTRY_HEADER = 16
DXT5 = 0x62
ARGB8888 = 0x7D
DDS_HEADER = 128

SKY_BRIGHTNESS = 0.72        # the template's typical sky blue and navy, so shading stays around 1
NAVY_BRIGHTNESS = 0.28
NAVY_BELOW = 0.42
BLUE_MIN_SATURATION = 0.12

# marks on the 512x512 shirt-and-trousers sheet, and how far away the clean cloth to cover each one is
MARKS = (((312, 44, 342, 76), (30, 0)), ((316, 136, 342, 165), (26, 0)), ((40, 270, 75, 300), (-35, 0)),
         ((430, 225, 480, 355), (0, -130)))
CHEST = (308, 40, 40)        # x, y, size of the logo on the shirt front
CHEST_ROTATION = 90          # the front lies on its side on the sheet, collar to the left


def paint(img, primary, secondary):
    a = np.asarray(img).astype(float) / 255
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    top = a[..., :3].max(-1)
    saturation = (top - a[..., :3].min(-1)) / np.maximum(top, 1e-3)
    blue = (b >= r) & (b >= g * 0.98) & (saturation > BLUE_MIN_SATURATION)
    out = a.copy()
    for mask, colour, shade in ((blue & (top > NAVY_BELOW), primary, top / SKY_BRIGHTNESS),
                                (blue & (top <= NAVY_BELOW), secondary, np.clip(top / NAVY_BRIGHTNESS, 0.35, 1.25))):
        for i in range(3):
            out[..., i][mask] = np.clip(colour[i] / 255 * shade[mask], 0, 1)
    return Image.fromarray((out * 255).astype("uint8"), "RGBA")


def clear_marks(img):
    a = np.asarray(img).copy()
    for (x0, y0, x1, y1), (dx, dy) in MARKS:
        a[y0:y1, x0:x1] = a[y0 + dy:y1 + dy, x0 + dx:x1 + dx].copy()
    return Image.fromarray(a, "RGBA")


def encode(img, code):
    if code == DXT5:
        buf = io.BytesIO()
        img.save(buf, "DDS", pixel_format="DXT5")
        return buf.getvalue()[DDS_HEADER:]
    if code == ARGB8888:
        return img.tobytes("raw", "BGRA")
    raise ValueError(f"kit texture format {code:#x}")


def replace_image(fsh, img):
    data = bytearray(fsh)
    off = struct.unpack_from("<I", data, 20)[0]
    block = encode(img, data[off] & 0x7F)
    data[off + ENTRY_HEADER:off + ENTRY_HEADER + len(block)] = block
    return bytes(data)


def decode(fsh):
    off = struct.unpack_from("<I", fsh, 20)[0]
    return ea_assets._decode_fsh_image(fsh, off)[0].convert("RGBA")


def name_colours(xml, slot, colour):
    """the slot's Coloured kit, with its shirt lettering in the given colour; a slot without one gets it"""
    m = re.search(r'(<team [^>]*\bid="%d"[^>]*>)(.*?)(</team>)' % slot, xml, re.S)
    body = re.sub(r'\s*<kit name="Coloured"[^>]*/>', "", m.group(2))
    kit = '\r\n\t\t\t<kit name="Coloured" namecolour="%s" numbercolour="%s"/>' % (colour, colour)
    return xml[:m.start(2)] + kit + body + xml[m.end(2):]


def replacements(gob, player_manager):
    """{entry: new bytes} for every league team in research/kits.json, plus the updated player manager XML"""
    colours = json.load(open(os.path.join(roster_build.RESEARCH, "kits.json"), encoding="utf-8"))
    new = {}
    xml = player_manager.decode("utf-8")
    with open(gob, "rb") as f:
        entries = gobpatch.read_index(f)

        def read(path):
            _, off, size = entries[gobpatch.name_hash(path)]
            f.seek(off)
            return ea_assets.maybe_decompress(f.read(size))

        template = {p: read(f"teams/{TEMPLATE}/{p}.fsh") for p in PARTS}
        images = {p: decode(template[p]) for p in PARTS}
        images[PARTS[0]] = clear_marks(images[PARTS[0]])
        for slot, team in apply_logos.league_slots():
            kit = colours[team]
            location = re.search(r'<team [^>]*location="([^"]+)"[^>]*\bid="%d"' % slot, xml).group(1)
            if gobpatch.name_hash(f"teams/{location}/{PARTS[0]}.fsh") not in entries:
                print(f"    {team}: {location} has no coloured kit files")
                continue
            for part in PARTS:
                img = paint(images[part], kit["primary"], kit["secondary"])
                logo = apply_logos.logo_image(team)
                if part == PARTS[0] and logo:
                    x, y, size = CHEST
                    small = apply_logos.fit(logo, size, size).rotate(CHEST_ROTATION)
                    img.paste(small, (x, y), small)
                new[gobpatch.name_hash(f"teams/{location}/{part}.fsh")] = replace_image(template[part], img)
            xml = name_colours(xml, slot, kit["lettering"])
            print(f"    {team}: kit in teams/{location}")
    new[roster_build.PLAYERMGR] = xml.encode("utf-8")
    return new
