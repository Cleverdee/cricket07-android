"""Builds the 2026 rosters into Cricket 07's player database (cricket.0.0.12.0.cdf) and team XMLs.

Each new player overwrites one record of the team being rebuilt. Abilities come from a template player of the same
role, bowling style and batting hand (2006 database), with the batting and bowling blocks shifted to levels worked
out from the player's real stats. Career stats are cleared, face = a generic one matching the skin hint.
Run through apply_rosters.py."""
import glob
import hashlib
import json
import os
import re
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(__file__))
from cdf import R, T0, TS, NT, load, players

RESEARCH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "research")
GOB = None   # folder of files unpacked from data.gob (apply_rosters.py sets it)
TEAMINFO, TEAMLOOK, PLAYERMGR = ("be2970b0ada8e42cf891ce60ffe69575.xml", "49ad277eb4663535abe0d4b1ec726023.xml",
                                 "730eef3fd7726e43de955f21083ab000.xml")

NAT = {"Australia": 1, "Bangladesh": 2, "Canada": 4, "England": 8, "India": 16, "Kenya": 32, "Afghanistan": 32,
       "Namibia": 64, "Netherlands": 128, "New Zealand": 256, "Pakistan": 512, "South Africa": 1024,
       "Sri Lanka": 2048, "West Indies": 4096, "Zimbabwe": 8192, "United States": 16384, "USA": 16384,
       "Scotland": 65536, "Ireland": 131072, "Bermuda": 262144}
INTL = {"Australia": 0, "Bangladesh": 1, "England": 7, "India": 13, "Afghanistan": 15, "New Zealand": 23,
        "Pakistan": 27, "South Africa": 30, "Sri Lanka": 32, "West Indies": 39, "Zimbabwe": 43,
        "Ireland": 60}
COUNTIES = {"Derbyshire": 5, "Durham": 6, "Essex": 8, "Glamorgan": 10, "Gloucestershire": 11, "Hampshire": 12, "Kent": 14,
            "Lancashire": 16, "Leicestershire": 17, "Middlesex": 18, "Northamptonshire": 24, "Nottinghamshire": 26,
            "Somerset": 29, "Surrey": 33, "Sussex": 34, "Warwickshire": 38, "Worcestershire": 41, "Yorkshire": 42}
# the game has 63 team slots: the leagues take those of the Australian states, the A sides, invitation and World XIs,
# and six small nations. Each slot keeps its old logo and kit.
BBL_SLOTS = [22, 28, 31, 35, 37, 40, 44, 45]          # NSW, Queensland, S Australia, Tasmania, Victoria, W Australia, Australia A, India A
IPL_SLOTS = [46, 47, 48, 49, 50, 52, 54, 55, 56, 57]  # Pakistan A, SA A, WI A, four XIs, World XIs, Scottish Saltires
CPL_SLOTS = [62, 3, 20, 21, 58, 59, 61]               # Ireland County, Canada, Namibia, Netherlands, United States, Bermuda, Scotland
PSL_SLOTS = [2, 4, 9, 25, 36, 19, 51, 53]   # Border, Canterbury, Gauteng, N. Districts, Trinidad, Masters XI, Duke's, Minister's

