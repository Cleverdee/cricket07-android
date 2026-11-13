/* Game phase (menus, batting, bowling, ...) for the touch overlay, worked out from which textures the
 * game draws (render_rec.c fingerprints every texture at upload and reports each frame's set here) and
 * from the game's own record of which side the player is on.
 *
 * Found by recording textures and memory in each phase over four matches with different teams. Many
 * textures that look like phase signals are really team or player textures (kits, faces, the batting
 * side's score strip) and only work for some teams: everything below held for every team tried.
 *   menu cursor       168da0b7  frontend menus, pause and end-of-over menus
 *   match HUD         17510c8c / 2daacf1f / a6872641 / e395bf8b  any live-match view (not cut-scenes,
 *                     replays, intros or menus)
 *   delivery camera   cfc62b9e / 6d93da0b / 1489fece  the view behind the bowler: a delivery is being set
 *                     up or bowled (absent once the ball is in play, and between balls)
 *   player's side     the match object (0x009a48b0) holds the batting and bowling team numbers (+0xd564, +0xd568,
 *                     swapped each innings) and the two teams in it (+0x1c20 left, +0x4858 right); the controller
 *                     lists 0x0090e514 (left) and 0x0090e510 (right) say which of them the player has
 *   autoplay          0x007edd8c: 1 while the game plays on its own (Autoplay over/innings/wicket)
 *   bowler aiming     global 0x007edd14: 1 from an accepted delivery to the ball's release
 * gamephase_dump() writes a frame's set (with the snapshot hook in d3d8.c) for finding new ones. */
#include "win32.h"

enum { PH_UNKNOWN, PH_MENU, PH_SCENE, PH_BAT_DELIVERY, PH_BAT_BALL, PH_BOWL_DELIVERY, PH_BOWL_BALL, PH_BOWL_AIM };

static volatile int g_phase;   /* PH_*, read by the overlay (android/host/window_android.c) */

extern uint32_t g_last_tex[], g_last_tex_wh[], g_last_tex_n;

static int has(const uint32_t *h, uint32_t n, uint32_t want) {
    for (uint32_t i = 0; i < n; i++) if (h[i] == want) return 1;
    return 0;
}

static int hud(const uint32_t *h, uint32_t n) {
    return has(h, n, 0x17510c8cu) + has(h, n, 0x2daacf1fu) + has(h, n, 0xa6872641u) + has(h, n, 0xe395bf8bu) >= 2;
}
static int delivery(const uint32_t *h, uint32_t n) {
    return has(h, n, 0xcfc62b9eu) || has(h, n, 0x6d93da0bu) || has(h, n, 0x1489feceu);
}
#define MATCH 0x009a48b0u
static int batting_team(void) { return (int)R32(MATCH + 0xd564u); }
static int bowling_team(void) { return (int)R32(MATCH + 0xd568u); }
/* the team on the side the player's controller is on (a second player's side is not shown on touch) */
static int player_team(void) {
    extern int netplay_on(void), netplay_slot(void);
    if (netplay_on()) {   /* two players: find this phone's pad in the lists (pad n is controller 2 + n) */
        uint8_t me = (uint8_t)(2 + netplay_slot());
        if (R8(0x0090e510u) == me || R8(0x0090e511u) == me) return (int)R32(MATCH + 0x4858u);
        if (R8(0x0090e514u) == me || R8(0x0090e515u) == me) return (int)R32(MATCH + 0x1c20u);
        return (int)R32(MATCH + (netplay_slot() ? 0x4858u : 0x1c20u));
    }
    int left = R8(0x0090e514u) != 0xff, right = R8(0x0090e510u) != 0xff;
    return (int)R32(MATCH + (!left && right ? 0x4858u : 0x1c20u));
}
static int player_bowling(void) { return bowling_team() == player_team(); }
static int autoplaying(void) { return R8(0x007edd8cu) != 0; }
/* 0x007edd14 is 1 from the moment the game accepts a delivery until the ball is released (the run-up, with
   the pitch-point cursor), 0 while it waits for a delivery to be chosen, in the field preview, and while
   the ball is in play */
static int bowler_aiming(void) { return R32(0x007edd14u) == 1; }

/* Training nets have no scoreboard: only one of the match HUD textures (17510c8c) is drawn, so they looked like
   a cut-scene and the touch controls showed only Skip. Bowling nets draw the delivery-camera set while a delivery
   is chosen or aimed; batting nets draw the shot-timing bar (8bd222e3 2D, e3b09cd3), which matches don't. The side
   is remembered between balls, and forgotten when a match scoreboard is back. */
static int g_nets_side;   /* 0 not known, 1 batting, 2 bowling */

static int nets(const uint32_t *h, uint32_t n) {
    if (!has(h, n, 0x17510c8cu) || autoplaying()) { g_nets_side = 0; return 0; }
    if (delivery(h, n) || bowler_aiming()) g_nets_side = 2;
    else if (has(h, n, 0x8bd222e3u) || has(h, n, 0xe3b09cd3u)) g_nets_side = 1;
    return g_nets_side == 2 ? (bowler_aiming() ? PH_BOWL_AIM : PH_BOWL_DELIVERY)
         : g_nets_side == 1 ? PH_BAT_DELIVERY : 0;
}

static int classify(const uint32_t *h, uint32_t n) {
    if (has(h, n, 0x168da0b7u)) return PH_MENU;
    if (!hud(h, n)) { int p = nets(h, n); return p ? p : PH_SCENE; }
    g_nets_side = 0;
    if (autoplaying()) return PH_SCENE;
    int d = delivery(h, n);
    if (player_bowling()) return bowler_aiming() ? PH_BOWL_AIM : d ? PH_BOWL_DELIVERY : PH_BOWL_BALL;
    return d ? PH_BAT_DELIVERY : PH_BAT_BALL;
}

/* A phase must hold for a few frames before it is reported, so single odd frames (fades, the first
   frame of a camera cut) don't flip the controls. */
#define PHASE_HOLD 5
void gamephase_frame(const uint32_t *hashes, uint32_t n) {
    static int candidate, count;
    int p = classify(hashes, n);
    if (p != candidate) { candidate = p; count = 0; }
    if (++count >= PHASE_HOLD) g_phase = p;
}

int gamephase_current(void) { return g_phase; }

/* the last frame's fingerprints, one per line: hash, size, 2d (pre-transformed draw) or 3d */
void gamephase_dump(const char *path) {
    FILE *f = fopen(path, "w");
    if (!f) return;
    for (uint32_t i = 0; i < g_last_tex_n; i++)
        fprintf(f, "%08x %ux%u %s\n", g_last_tex[i], g_last_tex_wh[i] & 0x7FFF, (g_last_tex_wh[i] >> 16) & 0x7FFF,
                g_last_tex_wh[i] >> 31 ? "2d" : "3d");
    fclose(f);
}
