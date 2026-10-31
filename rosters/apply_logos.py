"""Team logos for the league sides, from your own image files.

Put one image per team in rosters/logos/, named after the team ("Chennai Super Kings.png"). Each one replaces the logo
and small flag in the team texture of the slot that team took over (see roster_build.py). A team with no image keeps
the old logo. The images are other people's trademarks, so the repository doesn't include them. Needs Pillow."""
import glob
import io
import json
import os
import re
import struct

from PIL import Image

import ea_assets
import gobpatch
import roster_build

HERE = os.path.dirname(os.path.abspath(__file__))
LOGOS = os.path.join(HERE, "logos")
TEAMINFO_ENTRY = roster_build.TEAMINFO
DXT5 = 0x62
FSH_DIRECTORY = 16      # the file's header; each directory entry is 8 bytes after it
ENTRY_HEADER = 16       # each image's own header
DDS_HEADER = 128
LARGE_LOGO_BYTES = 256 * 256   # DXT5: one byte per pixel
MAX_LOGO_TEXTURE = 400000    # bytes, stored; skips the many larger textures


def league_slots():
    """(slot, team name) for every league team"""
    out = []
    for file, slots in (("psl.json", roster_build.PSL_SLOTS), ("ipl.json", roster_build.IPL_SLOTS),
                        ("bbl.json", roster_build.BBL_SLOTS), ("cpl.json", roster_build.CPL_SLOTS)):
        teams = json.load(open(os.path.join(roster_build.RESEARCH, file), encoding="utf-8"))["teams"]
        out += [(slot, team["name"]) for slot, team in zip(slots, teams)]
    return out


def texture_name(teaminfo_xml, slot):
    m = re.search(r'<team Index="%d"[^>]*?LogoAbbreviation="([^"]+)"' % slot, teaminfo_xml)
    return "frontend/textures/team_%s.fsh" % m.group(1).lower()


def logo_image(team):
    """the team's image from rosters/logos/, or None"""
    files = glob.glob(glob.escape(os.path.join(LOGOS, team)) + ".*")
    return Image.open(files[0]).convert("RGBA") if files else None


def encode_dxt5(img):
    buf = io.BytesIO()
    img.save(buf, "DDS", pixel_format="DXT5")
    return buf.getvalue()[DDS_HEADER:]


def fit(logo, w, h):
    """the logo scaled to fit, centred on transparency"""
    scale = min(w / logo.width, h / logo.height)
    small = logo.resize((max(1, round(logo.width * scale)), max(1, round(logo.height * scale))), Image.LANCZOS)
    canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    canvas.paste(small, ((w - small.width) // 2, (h - small.height) // 2), small)
    return canvas


def patch_texture(data, logo):
    """every logo and flag image in a team texture, in place, keeping the file's layout"""
    data = bytearray(data)
    count = struct.unpack_from("<I", data, 8)[0]
    done = 0
    for i in range(count):
        tag = bytes(data[FSH_DIRECTORY + 8 * i:FSH_DIRECTORY + 8 * i + 4])
        off = struct.unpack_from("<I", data, FSH_DIRECTORY + 8 * i + 4)[0]
        if tag[:2] not in (b"l1", b"l2", b"l3", b"fl"):
            continue
        code = data[off] & 0x7F
        w, h = struct.unpack_from("<HH", data, off + 4)
        if code != DXT5 or w % 4 or h % 4:
            print(f"    left {tag} alone: format {code:#x}, {w}x{h}")
            continue
        block = encode_dxt5(fit(logo, w, h))
        data[off + ENTRY_HEADER:off + ENTRY_HEADER + len(block)] = block
        done += 1
    return bytes(data), done


def large_logo(data):
    """the pixels of a texture's 256x256 logo, or None if it has none"""
    if data[:4] != b"SHPI":
        return None
    for i in range(struct.unpack_from("<I", data, 8)[0]):
        if data[FSH_DIRECTORY + 8 * i:FSH_DIRECTORY + 8 * i + 2] == b"l1":
            off = struct.unpack_from("<I", data, FSH_DIRECTORY + 8 * i + 4)[0]
            return bytes(data[off:off + ENTRY_HEADER + LARGE_LOGO_BYTES])
    return None


def replacements(gob):
    """{entry: new bytes} for the league teams that have an image; the rest keep their old logos.
    The game keeps a second copy of each team's logos for the match intro, under names that can't be worked out
    from the team, so those are found by holding the same large logo as the team's texture."""
    new, missing = {}, []
    with open(gob, "rb") as f:
        entries = gobpatch.read_index(f)

        def read(name):
            _, off, size = entries[name]
            f.seek(off)
            return ea_assets.maybe_decompress(f.read(size))

        textures = {}
        for name, (_, _, size) in entries.items():
            if name.endswith(".fsh") and size <= MAX_LOGO_TEXTURE:
                pixels = large_logo(read(name))
                if pixels:
                    textures.setdefault(pixels, []).append(name)
        teaminfo = read(TEAMINFO_ENTRY).decode("latin1")
        team_textures = {gobpatch.name_hash("frontend/textures/team_%s.fsh" % code.lower())
                         for code in re.findall(r'LogoAbbreviation="([^"]+)"', teaminfo)}
        for slot, team in league_slots():
            logo = logo_image(team)
            if not logo:
                missing.append(team)
                continue
            path = texture_name(teaminfo, slot)
            entry = gobpatch.name_hash(path)
            if entry not in entries:
                print(f"    {team}: no {path} in the archive")
                continue
            copies = textures.get(large_logo(read(entry)), [entry])
            if len(team_textures & set(copies)) > 1:     # e.g. Australia A has Australia's logo: its copies are shared
                print(f"    {team}: logo shared with another team, so the intro keeps the old one")
                copies = [entry]
            for name in copies:
                new[name], done = patch_texture(read(name), logo)
            print(f"    {team}: logo in {len(copies)} textures")
    if missing:
        print("  no logo image, keeping the old one:", ", ".join(missing))
    return new
