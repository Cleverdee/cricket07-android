/* Lockstep transport for two phones on the same network (Wi-Fi or a hotspot): UDP between peers.
 * RC_NP_PEER=ip:port, RC_NP_PORT=local port, RC_NP_SESSION=number agreed in the lobby (LobbyActivity.java).
 *
 * Every packet carries the inputs the other side hasn't acknowledged, so a lost packet is covered by the next and
 * a long gap (a Wi-Fi drop, a phone locked for a minute) recovers by itself. Packets go out as soon as there is a
 * new input, and at least every 50 ms as a keepalive. Quiet for NP_TIMEOUT_S: the other player counts as gone. */
#ifdef __ANDROID__
#include "win32.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <sys/socket.h>
#include <unistd.h>

#define NP_MAGIC 0x37304b43u   /* "CK07" */
#define NP_TIMEOUT_S 60
#define NP_WINDOW 1024         /* inputs kept for resending */
#define NP_PER_PACKET 32

typedef struct { uint32_t buttons; int32_t lx, ly, rx, ry; uint32_t dt_us; int32_t mouse; } Wire;
typedef struct {
    uint32_t magic, session, ack, first, count;     /* ack: we hold all their inputs up to this frame */
    uint32_t hash_frame, hash_lo, hash_hi;
    Wire in[NP_PER_PACKET];
} Packet;

int netplay_pop(void);
int32_t netplay_msg(int i);
void netplay_remote_input_m(int slot, uint32_t frame, uint32_t buttons, int lx, int ly, int rx, int ry, uint32_t dt_us, int32_t mouse);
void netplay_remote_hash(int slot, uint32_t frame, uint32_t lo, uint32_t hi);
void netplay_player_left(int slot);

static int g_fd, g_peer_slot;
static uint32_t g_session;
static struct sockaddr_in g_peer;
static volatile double g_last_rx;   /* for the overlay's "waiting" message */
double netplay_udp_quiet(void) { return g_last_rx ? host_seconds() - g_last_rx : 0; }

static void *udp_thread(void *arg) {
    (void)arg;
    static Wire sent[NP_WINDOW];
    static uint32_t sent_frame[NP_WINDOW];
    uint32_t first = 0, last = 0, peer_ack = 0, have = 0;
    uint32_t hash_frame = 0, hash_lo = 0, hash_hi = 0;
    int hash_left = 0, gone = 0;
    double last_tx = 0, t0 = host_seconds();
    for (;;) {
        int fresh = 0;
        while (netplay_pop()) {
            if (netplay_msg(0) == 1) {
                uint32_t f = (uint32_t)netplay_msg(2);
                Wire *w = &sent[f % NP_WINDOW];
                w->buttons = (uint32_t)netplay_msg(3);
                w->lx = netplay_msg(4); w->ly = netplay_msg(5); w->rx = netplay_msg(6); w->ry = netplay_msg(7);
                w->dt_us = (uint32_t)netplay_msg(8);
                w->mouse = netplay_msg(10);
                sent_frame[f % NP_WINDOW] = f;
                if (!first) first = f;
                last = f;
                fresh = 1;
            } else {
                hash_frame = (uint32_t)netplay_msg(2);
                hash_lo = (uint32_t)netplay_msg(8);
                hash_hi = (uint32_t)netplay_msg(9);
                hash_left = 4;
            }
        }
        double now = host_seconds();
        if (fresh || now - last_tx > 0.05) {
            static Packet p;
            p.magic = NP_MAGIC; p.session = g_session; p.ack = have;
            uint32_t start = peer_ack >= first && first ? peer_ack + 1 : first;
            if (last >= NP_WINDOW && start + NP_WINDOW <= last) start = last - NP_WINDOW + 1;
            uint32_t n = 0;
            if (first && start <= last) {
                for (uint32_t f = start; f <= last && n < NP_PER_PACKET; f++, n++) p.in[n] = sent[f % NP_WINDOW];
            }
            p.first = start; p.count = n;
            p.hash_frame = hash_left > 0 ? hash_frame : 0; p.hash_lo = hash_lo; p.hash_hi = hash_hi;
            if (hash_left > 0) hash_left--;
            sendto(g_fd, &p, offsetof(Packet, in) + n * sizeof(Wire), 0, (struct sockaddr *)&g_peer, sizeof g_peer);
            last_tx = now;
        }
        struct pollfd pfd = { g_fd, POLLIN, 0 };
        if (poll(&pfd, 1, 4) > 0) {
            static Packet r;
            ssize_t got;
            while ((got = recv(g_fd, &r, sizeof r, MSG_DONTWAIT)) > 0) {
                if ((size_t)got < offsetof(Packet, in) || r.magic != NP_MAGIC || r.session != g_session) continue;
                if (r.count > NP_PER_PACKET || (size_t)got < offsetof(Packet, in) + r.count * sizeof(Wire)) continue;
                g_last_rx = host_seconds();
                if (r.ack > peer_ack) peer_ack = r.ack;
                for (uint32_t i = 0; i < r.count; i++) {
                    uint32_t f = r.first + i;
                    Wire *w = &r.in[i];
                    netplay_remote_input_m(g_peer_slot, f, w->buttons, w->lx, w->ly, w->rx, w->ry, w->dt_us, w->mouse);
                    if (!have ? 1 : f == have + 1) have = f;
                }
                if (r.hash_frame) netplay_remote_hash(g_peer_slot, r.hash_frame, r.hash_lo, r.hash_hi);
            }
        }
        double since = host_seconds() - (g_last_rx ? g_last_rx : t0);
        if (!gone && since > NP_TIMEOUT_S) { gone = 1; netplay_player_left(g_peer_slot); }
    }
    return NULL;
}

void netplay_udp_start(int slot) {
    const char *peer = getenv("RC_NP_PEER");
    if (!peer) return;
    char ip[64];
    snprintf(ip, sizeof ip, "%s", peer);
    char *colon = strchr(ip, ':');
    if (!colon) { fprintf(stderr, "[netplay] bad RC_NP_PEER %s\n", peer); return; }
    *colon = 0;
    memset(&g_peer, 0, sizeof g_peer);
    g_peer.sin_family = AF_INET;
    g_peer.sin_port = htons((uint16_t)atoi(colon + 1));
    inet_pton(AF_INET, ip, &g_peer.sin_addr);
    g_peer_slot = !slot;
    g_session = getenv("RC_NP_SESSION") ? (uint32_t)strtoul(getenv("RC_NP_SESSION"), NULL, 10) : 0;
    g_fd = socket(AF_INET, SOCK_DGRAM, 0);
    int on = 1, tos = 0xB8;   /* expedited: Wi-Fi puts these in its voice queue */
    setsockopt(g_fd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof on);
    setsockopt(g_fd, IPPROTO_IP, IP_TOS, &tos, sizeof tos);
    struct sockaddr_in me = { 0 };
    me.sin_family = AF_INET;
    me.sin_port = htons(getenv("RC_NP_PORT") ? (uint16_t)atoi(getenv("RC_NP_PORT")) : 47007);
    if (bind(g_fd, (struct sockaddr *)&me, sizeof me)) perror("[netplay] bind");
    pthread_t t;
    pthread_create(&t, NULL, udp_thread, NULL);
    pthread_detach(t);
    fprintf(stderr, "[netplay] UDP to %s, session %u\n", peer, g_session);
}
#endif
