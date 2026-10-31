/* Android host window: replaces host/window.c (SDL + Metal) and host/watch.c on Android.
 *
 * SDLActivity runs main() (as SDL_main) on its own thread; that thread becomes the event loop, the
 * game gets a thread of its own (host/main.c), and the renderer (render_wgpu.c) presents from the
 * game thread straight into the app's Android surface through Vulkan.
 *
 * The surface comes from GameSurface.java, not from SDL: Android destroys it whenever the app goes
 * to the background. While there is none, android_present_begin() blocks the game thread (the game
 * pauses); a new surface bumps a generation number so the renderer recreates its wgpu surface.
 *
 * Input: physical controllers go through SDL exactly as in window.c. The touch overlay
 * (TouchOverlay.java) reports a virtual pad through nativeTouchPad(), fed into the same
 * input_gamepad() path as pad 0 whenever no physical controller holds that slot.
 *
 * Also: stderr/stdout go to logcat (tag "cricket07"), RC_* variables are read from files/env.txt,
 * and the frame rate is counted for the overlay's FPS readout. */
#include "win32.h"

#include <SDL.h>
#include <android/log.h>
#include <dlfcn.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <pthread.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define TAG "cricket07"

int g_window_w = 1024, g_window_h = 768;   /* surface size in pixels (read by render_wgpu.c) */
void *g_android_window;                    /* ANativeWindow* */
static SDL_Window *g_window;

void input_key_hid(int usage, int down);   /* input.c */
void input_mouse_button(int b, int down);
void dinput_mouse_move(int dx, int dy);
void input_gamepad(int idx, int connected, uint32_t buttons, int lx, int ly, int rx, int ry);
void dsound_android_lifecycle(int foreground);   /* host/dsound.c */

/* ------------------------------------------------------------------ logcat */

static int g_log_pipe[2];
static void *log_thread(void *arg) {
    (void)arg;
    char buf[2048];
    size_t len = 0;
    for (;;) {
        ssize_t n = read(g_log_pipe[0], buf + len, sizeof buf - 1 - len);
        if (n <= 0) return NULL;
        len += (size_t)n;
        char *start = buf, *nl;
        while ((nl = memchr(start, '\n', len - (size_t)(start - buf)))) {
            *nl = 0;
            __android_log_write(ANDROID_LOG_INFO, TAG, start);
            start = nl + 1;
        }
        len -= (size_t)(start - buf);
        memmove(buf, start, len);
        if (len == sizeof buf - 1) { buf[len] = 0; __android_log_write(ANDROID_LOG_INFO, TAG, buf); len = 0; }
    }
}

__attribute__((constructor)) static void redirect_stdio(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IOLBF, 0);
    if (pipe(g_log_pipe)) return;
    dup2(g_log_pipe[1], 1);
    dup2(g_log_pipe[1], 2);
    pthread_t t;
    pthread_create(&t, NULL, log_thread, NULL);
    pthread_detach(t);
}

/* ------------------------------------------------------------------ game folder, env.txt */

/* files/env.txt: one NAME=value per line (e.g. RC_LOG_FPS=1), applied before the game starts */
static void load_env(const char *dir) {
    char path[1100];
    snprintf(path, sizeof path, "%s/env.txt", dir);
    FILE *f = fopen(path, "r");
    if (!f) return;
    char line[512];
    while (fgets(line, sizeof line, f)) {
        line[strcspn(line, "\r\n")] = 0;
        char *eq = strchr(line, '=');
        if (line[0] == '#' || !eq) continue;
        *eq = 0;
        setenv(line, eq + 1, 1);
        fprintf(stderr, "env: %s=%s\n", line, eq + 1);
    }
    fclose(f);
}

/* Android/data/<package>/files/game; NULL (after telling the player) if it isn't there */
const char *android_game_dir(void) {
    static char dir[4096];
    const char *ext = SDL_AndroidGetExternalStoragePath();   /* .../Android/data/<package>/files */
    if (!ext) { fprintf(stderr, "no external storage: %s\n", SDL_GetError()); return NULL; }
    load_env(ext);
    snprintf(dir, sizeof dir, "%s/game", ext);
    char exe[1100];
    snprintf(exe, sizeof exe, "%s/%s", dir, getenv("RC_EXE") ? getenv("RC_EXE") : PROFILE_EXE);
    struct stat st;
    if (stat(exe, &st) != 0) {
        char msg[1400];
        snprintf(msg, sizeof msg, "Game files not found.\n\nCopy your game folder so that this file exists:\n%s", exe);
        fprintf(stderr, "%s\n", msg);
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, PROFILE_TITLE, msg, NULL);
        return NULL;
    }
    return dir;
}