# template players (2006 database): (name, role, bowling style, batting hand)
TEMPLATES = [
    ("B.Lee", "bowl", "RF", "R"), ("S.Bond", "bowl", "RF", "R"), ("S.Harmison", "bowl", "RF", "R"),
    ("S.Tait", "bowl", "RF", "R"), ("G.McGrath", "bowl", "RFM", "R"), ("M.Ntini", "bowl", "RFM", "R"),
    ("M.Hoggard", "bowl", "RFM", "R"), ("J.Gillespie", "bowl", "RFM", "R"), ("S.Clark", "bowl", "RFM", "R"),
    ("A.Noffke", "bowl", "RFM", "R"), ("B.Hilfenhaus", "bowl", "RFM", "R"), ("C.Langeveldt", "bowl", "RFM", "R"),
    ("J.Anderson", "bowl", "RFM", "L"), ("K.Mills", "bowl", "RM", "R"), ("A.Bichel", "bowl", "RM", "R"),
    ("M.Johnson", "bowl", "LF", "L"), ("N.Bracken", "bowl", "LFM", "R"), ("R.Sidebottom", "bowl", "LFM", "L"),
    ("J.Franklin", "allround", "LFM", "L"), ("D.Bollinger", "bowl", "LFM", "L"),
    ("A.Flintoff", "allround", "RF", "R"), ("J.Kallis", "allround", "RFM", "R"), ("S.Pollock", "allround", "RFM", "R"),
    ("S.Watson", "allround", "RFM", "R"), ("A.Hall", "allround", "RFM", "R"), ("I.Harvey", "allround", "RM", "R"),
    ("J.Oram", "allround", "RFM", "L"), ("L.Klusener", "allround", "RFM", "L"), ("P.Collingwood", "allround", "RM", "R"),
    ("S.Styris", "allround", "RM", "R"), ("J.Hopes", "allround", "RM", "R"),
    ("G.Swann", "bowl", "OS", "R"), ("R.Croft", "bowl", "OS", "R"), ("N.Hauritz", "bowl", "OS", "R"),
    ("J.Patel", "bowl", "OS", "R"), ("J.Botha", "allround", "OS", "R"), ("A.Symonds", "allround", "OS", "R"),
    ("S.Warne", "bowl", "LS", "R"), ("S.MacGill", "bowl", "LS", "R"), ("C.White", "allround", "LS", "R"),
    ("D.Vettori", "bowl", "SLA", "L"), ("M.Panesar", "bowl", "SLA", "L"), ("N.Boje", "allround", "SLA", "L"),
    ("A.Giles", "bowl", "SLA", "R"), ("R.Peterson", "allround", "SLA", "L"), ("B.Hogg", "bowl", "SLC", "L"),
    ("A.Gilchrist", "wk", "none", "L"), ("M.Boucher", "wk", "none", "R"), ("B.McCullum", "wk", "none", "R"),
    ("G.Jones", "wk", "none", "R"), ("B.Haddin", "wk", "none", "R"), ("M.Prior", "wk", "none", "R"),
    ("R.Ponting", "bat", "RM", "R"), ("D.Martyn", "bat", "RM", "R"), ("I.Bell", "bat", "RM", "R"),
    ("H.Gibbs", "bat", "OS", "R"), ("M.Vaughan", "bat", "OS", "R"), ("B.Hodge", "bat", "OS", "R"),
    ("K.Pietersen", "bat", "OS", "R"), ("M.Clarke", "bat", "SLA", "R"), ("D.Hussey", "bat", "OS", "R"),
    ("H.Amla", "bat", "none", "R"), ("G.Bailey", "bat", "none", "R"), ("L.Vincent", "bat", "none", "R"),
    ("P.Fulton", "bat", "RM", "R"), ("R.Key", "bat", "none", "R"),
    ("M.Hayden", "bat", "RM", "L"), ("J.Langer", "bat", "none", "L"), ("M.Hussey", "bat", "RM", "L"),
    ("M.Trescothick", "bat", "RM", "L"), ("A.Strauss", "bat", "none", "L"), ("S.Fleming", "bat", "none", "L"),
    ("P.Jaques", "bat", "none", "L"), ("S.Katich", "bat", "SLC", "L"), ("J.Rudolph", "bat", "LS", "L"),
    ("S.Marsh", "bat", "OS", "L"), ("M.North", "bat", "OS", "L"),
]
PACE = {"RF", "RFM", "RM", "LF", "LFM", "LM"}
SPIN = {"OS", "LS", "SLA", "SLC"}

