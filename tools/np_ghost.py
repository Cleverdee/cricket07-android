"""A stand-in for the other phone in a Cricket 07 multiplayer test, with a neutral pad. Speaks the lobby and lockstep
protocols of MenuActivity.java and host/netplay_udp.c. The phone and this PC must share a network.

  np_ghost.py join <phone-ip>   phone taps Host a match and stays player 1
  np_ghost.py host              phone taps Join a match and types this PC's address (it becomes player 2)

--mute <from> <to>   go silent between those seconds, to test the waiting message and recovery
--quit-after <s>     leave, to test the "other player has left" message (after the 60 s timeout)
"""
import random
import socket
import struct
import sys
import time

LOBBY, GAME, MAGIC = 47006, 47007, 0x37304b43
HDR, W = "<8I", "<I4iIi"


def arg(name, n=1):
    if name not in sys.argv:
        return None
    i = sys.argv.index(name)
    return sys.argv[i + 1:i + 1 + n] if n > 1 else sys.argv[i + 1]


def lobby_socket():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", LOBBY))
    return s


def as_host():
    s = lobby_socket()
    print("ghost hosting: on the phone tap Join a match and type this PC's address")
    while True:
        d, a = s.recvfrom(512)
        f = d.decode().split("|")
        if f[0] == "CK07J":
            print("phone joined:", f[1], "build", f[2])
            ip = a[0]
            break
    s.settimeout(0.1)
    trips = []
    for i in range(25):
        t = time.time()
        s.sendto(f"CK07P|{i}".encode(), (ip, LOBBY))
        try:
            while s.recvfrom(512)[0].decode() != f"CK07Q|{i}":
                pass
            trips.append((time.time() - t) * 1000)
        except socket.timeout:
            pass
    trips.sort()
    rtt = trips[max(0, len(trips) * 9 // 10 - 1)] if trips else 100
    delay = max(2, min(12, int(rtt / 2 / 16.67) + 3))
    print(f"round trip {rtt:.0f} ms ({len(trips)}/25 answered), delay {delay}")
    session = random.randrange(1, 1 << 30)
    for _ in range(50):
        s.sendto(f"CK07S|{session}|{delay}".encode(), (ip, LOBBY))
        try:
            if s.recvfrom(512)[0] == b"CK07A":
                break
        except socket.timeout:
            pass
    s.close()
    return ip, session, delay, True


def as_join(ip):
    s = lobby_socket()
    s.settimeout(0.5)
    build = None
    while True:
        s.sendto(b"CK07?" if not build else f"CK07J|PC ghost|{build}".encode(), (ip, LOBBY))
        try:
            d, a = s.recvfrom(512)
        except socket.timeout:
            continue
        f = d.decode().split("|")
        if f[0] == "CK07!":
            build = f[2]
            print("host's build", build)
        elif f[0] == "CK07P":
            s.sendto(f"CK07Q|{f[1]}".encode(), a)
        elif f[0] == "CK07X":
            sys.exit("different build")
        elif f[0] == "CK07S":
            for _ in range(5):
                s.sendto(b"CK07A", a)
            s.close()
            return ip, int(f[1]), int(f[2]), False


def run(ip, session, delay, slot0):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("", GAME))
    s.setblocking(False)
    print(f"lockstep: session {session}, delay {delay}, ghost is player {1 if slot0 else 2}")
    mute, quit_after = arg("--mute", 2), arg("--quit-after")
    t0 = last_log = time.time()
    have = ack = hashes = 0
    first = delay + 1
    mine = delay
    dts, last_new = {}, time.time()   # the frame time a host reports: real time since its last frames
    while True:
        now = time.time() - t0
        if quit_after and now > float(quit_after):
            print("ghost leaving")
            return
        silent = mute and float(mute[0]) < now < float(mute[1])
        try:
            while True:
                d, _ = s.recvfrom(2048)
                m = struct.unpack_from(HDR, d)
                if m[0] != MAGIC or m[1] != session:
                    continue
                ack = max(ack, m[2])
                for i in range(m[4]):
                    f = m[3] + i
                    if not have or f == have + 1:
                        have = f
                if m[5]:
                    hashes += 1
        except BlockingIOError:
            pass
        if not silent:
            target = max(mine, have + delay if have else first)
            if target > mine:
                t = time.time()
                dt = max(1000, min(50000, int((t - last_new) * 1e6 / (target - mine))))
                for f in range(mine + 1, target + 1):
                    dts[f] = dt
                mine, last_new = target, t
            start = max(ack + 1, first)
            frames = list(range(start, mine + 1))[:32]
            body = b"".join(struct.pack(W, 0, 0, 0, 0, 0, dts.get(f, 16667) if slot0 else 0, 0) for f in frames)
            s.sendto(struct.pack(HDR, MAGIC, session, have, start, len(frames), 0, 0, 0) + body, (ip, GAME))
        if time.time() - last_log > 5:
            last_log = time.time()
            print(f"{now:5.0f}s phone's frame {have}, hash packets {hashes}{' (muted)' if silent else ''}")
        time.sleep(0.008)


if __name__ == "__main__":
    run(*(as_host() if sys.argv[1] == "host" else as_join(sys.argv[2])))