/* ------------------------------------------------------------------ surface handoff (GameSurface.java) */

static pthread_mutex_t g_surf_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_surf_cond = PTHREAD_COND_INITIALIZER;
static unsigned g_surf_gen;      /* bumped for every new ANativeWindow */
static int g_surf_ok;            /* a surface exists and may be presented to */

/* Game thread, around every present: holds the lock so the surface can't vanish mid-present. */
static unsigned g_frames;
unsigned android_present_begin(void) {
    pthread_mutex_lock(&g_surf_lock);
    while (!g_surf_ok) pthread_cond_wait(&g_surf_cond, &g_surf_lock);
    return g_surf_gen;
}
void android_present_end(void) {
    g_frames++;
    pthread_mutex_unlock(&g_surf_lock);
}

JNIEXPORT void JNICALL Java_com_ashenstudios_cricket07_GameSurface_nativeSurfaceReady(JNIEnv *env, jclass cls,
                                                                                       jobject surface) {
    (void)cls;
    ANativeWindow *w = ANativeWindow_fromSurface(env, surface);
    if (!w) return;
    pthread_mutex_lock(&g_surf_lock);
    if (w != g_android_window) {
        if (g_android_window) ANativeWindow_release(g_android_window);
        g_android_window = w;
        g_surf_gen++;
    } else {
        ANativeWindow_release(w);   /* same window, size change only */
    }
    g_window_w = ANativeWindow_getWidth(w);
    g_window_h = ANativeWindow_getHeight(w);
    /* the game expects 60 Hz presents; keep high-refresh panels (the Mi 10T Pro's 144 Hz) at 60 */
    static int32_t (*set_rate)(ANativeWindow *, float, int8_t);   /* API 30+; minSdk is 29 */
    if (!set_rate) set_rate = (int32_t (*)(ANativeWindow *, float, int8_t))dlsym(RTLD_DEFAULT, "ANativeWindow_setFrameRate");
    if (set_rate) set_rate(w, 60.f, ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
    g_surf_ok = 1;
    fprintf(stderr, "surface: %dx%d (generation %u)\n", g_window_w, g_window_h, g_surf_gen);
    pthread_cond_broadcast(&g_surf_cond);
    pthread_mutex_unlock(&g_surf_lock);
}

/* UI thread, before Android destroys the surface: waits for an in-flight present, then blocks the next */
JNIEXPORT void JNICALL Java_com_ashenstudios_cricket07_GameSurface_nativeSurfaceGone(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    pthread_mutex_lock(&g_surf_lock);
    g_surf_ok = 0;
    pthread_mutex_unlock(&g_surf_lock);
    fprintf(stderr, "surface: gone (game paused)\n");
}

/* a keyboard key from the overlay (its pause button sends Escape): queued as an SDL key event, so the
   event loop applies it like a real key */
static volatile uint32_t g_touch_esc;
static volatile int g_touch_dirty = 1;
JNIEXPORT void JNICALL Java_com_ashenstudios_cricket07_TouchOverlay_nativeKey(JNIEnv *env, jclass cls, jint scancode,
                                                                             jboolean down) {
    (void)env; (void)cls;
    extern int netplay_on(void);
    if (netplay_on()) {   /* Esc travels with the pad, to reach both phones on the same frame (netplay.c) */
        if (scancode == 41) { g_touch_esc = down ? 1u << 20 : 0; g_touch_dirty = 1; }
        SDL_Event w = { 0 };
        w.type = SDL_USEREVENT;
        SDL_PushEvent(&w);
        return;
    }
    SDL_Event e = { 0 };
    e.type = down ? SDL_KEYDOWN : SDL_KEYUP;
    e.key.state = down ? SDL_PRESSED : SDL_RELEASED;
    e.key.keysym.scancode = (SDL_Scancode)scancode;
    SDL_PushEvent(&e);
}

/* multiplayer state for the overlay's message: 0 not multiplayer, 1 fine, 2 waiting for the other player,
   3 the other player has gone, 4 the two games have drifted apart */
JNIEXPORT jint JNICALL Java_com_ashenstudios_cricket07_TouchOverlay_nativeNetState(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    extern int netplay_on(void);
    extern uint32_t netplay_status(int what);
    if (!netplay_on()) return 0;
    if (netplay_status(2)) return 4;
    if (netplay_status(5)) return 3;
    return netplay_status(4) > 1000 ? 2 : 1;
}

/* the game phase for the overlay's layouts (host/gamephase.c): 0 unknown, 1 menu, 2 cut-scene, 3 batting
   delivery, 4 batting ball in play, 5 bowling delivery, 6 bowling ball in play */
JNIEXPORT jint JNICALL Java_com_ashenstudios_cricket07_TouchOverlay_nativeGamePhase(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    extern int gamephase_current(void);
    return gamephase_current();
}

/* UI thread, from GameActivity.onPause/onResume: SDL's own pause handling blocks its event loop, so
   anything that must happen while the app is in the background is driven from here */
JNIEXPORT void JNICALL Java_com_ashenstudios_cricket07_GameActivity_nativeForeground(JNIEnv *env, jclass cls,
                                                                                      jboolean foreground) {
    (void)env; (void)cls;
    fprintf(stderr, "app %s\n", foreground ? "in the foreground" : "in the background");
    dsound_android_lifecycle(foreground);
}

/* frames presented per second, for the overlay; -1 (no readout) without RC_SHOW_FPS=1 or before the
   first second has passed */
JNIEXPORT jint JNICALL Java_com_ashenstudios_cricket07_TouchOverlay_nativeFps(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (!getenv("RC_SHOW_FPS")) return -1;
    static unsigned last_frames;
    static double last_t;
    static int fps = -1;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    double t = ts.tv_sec + ts.tv_nsec * 1e-9;
    if (last_t == 0) { last_t = t; last_frames = g_frames; }
    else if (t - last_t >= 1.0) {
        fps = (int)((g_frames - last_frames) / (t - last_t) + 0.5);
        last_t = t; last_frames = g_frames;
    }
    return fps;
}

/* ------------------------------------------------------------------ controllers (as in window.c) */

#define PAD_SLOTS 4
static SDL_GameController *g_pad[PAD_SLOTS];
static SDL_Joystick *g_joy[PAD_SLOTS];

static const int g_std_for_raw[12] = { 2, 0, 1, 3, 4, 5, 6, 7, 9, 8, 10, 11 };

/* the touch pad: written by the UI thread, applied on the event thread */
static volatile uint32_t g_touch_buttons;
static volatile int g_touch_lx, g_touch_ly;

static void touch_report(void) {
    if (g_joy[0]) return;   /* a physical controller has pad 0 */
    input_gamepad(0, 1, g_touch_buttons | g_touch_esc, g_touch_lx, g_touch_ly, 0, 0);
}

JNIEXPORT void JNICALL Java_com_ashenstudios_cricket07_TouchOverlay_nativeTouchPad(JNIEnv *env, jclass cls,
                                                                                  jint buttons, jint lx, jint ly) {
    (void)env; (void)cls;
    g_touch_buttons = (uint32_t)buttons;
    g_touch_lx = lx;
    g_touch_ly = ly;
    g_touch_dirty = 1;
    SDL_Event e = { 0 };
    e.type = SDL_USEREVENT;   /* wake the event loop */
    SDL_PushEvent(&e);
}

/* the overlay's touchpad (taps and drags off the buttons): motion accumulates, button changes queue up */
static int g_tmouse_dx, g_tmouse_dy;      /* __atomic */
static int g_tmouse_q[16];                /* 1 down, 0 up */
static unsigned g_tmouse_head, g_tmouse_tail;
static pthread_mutex_t g_tmouse_lock = PTHREAD_MUTEX_INITIALIZER;

void input_mouse_move(int dx, int dy);
static void touch_mouse_apply(void) {
    int dx = __atomic_exchange_n(&g_tmouse_dx, 0, __ATOMIC_ACQ_REL);
    int dy = __atomic_exchange_n(&g_tmouse_dy, 0, __ATOMIC_ACQ_REL);
    if (dx || dy) input_mouse_move(dx, dy);
    pthread_mutex_lock(&g_tmouse_lock);
    while (g_tmouse_tail != g_tmouse_head) {
        int down = g_tmouse_q[g_tmouse_tail++ % 16];
        pthread_mutex_unlock(&g_tmouse_lock);
        input_mouse_button(0, down);
        pthread_mutex_lock(&g_tmouse_lock);
    }
    pthread_mutex_unlock(&g_tmouse_lock);
}

JNIEXPORT void JNICALL Java_com_ashenstudios_cricket07_TouchOverlay_nativeTouchMouse(JNIEnv *env, jclass cls,
                                                                                    jint button, jint dx, jint dy) {
    (void)env; (void)cls;
    __atomic_add_fetch(&g_tmouse_dx, dx, __ATOMIC_ACQ_REL);
    __atomic_add_fetch(&g_tmouse_dy, dy, __ATOMIC_ACQ_REL);
    if (button >= 0) {
        pthread_mutex_lock(&g_tmouse_lock);
        if (g_tmouse_head - g_tmouse_tail < 16) g_tmouse_q[g_tmouse_head++ % 16] = button;
        pthread_mutex_unlock(&g_tmouse_lock);
    }
    SDL_Event e = { 0 };
    e.type = SDL_USEREVENT;   /* wake the event loop */
    SDL_PushEvent(&e);
}

static void pad_report(int slot) {
    SDL_GameController *gc = g_pad[slot];
    SDL_Joystick *js = g_joy[slot];
    if (!js) {
        if (slot == 0) { g_touch_dirty = 1; return; }   /* pad 0 goes back to the touch overlay */
        input_gamepad(slot, 0, 0, 0, 0, 0, 0);
        return;
    }
    uint32_t b = 0;
    if (!gc) {
        int nb = SDL_JoystickNumButtons(js), na = SDL_JoystickNumAxes(js);
        for (int i = 0; i < nb && i < 12; i++)
            if (SDL_JoystickGetButton(js, i)) b |= 1u << g_std_for_raw[i];
        for (int h = 0; h < SDL_JoystickNumHats(js); h++) {
            Uint8 v = SDL_JoystickGetHat(js, h);
            if (v & SDL_HAT_UP) b |= 1u << 12;
            if (v & SDL_HAT_DOWN) b |= 1u << 13;
            if (v & SDL_HAT_LEFT) b |= 1u << 14;
            if (v & SDL_HAT_RIGHT) b |= 1u << 15;
        }
        int ax[4] = { 0, 0, 0, 0 };
        for (int i = 0; i < na && i < 4; i++) ax[i] = SDL_JoystickGetAxis(js, i);
        input_gamepad(slot, 1, b, ax[0], ax[1], ax[2], ax[3]);
        return;
    }
    static const SDL_GameControllerButton order[17] = {
        SDL_CONTROLLER_BUTTON_A, SDL_CONTROLLER_BUTTON_B, SDL_CONTROLLER_BUTTON_X, SDL_CONTROLLER_BUTTON_Y,
        SDL_CONTROLLER_BUTTON_LEFTSHOULDER, SDL_CONTROLLER_BUTTON_RIGHTSHOULDER, SDL_CONTROLLER_BUTTON_INVALID,
        SDL_CONTROLLER_BUTTON_INVALID, SDL_CONTROLLER_BUTTON_BACK, SDL_CONTROLLER_BUTTON_START,
        SDL_CONTROLLER_BUTTON_LEFTSTICK, SDL_CONTROLLER_BUTTON_RIGHTSTICK, SDL_CONTROLLER_BUTTON_DPAD_UP,
        SDL_CONTROLLER_BUTTON_DPAD_DOWN, SDL_CONTROLLER_BUTTON_DPAD_LEFT, SDL_CONTROLLER_BUTTON_DPAD_RIGHT,
        SDL_CONTROLLER_BUTTON_GUIDE,
    };
    for (int i = 0; i < 17; i++)
        if (order[i] != SDL_CONTROLLER_BUTTON_INVALID && SDL_GameControllerGetButton(gc, order[i])) b |= 1u << i;
    if (SDL_GameControllerGetAxis(gc, SDL_CONTROLLER_AXIS_TRIGGERLEFT) > 10000) b |= 1u << 6;
    if (SDL_GameControllerGetAxis(gc, SDL_CONTROLLER_AXIS_TRIGGERRIGHT) > 10000) b |= 1u << 7;
    input_gamepad(slot, 1, b, SDL_GameControllerGetAxis(gc, SDL_CONTROLLER_AXIS_LEFTX),
                  SDL_GameControllerGetAxis(gc, SDL_CONTROLLER_AXIS_LEFTY),
                  SDL_GameControllerGetAxis(gc, SDL_CONTROLLER_AXIS_RIGHTX),
                  SDL_GameControllerGetAxis(gc, SDL_CONTROLLER_AXIS_RIGHTY));
}
static int pad_slot_of(SDL_JoystickID id) {
    for (int i = 0; i < PAD_SLOTS; i++)
        if (g_joy[i] && SDL_JoystickInstanceID(g_joy[i]) == id) return i;
    return -1;
}
static void pad_added(int device_index) {
    if (pad_slot_of(SDL_JoystickGetDeviceInstanceID(device_index)) >= 0) return;
    for (int i = 0; i < PAD_SLOTS; i++)
        if (!g_joy[i]) {
            if (SDL_IsGameController(device_index)) {
                g_pad[i] = SDL_GameControllerOpen(device_index);
                g_joy[i] = g_pad[i] ? SDL_GameControllerGetJoystick(g_pad[i]) : NULL;
            } else {
                g_joy[i] = SDL_JoystickOpen(device_index);
            }
            if (!g_joy[i]) { fprintf(stderr, "controller: open failed: %s\n", SDL_GetError()); return; }
            fprintf(stderr, "controller %d: %s (%s, %d buttons, %d axes, %d hats)\n", i, SDL_JoystickName(g_joy[i]),
                    g_pad[i] ? "mapped" : "raw joystick", SDL_JoystickNumButtons(g_joy[i]),
                    SDL_JoystickNumAxes(g_joy[i]), SDL_JoystickNumHats(g_joy[i]));
            pad_report(i);
            return;
        }
}
static void pad_removed(SDL_JoystickID id) {
    int i = pad_slot_of(id);
    if (i < 0) return;
    if (g_pad[i]) SDL_GameControllerClose(g_pad[i]); else SDL_JoystickClose(g_joy[i]);
    g_pad[i] = NULL;
    g_joy[i] = NULL;
    fprintf(stderr, "controller %d disconnected\n", i);
    pad_report(i);
}

/* ------------------------------------------------------------------ window and event loop */

int window_create(const char *title) {
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
    SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");   /* Back = Escape, not "quit the app" */
    /* otherwise the accelerometer shows up as a buttonless joystick, takes pad 0 and turns the touch pad off */
    SDL_SetHint(SDL_HINT_ACCELEROMETER_AS_JOYSTICK, "0");
    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS | SDL_INIT_GAMECONTROLLER) != 0) {
        fprintf(stderr, "SDL_Init failed: %s\n", SDL_GetError());
        return 0;
    }
    /* Vulkan flag: without any graphics flag SDL2 defaults to OpenGL on Android (SDL_DefaultGraphicsBackends)
       and puts an EGL surface on the window, so vkCreateAndroidSurfaceKHR fails with NATIVE_WINDOW_IN_USE */
    g_window = SDL_CreateWindow(title, 0, 0, 0, 0, SDL_WINDOW_FULLSCREEN | SDL_WINDOW_VULKAN);
    if (!g_window) { fprintf(stderr, "SDL_CreateWindow failed: %s\n", SDL_GetError()); return 0; }
    fprintf(stderr, "window: created, waiting for the surface\n");
    for (int i = 0; i < SDL_NumJoysticks(); i++) pad_added(i);
    return 1;
}