FACES = {"asian": [41030, 41032, 41033, 41037, 41039, 41041, 41042, 41044, 41048, 41052, 41056],
         "dark": [41026, 41040, 41046, 41049, 41047, 41045, 41054],
         "white": [41000, 41002, 41003, 41004, 41005, 41006, 41008, 41009, 41010, 41011, 41012, 41013, 41014,
                   41017, 41018, 41019, 41020, 41021, 41022, 41023, 41024, 41025, 41027, 41028]}


# ---------------------------------------------------------------- ability block

def words(rec):
    return [struct.unpack_from("<I", rec, o)[0] for o in range(0x64, 0xAC, 4)]


def get6(rec, word, i):
    return (struct.unpack_from("<I", rec, 0x64 + 4 * word)[0] >> (6 * i)) & 63


def set6(rec, word, i, v):
    o = 0x64 + 4 * word
    w = struct.unpack_from("<I", rec, o)[0]
    w = (w & ~(63 << (6 * i))) | ((v & 63) << (6 * i))
    struct.pack_into("<I", rec, o, w)


BAT_FIELDS = [(1, i) for i in range(5)] + [(2, i) for i in range(5)] + [(3, i) for i in range(4)]
BOWL_FIELDS = [(4, 3), (4, 4)] + [(5, i) for i in range(5)] + [(6, i) for i in range(5)]


def level(rec, fields, skip_placeholder=False):
    v = [get6(rec, w, i) for w, i in fields]
    if skip_placeholder:
        v = [x for x in v if x not in (31, 33)]
    return sum(v) / len(v) if v else 0


def shift(rec, fields, target, skip_placeholder=False, lo=1, hi=62):
    cur = level(rec, fields, skip_placeholder)
    d = target - cur
    for w, i in fields:
        x = get6(rec, w, i)
        if skip_placeholder and x in (31, 33):
            continue
        set6(rec, w, i, max(lo, min(hi, round(x + d))))


# ---------------------------------------------------------------- ratings from stats

def num(v, default=None):
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def bat_target(p, t20=False):
    avg = num(p.get("bat_avg"), 15.0)
    sr = num(p.get("bat_sr"))
    if t20 or (num(p.get("tests"), 0) < 5 and sr):
        base_sr = 130.0 if t20 or num(p.get("t20is"), 0) > num(p.get("odis"), 0) else 85.0
        if sr:
            avg = avg * (sr / base_sr) ** 0.7
        if t20:
            avg *= 1.35     # T20 averages run low next to Test/ODI ones
    return max(31.0, min(60.0, 30.0 + 0.5 * avg))


def bowl_target(p, t20=False):
    if p.get("bowl") in (None, "none"):
        return None
    avg, econ, wk = num(p.get("bowl_avg")), num(p.get("econ")), num(p.get("wkts"), 0)
    if avg is None:
        return 38.0
    if t20:
        lv = 68 - 0.5 * avg - 1.5 * ((econ or 7.8) - 7.0)
    else:
        lv = 70 - 0.5 * avg
    if wk < 15:
        lv = min(lv, 44.0)
    elif wk < 40 and p.get("role") in ("bat", "wk"):
        lv = min(lv, 46.0)
    return max(30.0, min(59.0, lv))


# ---------------------------------------------------------------- helpers

def commentary_ids():
    out = {}
    for f in glob.glob(os.path.join(GOB, "*.xml")):
        for m in re.finditer(r'<gfcommentary id="(\d+)" name="([^"]+)"', open(f, encoding="latin1").read()):
            out.setdefault(m.group(2).strip().lower(), int(m.group(1)))
    return out


