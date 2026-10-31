"""Makes a copy of your game's Data/data.gob with the 2026 rosters. The original file is not changed.

  apply_rosters.py <your data.gob> <output data.gob>

Reads the player database and XML files out of your archive, rebuilds the squads from research/, then appends the
results to the copy (gobpatch.py)."""
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

sys.path.insert(0, os.path.join(HERE, "..", "tools"))
import apply_kits
import apply_logos
import ea_assets
import gobpatch
import roster_build
from cdf import CDF_ENTRY


def extract(gob, outdir):
    """the player database and every XML in the archive, unpacked"""
    with open(gob, "rb") as f:
        for name, (_, off, size) in gobpatch.read_index(f).items():
            if name == CDF_ENTRY or name.endswith(".xml"):
                f.seek(off)
                with open(os.path.join(outdir, name), "wb") as o:
                    o.write(ea_assets.maybe_decompress(f.read(size)))


def main(src, dst):
    with tempfile.TemporaryDirectory() as tmp:
        gobdir, out = os.path.join(tmp, "gob"), os.path.join(tmp, "out")
        os.makedirs(gobdir)
        extract(src, gobdir)
        roster_build.GOB = gobdir
        roster_build.build(out, os.path.join(gobdir, CDF_ENTRY))
        os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
        shutil.copyfile(src, dst + ".part")
        new = {}
        for f in sorted(os.listdir(out)):
            if f == "report.txt":
                continue
            new[CDF_ENTRY if f.endswith(".cdf") else f] = open(os.path.join(out, f), "rb").read()
        new.update(apply_logos.replacements(src))
        new.update(apply_kits.replacements(src, new[roster_build.PLAYERMGR]))
        gobpatch.patch(dst + ".part", new)
    os.replace(dst + ".part", dst)
    print("wrote", dst)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