void window_event_loop(void) {
    for (;;) {
        SDL_Event e;
        int got = SDL_WaitEventTimeout(&e, 10);
        if (g_touch_dirty) { g_touch_dirty = 0; touch_report(); }
        touch_mouse_apply();
        if (!got) continue;
        switch (e.type) {
        case SDL_QUIT:
            fprintf(stderr, "app closed\n");
            exit(0);
        case SDL_KEYDOWN: case SDL_KEYUP: {
            int usage = (int)e.key.keysym.scancode;
            if (usage == SDL_SCANCODE_AC_BACK) usage = SDL_SCANCODE_ESCAPE;
            if (e.type == SDL_KEYUP) input_key_hid(usage, 0);
            else if (!e.key.repeat) input_key_hid(usage, 1);
            break;
        }
        case SDL_JOYDEVICEADDED: pad_added(e.jdevice.which); break;
        case SDL_JOYDEVICEREMOVED: pad_removed(e.jdevice.which); break;
        case SDL_JOYAXISMOTION: case SDL_JOYBUTTONDOWN: case SDL_JOYBUTTONUP: case SDL_JOYHATMOTION:
        case SDL_CONTROLLERAXISMOTION: case SDL_CONTROLLERBUTTONDOWN: case SDL_CONTROLLERBUTTONUP: {
            SDL_JoystickID id = e.type == SDL_JOYAXISMOTION ? e.jaxis.which : e.type == SDL_JOYHATMOTION ? e.jhat.which
                              : e.type == SDL_CONTROLLERAXISMOTION ? e.caxis.which
                              : e.type == SDL_CONTROLLERBUTTONDOWN || e.type == SDL_CONTROLLERBUTTONUP ? e.cbutton.which
                              : e.jbutton.which;
            int i = pad_slot_of(id);
            if (i >= 0) pad_report(i);
            break;
        }
        }
    }
}

/* host/watch.c needs execinfo/backtrace (API 33+): write watchpoints are a desktop debugging aid */
void watch_arm(uint32_t addr) { (void)addr; }