def pick_template(p, tpl_recs):
    role, style, hand = p.get("role", "bat"), p.get("bowl", "none") or "none", p.get("bat", "R")

    def cat(s):
        return "pace" if s in PACE else "spin" if s in SPIN else "none"

    best, score = None, -1e9
    for name, trole, tstyle, thand in TEMPLATES:
        if name not in tpl_recs:
            continue
        s = 0
        s += 40 if trole == role else 15 if {trole, role} <= {"bowl", "allround"} or {trole, role} <= {"bat", "allround"} else 0
        if role == "wk":
            s += 100 if trole == "wk" else -100
        s += 30 if tstyle == style else 12 if cat(tstyle) == cat(style) else 0
        if style in SPIN and tstyle not in SPIN:
            s -= 50
        if style in PACE and tstyle in SPIN:
            s -= 50
        if style.startswith("L") and style in PACE and tstyle.startswith("L"):
            s += 8
        s += 10 if thand == hand else 0
        s += (int(hashlib.md5((p["name"] + name).encode()).hexdigest()[:2], 16) % 7)   # spread equal picks
        if s > score:
            best, score = name, s
    return best


def short_name(name):
    """names hold 15 characters: longer ones become initial + surname ("Rahmanullah Gurbaz" -> "R.Gurbaz")"""
    if len(name) <= 15:
        return name
    parts = name.replace(".", " ").split()
    return (parts[0][0] + "." + parts[-1])[:15]


def encode_name(name):
    raw = short_name(name).encode("latin1", "replace")[:15]
    return raw + b"\0" * (16 - len(raw))


# career stats: four 20-byte blocks from +0x18 (Test, ODI, first-class, List A): u16 innings, not outs, balls
# bowled, wickets, runs conceded, runs, balls faced, catches; u32 matches << 22 | five-wicket hauls << 14
def stat_block(rec, i, m, avg, sr, wk, bavg, econ, keeper, test):
    m = int(max(0, m))
    o = 0x18 + 20 * i
    if m == 0:
        rec[o:o + 20] = bytes(20)
        return
    inns = int(m * (1.75 if test else 0.92))
    no = int(inns * (0.1 if avg >= 25 else 0.25))
    runs = int(avg * max(1, inns - no))
    bf = int(runs * 100 / max(20.0, sr))
    wk = int(max(0, wk)) if bavg else 0
    conceded = int(wk * bavg) if wk and bavg else 0
    balls = int(conceded * 6 / econ) if conceded and econ else 0
    catches = int(m * (2.2 if keeper and test else 1.2 if keeper else 0.45))
    fives = int(wk / 22) if test else 0
    v = [min(65535, x) for x in (inns, no, balls, wk, conceded, runs, bf, catches)]
    struct.pack_into("<8HI", rec, o, *v, (min(m, 1023) << 22) | (min(fives, 255) << 14))


def write_stats(rec, p, t20):
    avg = num(p.get("bat_avg"), 12.0)
    sr = num(p.get("bat_sr"), 75.0)
    bavg, econ = num(p.get("bowl_avg")), num(p.get("econ"))
    keeper = p.get("role") == "wk"
    if t20:                                   # PSL players: their T20 career goes in the one-day blocks
        m, wk = num(p.get("t20s"), 0), num(p.get("wkts"), 0)
        stat_block(rec, 0, 0, 0, 0, 0, None, None, keeper, True)
        stat_block(rec, 2, 0, 0, 0, 0, None, None, keeper, True)
        stat_block(rec, 1, m, avg, sr, wk, bavg, econ, keeper, False)
        stat_block(rec, 3, m, avg, sr, wk, bavg, econ, keeper, False)
        return
    t, od, i20 = num(p.get("tests"), 0), num(p.get("odis"), 0), num(p.get("t20is"), 0)
    wk = num(p.get("wkts"), 0)
    weight = 3 * t + od + 0.6 * i20 or 1
    wt, wo = wk * 3 * t / weight, wk * (od + 0.6 * i20) / weight
    tecon = 3.2 if econ or bavg else None
    stat_block(rec, 0, t, avg, 55.0, wt, bavg, tecon, keeper, True)
    stat_block(rec, 1, od + i20, avg * (0.9 if t else 1.0), sr, wo, bavg, econ or 5.5, keeper, False)
    stat_block(rec, 2, t * 1.8 + 15, avg, 55.0, wt * 1.9, bavg, tecon, keeper, True)
    stat_block(rec, 3, (od + i20) * 1.4 + 10, avg, sr, wo * 1.4, bavg, econ or 5.5, keeper, False)


SQUAD = 20   # squad slots per team record
TEST_SKIP = json.loads(os.environ.get("RB_TEST_SKIP", "{}"))   # {team: ["stats","looks","shift"]} for bisecting


def team_squad(b, t):
    base = T0 + t * TS
    return [struct.unpack_from("<H", b, base + 4 + 2 * i)[0] for i in range(SQUAD)]


def write_team(b, t, ids, captain, shirts):
    base = T0 + t * TS
    for i in range(SQUAD):          # +44 holds the team's own index, the next team's +0 its home ground
        struct.pack_into("<H", b, base + 4 + 2 * i, ids[i] if i < len(ids) else 0)
    struct.pack_into("<H", b, base + 50, captain)
    for i in range(SQUAD):
        b[base + 0xAE4 + i] = shirts[i] if i < len(shirts) else 0


# ---------------------------------------------------------------- main

def build(outdir, cdf_path):
    b = load(cdf_path)
    orig = bytes(b)
    P = players(b)
    by_id = {}                                       # every named record (names repeat, so not via P)
    for k in range(T0 // R):
        if re.match(rb"[A-Z][a-zA-Z'. -]{1,30}\x00", bytes(orig[k * R + 8:k * R + 40])):
            by_id.setdefault(struct.unpack_from("<H", orig, k * R + 4)[0], k)
    tpl_recs = {n: bytes(orig[P[n][0] * R:(P[n][0] + 1) * R + 4]) for n, *_ in TEMPLATES if n in P}
    comm = commentary_ids()
    used_ids = set(by_id)
    next_id = [33000]
    claimed = set()
    reused_ids = set()
    report = []

    def new_id():
        while next_id[0] in used_ids:
            next_id[0] += 1
        used_ids.add(next_id[0])
        return next_id[0]

    def write_player(k, p, nat, t20, skip=()):
        tname = pick_template(p, tpl_recs)
        tpl = bytearray(tpl_recs[tname])            # 364 bytes: this record + next record's face id
        rec = bytearray(orig[k * R:(k + 1) * R + 4])
        keep_head = rec[0:4]                         # previous record's face id
        rec[4:R] = tpl[4:R]
        rec[0:4] = keep_head
        # keep the record's own id: the game's per-player condition table (form, fitness: the selection
        # screen bars) is keyed by id and has no entries for new ones
        pid = struct.unpack_from("<H", orig, k * R + 4)[0]
        reused_ids.add(pid)
        struct.pack_into("<H", rec, 4, pid)
        cid = comm.get((p.get("surname") or p["name"].split(".")[-1].split()[-1]).lower(), 65535)
        struct.pack_into("<H", rec, 6, cid)
        rec[8:0x18] = encode_name(p["name"])
        if "stats" not in skip:
            write_stats(rec, p, t20)
        struct.pack_into("<I", rec, 220, nat)
        # batting hand
        if p.get("bat") in ("L", "R"):
            rec[0x89] = (rec[0x89] & ~1) | (1 if p["bat"] == "L" else 0)
        # abilities
        bt = bat_target(p, t20)
        bw = bowl_target(p, t20)
        if "shift" not in skip:
            shift(rec, BAT_FIELDS, bt)
        if bw is not None and "shift" not in skip:
            shift(rec, BOWL_FIELDS, bw, skip_placeholder=True)
        # date of birth
        try:
            y, m, d = (int(x) for x in str(p.get("dob", ""))[:10].split("-"))
            rec[352], rec[353], rec[354] = d, m - 1, y - 1900
        except ValueError:
            pass
        # appearance (face morphs, hair, skin, face id) from a 2006 player of the same skin: the slot's own
        # player when he matches, else one from the pool; template looks can name face parts that don't exist
        # for another skin, and the game then waits forever for the missing texture
        skin = p.get("skin", "white")
        donor = k if skin_of.get(k) == skin else looks[skin][zlib.crc32(p["name"].encode()) % len(looks[skin])]
        d = orig[donor * R:(donor + 1) * R + 4] if "looks" not in skip else tpl
        rec[0x90:0xAC] = d[0x90:0xAC]
        rec[0xC4:0xDC] = d[0xC4:0xDC]
        rec[R:R + 4] = d[R:R + 4]
        b[k * R:(k + 1) * R + 4] = rec
        report.append(f"    {p['name']:<24} {p.get('role','?'):<8} {p.get('bowl','?'):<4} {p.get('bat','?')}  "
                      f"bat {bt:4.1f} bowl {bw if bw is None else round(bw,1)!s:>5}  from {tname}")
        return pid

    # extra records: players in no squad, then those a rebuilt team didn't need (every team is rebuilt)
    in_teams = {by_id[i] for t in range(NT) for i in team_squad(orig, t) if i in by_id}
    spare = sorted(set(by_id.values()) - in_teams)

    built, built_in, built_dob = {}, {}, {}

    def same_player(key, p):
        """names repeat (two J.Smiths), so a date of birth has to agree when both are known"""
        return key in built and (not p.get("dob") or not built_dob.get(key) or p["dob"] == built_dob[key])
    skin_of, looks = {}, {"white": [], "asian": [], "dark": []}
    for k in set(by_id.values()):
        face = struct.unpack_from("<I", orig, (k + 1) * R)[0]
        for sk, faces in FACES.items():
            if face in faces:
                skin_of[k] = sk
                looks[sk].append(k)
    for sk in looks:
        looks[sk].sort()

    def rebuild(t, label, squad, nat_of, t20):
        cap = [p for p in squad if p.get("captain")][:1]
        squad = (cap + [p for p in squad if p not in cap])[:SQUAD]     # captain first; drops the last-listed
        pool = [by_id[i] for i in team_squad(orig, t) if i in by_id and by_id[i] not in claimed]
        while len(pool) < min(SQUAD, len(squad)) and spare:    # players in no squad at all
            k = spare.pop()
            if k not in claimed:
                pool.append(k)
        if len(pool) < 16:
            raise SystemExit(f"{label}: only {len(pool)} free records for {len(squad)} players")
        if len(pool) < min(SQUAD, len(squad)):   # keep the captain, drop the last-listed (fringe) players
            cap = [p for p in squad if p.get("captain")][:1]
            rest = [p for p in squad if p not in cap]
            squad = cap + rest[:len(pool) - len(cap)]
            report.append(f"  {label}: {len(pool)} records free, squad cut to {len(squad)}")
        report.append(f"  {label} (team {t})")
        ids, shirts, captain = [], [], None
        taken = set()
        pool = iter(pool)
        for p in squad[:SQUAD]:
            keys = [x.lower() for x in (p.get("full_name"), p["name"]) if x]
            key = next((x for x in keys if same_player(x, p)), None)
            if key:                                  # already made for another team (e.g. national side)
                pid = built[key]
                report.append(f"    {p['name']:<24} (same player as in {built_in[key]})")
            else:
                k = next(pool)
                claimed.add(k)
                pid = write_player(k, p, nat_of(p), t20, TEST_SKIP.get(label, ()))
                for x in keys:
                    built.setdefault(x, pid)
                    built_in.setdefault(x, label)
                    built_dob.setdefault(x, p.get("dob"))
            ids.append(pid)
            if p.get("captain") and captain is None:
                captain = pid
            s = p.get("shirt")
            s = int(s) if isinstance(s, (int, float)) or (isinstance(s, str) and s.isdigit()) else None
            if s is None or s in taken or not 0 < s < 256:
                s = next(x for x in range(1, 100) if x not in taken)
            taken.add(s)
            shirts.append(s)
        write_team(b, t, ids, captain or ids[0], shirts)
        spare.extend(k for k in pool if k not in claimed)

    # international teams
    intl = {}
    for f in sorted(glob.glob(os.path.join(RESEARCH, "intl_*.json"))):
        intl.update(json.load(open(f, encoding="utf-8")))
    for team, squad in intl.items():
        if team not in INTL:
            print("skipping unknown team", team)
            continue
        rebuild(INTL[team], team, squad, lambda p, team=team: NAT[team], t20=False)

    # leagues: T20 figures, and the team's slot takes its name
    renames = {15: ("Afghanistan", "Afghanistan", "AFG")}
    for file, slots, home in (("psl.json", PSL_SLOTS, "Pakistan"), ("ipl.json", IPL_SLOTS, "India"),
                              ("bbl.json", BBL_SLOTS, "Australia"), ("cpl.json", CPL_SLOTS, "West Indies")):
        teams = json.load(open(os.path.join(RESEARCH, file), encoding="utf-8"))["teams"]
        if len(teams) != len(slots):
            raise SystemExit(f"{file}: {len(teams)} teams for {len(slots)} slots")
        for slot, team in zip(slots, teams):
            rebuild(slot, team["name"], team["players"],
                    lambda p, home=home: NAT.get(p.get("country", home), NAT.get(home, NAT["West Indies"])), t20=True)
            renames[slot] = (team["name"], team.get("short", team["name"])[:12], team.get("abbr", "T20")[:3].upper())

    # counties: first-class figures in the Test fields
    for file in ("county_a.json", "county_b.json"):
        for team in json.load(open(os.path.join(RESEARCH, file), encoding="utf-8"))["teams"]:
            rebuild(COUNTIES[team["name"]], team["name"], team["players"], lambda p: NAT.get(p.get("country"), NAT["England"]), t20=False)

    report.append(f"  player records left over: {len(spare)}")
    os.makedirs(outdir, exist_ok=True)
    open(os.path.join(outdir, "cricket.0.0.12.0.cdf"), "wb").write(b)
    write_xmls(outdir, renames, reused_ids)
    open(os.path.join(outdir, "report.txt"), "w", encoding="utf-8").write("\n".join(report) + "\n")
    print("\n".join(report))


def write_xmls(outdir, renames, reused_ids=()):
    ti = open(os.path.join(GOB, TEAMINFO), encoding="utf-8", newline="").read()
    for idx, (name, short, abbr) in renames.items():
        ti = re.sub(r'(<team Index="%d"\s+TeamName=")[^"]*("\s+TeamNameShort=")[^"]*("\s+Abbreviation=")[^"]*'
                    r'("\s+LogoAbbreviation=")' % idx,
                    lambda m: m.group(1) + name + m.group(2) + short + m.group(3) + abbr + m.group(4), ti)
    open(os.path.join(outdir, TEAMINFO), "w", encoding="utf-8", newline="").write(ti)
    pm = open(os.path.join(GOB, PLAYERMGR), encoding="utf-8", newline="").read()
    for idx, (name, short, abbr) in renames.items():
        pm = re.sub(r'(<team name=")[^"]*("[^>]*\bid="%d")' % idx, lambda m: m.group(1) + name + m.group(2), pm)
    # unique faces are listed by player id: drop the ones whose record now holds a new player
    pm = re.sub(r'[ \t]*<player [^>]*location="(\d+)"[^>]*/>[^\n]*\n',
                lambda m: "" if int(m.group(1)) in reused_ids else m.group(0), pm)
    open(os.path.join(outdir, PLAYERMGR), "w", encoding="utf-8", newline="").write(pm)
    tl = open(os.path.join(GOB, TEAMLOOK), encoding="utf-8", newline="").read()
    for idx, (name, short, abbr) in renames.items():
        tl = re.sub(r'(<team Index="%d"[^>]*TeamName=")[^"]*("[^>]*TeamNameShort=")[^"]*' % idx,
                    lambda m: m.group(1) + name + m.group(2) + short, tl)
    open(os.path.join(outdir, TEAMLOOK), "w", encoding="utf-8", newline="").write(tl)
