package com.ashenstudios.cricket07;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

/* On-screen controls, reported to the game as pad 0 through the same path as a physical controller
   (nativeTouchPad -> input_gamepad); hidden, and not reported, while a physical controller is connected, until
   the screen is touched.

   The controls follow what the game is doing. host/gamephase.c works out the phase from the textures the
   game draws (nativeGamePhase): menu, cut-scene, batting or bowling with a delivery under way, or the ball
   in play, or the bowler aiming. Within those, the player's own presses pick the sub-phase: a shot played
   turns "ball in play" into Running, Field opens the field preview. Each layout has its own buttons, and
   their positions and sizes can be changed in edit mode, saved per layout. The gear opens settings: edit mode,
   and a choice between these dynamic controls and a classic controller (every pad button on screen at once,
   for anyone the dynamic switching doesn't suit).

   Bits are the W3C standard layout used by host/input.c. The game sees a "Logitech Dual Action": A = its
   bottom button (Cross), B = right (Circle), X = left (Square), Y = top (Triangle), LB/RB = L1/R1.
   Game actions, from its controller settings screens and loading tips: batting Y shot, A second shot,
   B advance, X leave, LB (held) lofted, stick direction; running B run, A stop, Y dive; bowling delivery
   N = pad button N (1 X, 2 A, 3 B, 4 Y, 5 LB, 6 RB, 7 LT, 8 RT), d-pad left/right side of the wicket, up/down field;
   aiming A lock, X set, B fake, stick aim and swing; fielding (semi-auto) Y keeper's end, A bowler's end,
   with a modifier (RB) for a direct hit; menus A select, Y back. */
public class TouchOverlay extends View {
    static native void nativeTouchPad(int buttons, int lx, int ly);
    static native void nativeTouchMouse(int button, int dx, int dy);   /* button: -1 none, 0 up, 1 down */
    static native int nativeFps();
    static native int nativeGamePhase();   /* host/gamephase.c PH_*: see phaseLayout() */
    static native int nativeNetState();   /* multiplayer: 0 off, 1 fine, 2 waiting, 3 other player gone, 4 out of sync */
    static native void nativeKey(int scancode, boolean down);   /* a keyboard key (USB HID usage / SDL scancode) */
    private static final int KEY_ESCAPE = 41;   /* the game pauses on Escape, not on the pad's Start */

    private static final int A = 0, B = 1, X = 2, Y = 3, LB = 4, RB = 5, LT = 6, RT = 7, DUP = 12, DDOWN = 13, DLEFT = 14,
                             DRIGHT = 15;

    static final int L_MENU = 0, L_BAT = 1, L_RUN = 2, L_CHOOSE = 3, L_AIM = 4, L_FIELD = 5, L_SCENE = 6, L_FIELDSET = 7,
                     L_DYN = 8,   /* the dynamic layouts are 0..L_DYN-1 */
                     L_PAD = 8, L_N = 9;
    static final String[] LAYOUT_NAMES = { "Menu", "Batting", "Running", "Bowling: choose", "Bowling: aim",
                                           "Fielding", "Cut-scene", "Bowling: field", "Classic controller" };
    private static final boolean[] HAS_STICK = { true, true, false, false, true, true, false, false, true };
    private static final boolean[] HAS_TOUCHPAD = { true, false, false, false, false, false, true, false, false };

    static final int I_CHECK = 1, I_BACK = 2, I_BAT = 3, I_LOFT = 4, I_RUN = 5, I_HAND = 6, I_DIVE = 7, I_SIDE = 8,
            I_FIELD = 9, I_LOCK = 10, I_TARGET = 11, I_FAKE = 12, I_UP = 13, I_DOWN = 14, I_DIRECT = 15, I_SKIP = 16,
            I_PAUSE = 17, I_GEAR = 18, I_NUMBER = 19, I_CROSS = 20, I_CIRCLE = 21, I_SQUARE = 22, I_TRIANGLE = 23,
            I_LEFT = 24, I_RIGHT = 25;
    static final int F_TOGGLE = 1, F_SHOT = 2, F_DELIVERY = 4, F_SETTINGS = 8, F_ESCAPE = 16, F_FIELD_OPEN = 32,
                     F_FIELD_CLOSE = 64;

    /* A button. Default place: xu/yu layout units (a ninth of the screen height) from the left or right edge
       and from the bottom; ru its radius in units. x, y, r: where it is now, in pixels. */
    static final class Btn {
        final String id, label; final int icon, bits, flags; final boolean right; final float xu, yu, ru;
        float x, y, r; boolean on;
        Btn(String id, String label, int icon, int bits, int flags, boolean right, float xu, float yu, float ru) {
            this.id = id; this.label = label; this.icon = icon; this.bits = bits; this.flags = flags;
            this.right = right; this.xu = xu; this.yu = yu; this.ru = ru;
        }
        boolean hit(float px, float py) { float dx = px - x, dy = py - y; return dx * dx + dy * dy <= r * r * 1.35f; }
    }
    private static Btn R(String id, String label, int icon, int bit, int flags, float xu, float yu, float ru) {
        return new Btn(id, label, icon, bit < 0 ? 0 : 1 << bit, flags, true, xu, yu, ru);
    }
    private static Btn Lf(String id, String label, int icon, int bit, int flags, float xu, float yu, float ru) {
        return new Btn(id, label, icon, bit < 0 ? 0 : 1 << bit, flags, false, xu, yu, ru);
    }

    private final Btn[][] mLayouts = {
        { /* menu */ R("ok", "OK", I_CHECK, A, 0, 1.45f, 1.45f, 0.65f), R("back", "Back", I_BACK, Y, 0, 3.05f, 2.55f, 0.55f) },
        { /* batting */ R("shot", "Shot", I_BAT, Y, F_SHOT, 1.6f, 1.6f, 0.8f),
          R("charge", "Charge", I_RUN, B, F_SHOT, 1.55f, 3.55f, 0.55f),
          R("leave", "Leave", I_HAND, X, 0, 3.3f, 1.45f, 0.55f),
          R("shot2", "Shot 2", I_BAT, A, F_SHOT, 3.2f, 3.2f, 0.45f),
          Lf("loft", "Loft", I_LOFT, LB, F_TOGGLE, 1.35f, 5.1f, 0.58f) },
        { /* running */ R("run", "Run", I_RUN, B, 0, 1.65f, 1.65f, 0.9f), R("stop", "Stop", I_HAND, A, 0, 3.45f, 1.45f, 0.62f),
          R("dive", "Dive", I_DIVE, Y, 0, 1.6f, 3.7f, 0.52f) },
        { /* bowling: choose - the game's delivery panel order, top to bottom */
          R("d2", "2", I_NUMBER, A, F_DELIVERY, 0.9f, 7.3f, 0.52f), R("d1", "1", I_NUMBER, X, F_DELIVERY, 0.9f, 5.9f, 0.52f),
          R("d3", "3", I_NUMBER, B, F_DELIVERY, 0.9f, 4.5f, 0.52f), R("d4", "4", I_NUMBER, Y, F_DELIVERY, 0.9f, 3.1f, 0.52f),
          R("d5", "5", I_NUMBER, LB, F_DELIVERY, 2.2f, 7.3f, 0.52f), R("d6", "6", I_NUMBER, RB, F_DELIVERY, 2.2f, 5.9f, 0.52f),
          R("d7", "7", I_NUMBER, LT, F_DELIVERY, 2.2f, 4.5f, 0.52f), R("d8", "8", I_NUMBER, RT, F_DELIVERY, 2.2f, 3.1f, 0.52f),
          Lf("side", "Side", I_SIDE, DRIGHT, 0, 1.1f, 1.6f, 0.52f), Lf("field", "Field", I_FIELD, DUP, F_FIELD_OPEN, 2.55f, 1.0f, 0.5f) },
        { /* bowling: aim */ R("lock", "Lock", I_LOCK, A, 0, 1.65f, 1.65f, 0.85f), R("set", "Set", I_TARGET, X, 0, 3.45f, 1.45f, 0.58f),
          R("fake", "Fake", I_FAKE, B, 0, 1.6f, 3.65f, 0.52f) },
        { /* fielding */ R("keeper", "Keeper", I_UP, Y, 0, 1.55f, 3.35f, 0.62f), R("bowler", "Bowler", I_DOWN, A, 0, 1.55f, 1.5f, 0.62f),
          R("direct", "Direct", I_DIRECT, RB, F_TOGGLE, 3.3f, 2.4f, 0.5f) },
        { /* cut-scene */ R("skip", "Skip", I_SKIP, A, 0, 1.45f, 1.45f, 0.58f) },
        { /* bowling: field formation preview (opened by Field: d-pad up/down cycle, A select, Y close) */
          R("fselect", "Select", I_CHECK, A, F_FIELD_CLOSE, 1.6f, 1.6f, 0.75f), R("fclose", "Close", I_BACK, Y, F_FIELD_CLOSE, 3.35f, 1.45f, 0.55f),
          Lf("fnext", "Next", I_UP, DUP, 0, 1.2f, 2.6f, 0.55f), Lf("fprev", "Previous", I_DOWN, DDOWN, 0, 1.2f, 1.2f, 0.55f) },
        { /* classic controller: the whole pad, the same in every phase (the stick is anywhere else on the left) */
          R("pa", "", I_CROSS, A, 0, 2.2f, 1.3f, 0.55f), R("pb", "", I_CIRCLE, B, 0, 1.1f, 2.4f, 0.55f),
          R("px", "", I_SQUARE, X, 0, 3.3f, 2.4f, 0.55f), R("py", "", I_TRIANGLE, Y, 0, 2.2f, 3.5f, 0.55f),
          R("pr1", "R1", I_NUMBER, RB, 0, 1.1f, 6.5f, 0.48f), R("pr2", "R2", I_NUMBER, RT, 0, 2.5f, 6.5f, 0.48f),
          Lf("pl1", "L1", I_NUMBER, LB, 0, 1.1f, 6.5f, 0.48f), Lf("pl2", "L2", I_NUMBER, LT, 0, 2.5f, 6.5f, 0.48f),
          Lf("pup", "", I_UP, DUP, 0, 1.9f, 5.05f, 0.42f), Lf("pdown", "", I_DOWN, DDOWN, 0, 1.9f, 3.55f, 0.42f),
          Lf("pleft", "", I_LEFT, DLEFT, 0, 1.15f, 4.3f, 0.42f), Lf("pright", "", I_RIGHT, DRIGHT, 0, 2.65f, 4.3f, 0.42f) },
    };
    /* on every layout: pause (Start) and the settings gear, top left */
    private final Btn[] mGlobal = {
        Lf("pause", "", I_PAUSE, -1, F_ESCAPE, 0.85f, 8.15f, 0.42f), Lf("edit", "", I_GEAR, -1, F_SETTINGS, 2.0f, 8.15f, 0.36f),
    };
    private boolean mClassic;     /* the classic controller instead of the dynamic layouts (saved as "classic") */
    private boolean mSettings;    /* the settings panel is open */
    private final RectF mSetDynamic = new RectF(), mSetClassic = new RectF(), mSetEdit = new RectF(), mSetClose = new RectF();

    private int mLayout = L_MENU, mPhase = -1;
    private boolean mShotPlayed, mFieldPreview;
    private long mFadeStart;

    private final SparseArray<Btn> mHeld = new SparseArray<>();   /* pointer id -> button */
    private final java.util.HashMap<Btn, Long> mPending = new java.util.HashMap<>();   /* released, held to the minimum */
    private static final long MIN_HOLD_MS = 100;   /* a quick tap must last a few game frames to be seen */
    private int mStickPointer = -1;
    private float mStickCx, mStickCy, mStickX, mStickY, mStickRadius;
    /* Off-button touches on the game picture in menus and cut-scenes are a touchpad for the game's mouse
       cursor (the title screen and some menus only take clicks): drag moves the cursor, a tap clicks. */
    private int mMousePointer = -1;
    private float mMouseRestX, mMouseRestY;
    private boolean mMouseMoved;
    private long mMouseDownAt;
    private final Runnable mMouseRelease = () -> nativeTouchMouse(0, 0, 0);
    private boolean mHidden, mPadConnected;
    private int mSentButtons = -1, mSentX, mSentY;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG), mLine = new Paint(Paint.ANTI_ALIAS_FLAG),
                        mText = new Paint(Paint.ANTI_ALIAS_FLAG), mIcon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final float mDp;
    private float mU = 1;
    private final SharedPreferences mPrefs;

    /* edit mode: drag a button to move it, pinch to resize; saved per layout */
    private boolean mEdit;
    private int mEditLayout;
    private Btn mEditSel;
    private int mDragPointer = -1;
    private float mDragDx, mDragDy, mPinchDist, mPinchR;
    private final RectF mEditPrev = new RectF(), mEditNext = new RectF(), mEditReset = new RectF(), mEditDone = new RectF();

    public TouchOverlay(Context context) {
        super(context);
        mDp = context.getResources().getDisplayMetrics().density;
        mPrefs = context.getSharedPreferences("touch_layout", Context.MODE_PRIVATE);
        mClassic = mPrefs.getBoolean("classic", false);
        if (mClassic) mLayout = L_PAD;
        mLine.setStyle(Paint.Style.STROKE);
        mIcon.setStyle(Paint.Style.STROKE);
        mIcon.setStrokeCap(Paint.Cap.ROUND);
        mIcon.setStrokeJoin(Paint.Join.ROUND);
        mText.setTextAlign(Paint.Align.CENTER);
        setWillNotDraw(false);
        postDelayed(mFpsTick, 1000);
        post(mPhaseTick);
    }

    /* the FPS readout: RC_SHOW_FPS=1 in files/env.txt (nativeFps() >= 0 once frames are counted) */
    private int mFps = -1, mNet;
    private final Runnable mFpsTick = new Runnable() {
        @Override public void run() {
            int f = nativeFps();
            if (f != mFps) { mFps = f; invalidate(); }
            int n = nativeNetState();
            if (n != mNet) { mNet = n; invalidate(); }
            postDelayed(this, mNet != 0 ? 250 : 1000);
        }
    };

    /* ------------------------------------------------------------------ phase -> layout */

    private final Runnable mPhaseTick = new Runnable() {
        @Override public void run() {
            int p = nativeGamePhase();
            if (p != mPhase) {
                if (p == 3 && mPhase != 3) {   /* a new delivery to face: no shot yet, loft back off */
                    mShotPlayed = false;
                    for (Btn b : mLayouts[L_BAT]) if ((b.flags & F_TOGGLE) != 0) b.on = false;
                    invalidate();
                }
                if (p != 5) mFieldPreview = false;
                mPhase = p;
            }
            setLayout(mClassic ? L_PAD : phaseLayout());
            postDelayed(this, 80);
        }
    };

    private int phaseLayout() {
        switch (mPhase) {
        case 1: return L_MENU;
        case 2: return L_SCENE;
        case 3: return L_BAT;
        case 4: return mShotPlayed ? L_RUN : L_BAT;   /* between balls the batting controls stay up */
        case 5: return mFieldPreview ? L_FIELDSET : L_CHOOSE;
        case 6: return L_FIELD;
        case 7: return L_AIM;
        default: return L_MENU;
        }
    }

    private void setLayout(int l) {
        if (l == mLayout) return;
        mLayout = l;
        mHeld.clear();
        mPending.clear();
        mStickPointer = -1;
        mMousePointer = -1;
        mCursorKnown = false;   /* the game may have moved or recentred the cursor */
        mFadeStart = SystemClock.uptimeMillis();
        send();
        invalidate();
    }

    public void setControllerConnected(boolean connected) {
        if (connected == mPadConnected) return;   /* only a real change: a touch may have brought the controls back */
        mPadConnected = connected;
        mHidden = connected;
        mHeld.clear();
        mPending.clear();
        mStickPointer = -1;
        mEdit = false;
        mSettings = false;
        if (!connected) send();   /* back to touch: report the released state */
        invalidate();
    }

    /* ------------------------------------------------------------------ placement */

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        mU = Math.min(w, h) / 9f;
        mStickRadius = 1.4f * mU;
        for (int l = 0; l < L_N; l++) place(l);
        placeGlobal();
    }

    private void placeDefault(Btn b) {
        int w = getWidth(), h = getHeight();
        b.x = b.right ? w - b.xu * mU : b.xu * mU;
        b.y = h - b.yu * mU;
        b.r = b.ru * mU;
    }

    private void place(int l) {
        int w = getWidth(), h = getHeight();
        for (Btn b : mLayouts[l]) {
            placeDefault(b);
            String s = mPrefs.getString(l + "." + b.id, null);
            if (s == null) continue;
            String[] f = s.split(",");
            try { b.x = Float.parseFloat(f[0]) * w; b.y = Float.parseFloat(f[1]) * h; b.r = Float.parseFloat(f[2]) * mU; }
            catch (RuntimeException e) { placeDefault(b); }
        }
    }

    private void placeGlobal() {
        int w = getWidth(), h = getHeight();
        for (Btn b : mGlobal) {
            placeDefault(b);
            String s = mPrefs.getString("g." + b.id, null);
            if (s == null) continue;
            String[] f = s.split(",");
            try { b.x = Float.parseFloat(f[0]) * w; b.y = Float.parseFloat(f[1]) * h; b.r = Float.parseFloat(f[2]) * mU; }
            catch (RuntimeException e) { placeDefault(b); }
        }
    }

    private void save(int l, Btn b) {
        String key = (isGlobal(b) ? "g" : String.valueOf(l)) + "." + b.id;
        mPrefs.edit().putString(key, (b.x / getWidth()) + "," + (b.y / getHeight()) + "," + (b.r / mU)).apply();
    }

    private boolean isGlobal(Btn b) { for (Btn g : mGlobal) if (g == b) return true; return false; }

    private Btn buttonAt(int l, float x, float y) {
        for (Btn b : mGlobal) if (b.hit(x, y)) return b;
        for (Btn b : mLayouts[l]) if (b.hit(x, y)) return b;
        return null;
    }

    /* touches on the game picture drive the mouse: in the menu and cut-scene layouts, and on the classic
       controller while the game is in a menu or cut-scene */
    private boolean hasTouchpad() { return mLayout == L_PAD ? mPhase <= 2 : HAS_TOUCHPAD[mLayout]; }

    /* the game picture: the 4:3 area centred on the screen */
    private boolean inGame(float x) {
        float gw = getHeight() * 4f / 3f, x0 = (getWidth() - gw) / 2f;
        return x >= x0 && x <= x0 + gw;
    }

    /* ------------------------------------------------------------------ touch */

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mHidden) {                            /* touching the screen always brings the controls back */
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { mHidden = false; invalidate(); }
            return true;
        }
        if (mEdit) { editTouch(e); return true; }
        if (mSettings) { settingsTouch(e); return true; }
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN: {
            int id = e.getPointerId(idx);
            float x = e.getX(idx), y = e.getY(idx);
            Btn b = buttonAt(mLayout, x, y);
            if (b != null) press(id, b);
            else if (HAS_STICK[mLayout] && x < getWidth() / 2f && !(hasTouchpad() && inGame(x)) && mStickPointer < 0) {
                mStickPointer = id;   /* the stick appears where the thumb lands */
                mStickCx = mStickX = x;
                mStickCy = mStickY = y;
            } else if (hasTouchpad() && inGame(x) && mMousePointer < 0) {
                mMousePointer = id;
                mMouseRestX = x;
                mMouseRestY = y;
                mMouseMoved = false;
                mMouseDownAt = e.getEventTime();
                cursorTo(x, y, true);
            }
            break;
        }
        case MotionEvent.ACTION_MOVE:
            for (int i = 0; i < e.getPointerCount(); i++) {
                int id = e.getPointerId(i);
                float x = e.getX(i), y = e.getY(i);
                if (id == mStickPointer) { mStickX = x; mStickY = y; }
                else if (id == mMousePointer) {
                    float slop = 12 * mDp;
                    if (!mMouseMoved && Math.abs(x - mMouseRestX) < slop && Math.abs(y - mMouseRestY) < slop) continue;
                    mMouseMoved = true;
                    cursorTo(x, y, false);   /* the cursor follows the finger */
                }
            }
            break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
        case MotionEvent.ACTION_CANCEL: {
            if (action == MotionEvent.ACTION_CANCEL) { mHeld.clear(); mStickPointer = -1; mMousePointer = -1; break; }
            int id = e.getPointerId(idx);
            Btn b = mHeld.get(id);
            if (b != null) release(id, b);
            if (id == mStickPointer) mStickPointer = -1;
            if (id == mMousePointer) {
                mMousePointer = -1;
                if (!mMouseMoved && e.getEventTime() - mMouseDownAt < 500) {   /* a tap: click where it landed */
                    long wait = Math.max(0, mCursorReadyAt - SystemClock.uptimeMillis());   /* after the cursor got there */
                    postDelayed(() -> nativeTouchMouse(1, 0, 0), wait);
                    removeCallbacks(mMouseRelease);
                    postDelayed(mMouseRelease, wait + 90);
                }
            }
            break;
        }
        }
        send();
        invalidate();
        return true;
    }

    /* Direct touch on the game picture (menus, cut-scenes): the game's cursor only moves by relative mouse
       motion, so a touch first pushes it into the top-left corner, where the game stops it, then moves it by
       the touch position in game pixels; dragging keeps it under the finger. MOUSE_PER_PIXEL: mouse counts
       per game pixel (measured on the menus: the cursor moves 1.28 game pixels per count). */
    private static final float MOUSE_PER_PIXEL = 1f / 1.28f;
    private float mCursorGx, mCursorGy;    /* where the cursor is, in the game's 640x480 */
    private boolean mCursorKnown;          /* mCursorGx/Gy valid: pushed to the corner since the last layout change */
    private long mCursorReadyAt;           /* when the last move will have reached the game */

    private void cursorTo(float x, float y, boolean home) {
        float gw = getHeight() * 4f / 3f, x0 = (getWidth() - gw) / 2f;
        final float gx = Math.max(0, Math.min(639, (x - x0) / gw * 640f)), gy = Math.max(0, Math.min(479, y / getHeight() * 480f));
        /* only the first touch on a screen: later ones move from where it is. In multiplayer the other player may
           have moved it, so every touch starts from the corner. */
        if (home && (!mCursorKnown || mNet != 0)) {
            mCursorKnown = true;
            nativeTouchMouse(-1, -4000, -4000);   /* any position -> the corner (0, 0) */
            mCursorGx = mCursorGy = 0;
            final int dx = Math.round(gx * MOUSE_PER_PIXEL), dy = Math.round(gy * MOUSE_PER_PIXEL);
            postDelayed(() -> nativeTouchMouse(-1, dx, dy), 60);   /* a later frame, or it nets out against the push */
            mCursorGx = gx; mCursorGy = gy;
            mCursorReadyAt = SystemClock.uptimeMillis() + 130;
            return;
        }
        int dx = Math.round((gx - mCursorGx) * MOUSE_PER_PIXEL), dy = Math.round((gy - mCursorGy) * MOUSE_PER_PIXEL);
        if (dx == 0 && dy == 0) return;
        nativeTouchMouse(-1, dx, dy);
        mCursorGx += dx / MOUSE_PER_PIXEL; mCursorGy += dy / MOUSE_PER_PIXEL;
        if (home) mCursorReadyAt = SystemClock.uptimeMillis() + 50;
    }

    private long mPressAt[] = new long[16];

    private void press(int pointer, Btn b) {
        if ((b.flags & F_SETTINGS) != 0) { openSettings(); return; }
        if ((b.flags & F_ESCAPE) != 0) {   /* a fixed-length tap: the layout changes as the game pauses */
            nativeKey(KEY_ESCAPE, true);
            postDelayed(() -> nativeKey(KEY_ESCAPE, false), MIN_HOLD_MS);
            return;
        }
        if ((b.flags & F_TOGGLE) != 0) { b.on = !b.on; return; }
        mHeld.put(pointer, b);
        mPending.remove(b);
        if (pointer < 16) mPressAt[pointer] = SystemClock.uptimeMillis();
        if ((b.flags & F_SHOT) != 0 && mLayout == L_BAT) mShotPlayed = true;
    }

    private void release(int pointer, Btn b) {
        mHeld.remove(pointer);
        long held = pointer < 16 ? SystemClock.uptimeMillis() - mPressAt[pointer] : MIN_HOLD_MS;
        if (held < MIN_HOLD_MS) {   /* keep it down until the game has seen it */
            mPending.put(b, SystemClock.uptimeMillis() + MIN_HOLD_MS - held);
            postDelayed(mPendingTick, MIN_HOLD_MS - held + 5);
        } else afterRelease(b);
    }

    private void afterRelease(Btn b) {
        /* (aiming comes from the game itself, phase 7: a number the bowler doesn't have changes nothing) */
        if ((b.flags & F_FIELD_OPEN) != 0 && mLayout == L_CHOOSE) { mFieldPreview = true; setLayout(L_FIELDSET); }
        if ((b.flags & F_FIELD_CLOSE) != 0 && mLayout == L_FIELDSET) { mFieldPreview = false; setLayout(L_CHOOSE); }
    }

    private final Runnable mPendingTick = () -> {
        long now = SystemClock.uptimeMillis();
        java.util.ArrayList<Btn> done = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Btn, Long> en : mPending.entrySet()) if (en.getValue() <= now) done.add(en.getKey());
        for (Btn b : done) mPending.remove(b);
        send();
        for (Btn b : done) afterRelease(b);
        invalidate();
    };

    private static final float STICK_DEADZONE = 0.2f;   /* of the stick's radius */

    private int stickAxis(float d) {
        float v = d / mStickRadius;
        if (v > 1) v = 1;
        if (v < -1) v = -1;
        return (int) (v * 32767);
    }

    private int heldBits() {
        int bits = 0;
        for (int i = 0; i < mHeld.size(); i++) bits |= mHeld.valueAt(i).bits;
        for (Btn b : mPending.keySet()) bits |= b.bits;
        for (Btn b : mLayouts[mLayout]) if ((b.flags & F_TOGGLE) != 0 && b.on) bits |= b.bits;
        return bits;
    }

    private void send() {
        boolean off = mEdit || mSettings;   /* the game sees nothing pressed while a panel is open */
        int buttons = off ? 0 : heldBits();
        int lx = 0, ly = 0;
        if (mStickPointer >= 0 && !off) {
            float dx = mStickX - mStickCx, dy = mStickY - mStickCy;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > mStickRadius) { dx *= mStickRadius / len; dy *= mStickRadius / len; }
            if (len >= STICK_DEADZONE * mStickRadius) {   /* a thumb settling on the screen isn't a direction */
                lx = stickAxis(dx);
                ly = stickAxis(dy);   /* +y = down, as in the standard layout */
            }
        }
        if (buttons == mSentButtons && lx == mSentX && ly == mSentY) return;
        mSentButtons = buttons; mSentX = lx; mSentY = ly;
        nativeTouchPad(buttons, lx, ly);
    }

    /* ------------------------------------------------------------------ settings */

    private void openSettings() {
        mSettings = true;
        mHeld.clear();
        mPending.clear();
        mStickPointer = -1;
        mMousePointer = -1;
        send();
    }

    private void settingsTouch(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return;
        float x = e.getX(), y = e.getY();
        if (mSetDynamic.contains(x, y)) setClassic(false);
        else if (mSetClassic.contains(x, y)) setClassic(true);
        else if (mSetEdit.contains(x, y)) { mSettings = false; enterEdit(); }
        else if (mSetClose.contains(x, y)) { mSettings = false; send(); }
        invalidate();
    }

    private void setClassic(boolean on) {
        if (on == mClassic) return;
        mClassic = on;
        mPrefs.edit().putBoolean("classic", on).apply();
        setLayout(on ? L_PAD : phaseLayout());
    }

    /* ------------------------------------------------------------------ edit mode */

    private void enterEdit() {
        mEdit = true;
        mEditLayout = mLayout;
        mEditSel = null;
        mHeld.clear();
        mPending.clear();
        mStickPointer = -1;
        send();   /* the game sees nothing pressed while editing */
    }

    private void editTouch(MotionEvent e) {
        int action = e.getActionMasked(), idx = e.getActionIndex();
        switch (action) {
        case MotionEvent.ACTION_DOWN: {
            float x = e.getX(idx), y = e.getY(idx);
            if (mEditDone.contains(x, y)) { mEdit = false; mEditSel = null; break; }
            if (mEditReset.contains(x, y)) { resetLayout(mEditLayout); break; }
            /* the arrows step through the dynamic layouts (the classic controller is a single one) */
            if (!mClassic && mEditPrev.contains(x, y)) { mEditLayout = (mEditLayout + L_DYN - 1) % L_DYN; mEditSel = null; break; }
            if (!mClassic && mEditNext.contains(x, y)) { mEditLayout = (mEditLayout + 1) % L_DYN; mEditSel = null; break; }
            Btn b = buttonAt(mEditLayout, x, y);
            mEditSel = b;
            if (b != null) { mDragPointer = e.getPointerId(idx); mDragDx = b.x - x; mDragDy = b.y - y; }
            break;
        }
        case MotionEvent.ACTION_POINTER_DOWN:
            if (mEditSel != null && e.getPointerCount() == 2) {   /* pinch: resize the selected button */
                mPinchDist = dist(e);
                mPinchR = mEditSel.r;
                mDragPointer = -1;
            }
            break;
        case MotionEvent.ACTION_MOVE:
            if (mEditSel == null) break;
            if (e.getPointerCount() >= 2 && mPinchDist > 0) {
                float r = mPinchR * dist(e) / mPinchDist;
                mEditSel.r = Math.max(0.3f * mU, Math.min(1.6f * mU, r));
            } else if (mDragPointer >= 0) {
                int i = e.findPointerIndex(mDragPointer);
                if (i >= 0) {
                    mEditSel.x = Math.max(mEditSel.r, Math.min(getWidth() - mEditSel.r, e.getX(i) + mDragDx));
                    mEditSel.y = Math.max(mEditSel.r, Math.min(getHeight() - mEditSel.r, e.getY(i) + mDragDy));
                }
            }
            break;
        case MotionEvent.ACTION_POINTER_UP:
            mPinchDist = 0;
            if (mEditSel != null) save(mEditLayout, mEditSel);
            break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
            mPinchDist = 0;
            mDragPointer = -1;
            if (mEditSel != null) save(mEditLayout, mEditSel);
            break;
        }
        invalidate();
    }

    private static float dist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void resetLayout(int l) {
        SharedPreferences.Editor ed = mPrefs.edit();
        for (Btn b : mLayouts[l]) ed.remove(l + "." + b.id);
        for (Btn b : mGlobal) ed.remove("g." + b.id);
        ed.apply();
        place(l);
        placeGlobal();
    }

    /* ------------------------------------------------------------------ drawing */

    @Override
    protected void onDraw(Canvas c) {
        mText.setColor(Color.argb(200, 255, 255, 255));
        if (mFps >= 0) {
            mText.setTextAlign(Paint.Align.LEFT);
            mText.setTextSize(0.32f * mU);
            c.drawText(mFps + " fps", 8 * mDp, mText.getTextSize() + 4 * mDp, mText);
            mText.setTextAlign(Paint.Align.CENTER);
        }
        drawNetMessage(c);
        if (mHidden) return;
        if (mEdit) { drawEdit(c); return; }
        if (mSettings) { drawSettings(c); return; }

        /* fade the new layout in, so a switch is noticed without being in the way */
        float a = Math.min(1f, (SystemClock.uptimeMillis() - mFadeStart) / 220f);
        if (a < 1f) postInvalidateOnAnimation();
        int held = heldBits();
        for (Btn b : mGlobal) drawButton(c, b, (held & b.bits) != 0, a);
        for (Btn b : mLayouts[mLayout]) drawButton(c, b, (b.flags & F_TOGGLE) != 0 ? b.on : (held & b.bits) != 0 && b.bits != 0 && isHeld(b), a);

        if (HAS_STICK[mLayout]) {
            mLine.setStrokeWidth(2 * mDp);
            mLine.setColor(Color.argb((int) (150 * a), 255, 255, 255));
            if (mStickPointer >= 0) {
                mFill.setColor(Color.argb(60, 0, 0, 0));
                c.drawCircle(mStickCx, mStickCy, mStickRadius, mFill);
                c.drawCircle(mStickCx, mStickCy, mStickRadius, mLine);
                float dx = mStickX - mStickCx, dy = mStickY - mStickCy;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > mStickRadius) { dx *= mStickRadius / len; dy *= mStickRadius / len; }
                mFill.setColor(Color.argb(140, 255, 255, 255));
                c.drawCircle(mStickCx + dx, mStickCy + dy, mStickRadius * 0.45f, mFill);
            } else {   /* where the stick lives: a faint ring at its resting place */
                mLine.setColor(Color.argb((int) (90 * a), 255, 255, 255));
                c.drawCircle(1.9f * mU, getHeight() - 1.9f * mU, mStickRadius * 0.8f, mLine);
            }
        }
    }

    private void drawNetMessage(Canvas c) {
        String msg = mNet == 2 ? "Waiting for the other player..." : mNet == 3 ? "The other player has left"
                   : mNet == 4 ? "Out of sync: please restart the match" : null;
        if (msg == null) return;
        mText.setTextSize(0.36f * mU);
        float half = mText.measureText(msg) / 2 + 0.4f * mU, y = 1.0f * mU;
        mFill.setColor(Color.argb(210, 0, 0, 0));
        c.drawRoundRect(new RectF(getWidth() / 2f - half, y - 0.6f * mU, getWidth() / 2f + half, y + 0.3f * mU), 0.2f * mU, 0.2f * mU, mFill);
        mText.setColor(Color.WHITE);
        c.drawText(msg, getWidth() / 2f, y, mText);
    }

    private boolean isHeld(Btn b) {
        for (int i = 0; i < mHeld.size(); i++) if (mHeld.valueAt(i) == b) return true;
        return mPending.containsKey(b);
    }

    private void drawButton(Canvas c, Btn b, boolean down, float a) {
        mFill.setColor(down ? Color.argb((int) (190 * a), 255, 255, 255) : Color.argb((int) (70 * a), 0, 0, 0));
        c.drawCircle(b.x, b.y, b.r, mFill);
        mLine.setStrokeWidth(Math.max(1.5f * mDp, b.r * 0.045f));
        mLine.setColor(Color.argb((int) (170 * a), 255, 255, 255));
        c.drawCircle(b.x, b.y, b.r, mLine);
        int fg = down ? Color.argb((int) (230 * a), 20, 24, 28) : Color.argb((int) (230 * a), 255, 255, 255);
        boolean labelled = b.label.length() > 0 && b.icon != I_NUMBER;
        float iy = labelled ? b.y - b.r * 0.14f : b.y;
        drawIcon(c, b, b.x, iy, b.r * (labelled ? 0.42f : 0.5f), fg);
        if (labelled) {
            mText.setColor(fg);
            mText.setTextSize(Math.max(9 * mDp, b.r * 0.27f));
            c.drawText(b.label, b.x, b.y + b.r * 0.62f, mText);
        }
    }

    /* Outline icons, drawn in a box of half-size s around (cx, cy). */
    private void drawIcon(Canvas c, Btn b, float cx, float cy, float s, int color) {
        mIcon.setColor(color);
        mIcon.setStrokeWidth(Math.max(1.5f * mDp, s * 0.16f));
        mIcon.setStyle(Paint.Style.STROKE);
        Path p = mPath;
        p.reset();
        switch (b.icon) {
        case I_NUMBER:
            mText.setColor(color);
            mText.setTextSize(s * 1.25f);
            c.drawText(b.label, cx, cy - (mText.descent() + mText.ascent()) / 2, mText);
            return;
        case I_CHECK: p.moveTo(cx - s * .7f, cy); p.lineTo(cx - s * .2f, cy + s * .5f); p.lineTo(cx + s * .75f, cy - s * .55f); break;
        case I_BACK:   /* curved arrow back */
            p.moveTo(cx - s * .2f, cy - s * .75f); p.lineTo(cx - s * .7f, cy - s * .3f); p.lineTo(cx - s * .2f, cy + s * .15f);
            p.moveTo(cx - s * .7f, cy - s * .3f); p.lineTo(cx + s * .25f, cy - s * .3f);
            p.quadTo(cx + s * .8f, cy - s * .3f, cx + s * .8f, cy + s * .2f); p.quadTo(cx + s * .8f, cy + s * .7f, cx + s * .2f, cy + s * .7f);
            break;
        case I_BAT: {   /* a bat at an angle: blade and handle */
            c.save(); c.rotate(-40, cx, cy);
            c.drawRoundRect(new RectF(cx - s * .28f, cy - s * .35f, cx + s * .28f, cy + s * .9f), s * .2f, s * .2f, mIcon);
            c.drawLine(cx, cy - s * .35f, cx, cy - s * .95f, mIcon);
            c.restore();
            return;
        }
        case I_LOFT: p.moveTo(cx - s * .7f, cy + s * .7f); p.quadTo(cx - s * .1f, cy - s * .2f, cx + s * .7f, cy - s * .7f);
            p.moveTo(cx + s * .1f, cy - s * .75f); p.lineTo(cx + s * .7f, cy - s * .7f); p.lineTo(cx + s * .6f, cy - s * .1f); break;
        case I_RUN:   /* running figure */
            c.drawCircle(cx + s * .25f, cy - s * .7f, s * .18f, mIcon);
            p.moveTo(cx + s * .1f, cy - s * .4f); p.lineTo(cx - s * .15f, cy + s * .15f);                       /* body */
            p.moveTo(cx - s * .15f, cy + s * .15f); p.lineTo(cx + s * .3f, cy + s * .45f); p.lineTo(cx + s * .2f, cy + s * .9f);   /* front leg */
            p.moveTo(cx - s * .15f, cy + s * .15f); p.lineTo(cx - s * .45f, cy + s * .55f); p.lineTo(cx - s * .8f, cy + s * .5f);  /* back leg */
            p.moveTo(cx - s * .6f, cy - s * .15f); p.lineTo(cx - s * .05f, cy - s * .3f); p.lineTo(cx + s * .55f, cy - s * .05f);  /* arms */
            break;
        case I_HAND:   /* raised palm */
            c.drawRoundRect(new RectF(cx - s * .55f, cy - s * .1f, cx + s * .45f, cy + s * .85f), s * .3f, s * .3f, mIcon);
            for (int i = 0; i < 4; i++) { float fx = cx - s * .42f + i * s * .28f; c.drawLine(fx, cy - s * .1f, fx, cy - s * (i == 0 || i == 3 ? .55f : .8f), mIcon); }
            c.drawLine(cx + s * .45f, cy + s * .3f, cx + s * .8f, cy - s * .05f, mIcon);
            return;
        case I_DIVE:   /* figure stretched out low, with a downward arrow */
            c.drawCircle(cx + s * .7f, cy - s * .05f, s * .16f, mIcon);
            p.moveTo(cx + s * .5f, cy + s * .05f); p.lineTo(cx - s * .6f, cy + s * .45f);
            p.moveTo(cx + s * .2f, cy + s * .15f); p.lineTo(cx + s * .55f, cy + s * .55f);
            p.moveTo(cx - s * .6f, cy - s * .7f); p.lineTo(cx - s * .6f, cy - s * .15f); p.moveTo(cx - s * .85f, cy - s * .4f);
            p.lineTo(cx - s * .6f, cy - s * .15f); p.lineTo(cx - s * .35f, cy - s * .4f);
            break;
        case I_SIDE: p.moveTo(cx - s * .8f, cy); p.lineTo(cx + s * .8f, cy);
            p.moveTo(cx - s * .45f, cy - s * .35f); p.lineTo(cx - s * .8f, cy); p.lineTo(cx - s * .45f, cy + s * .35f);
            p.moveTo(cx + s * .45f, cy - s * .35f); p.lineTo(cx + s * .8f, cy); p.lineTo(cx + s * .45f, cy + s * .35f); break;
        case I_FIELD: for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) c.drawCircle(cx + i * s * .6f, cy + j * s * .6f, s * .12f, mIcon);
            return;
        case I_LOCK: c.drawRoundRect(new RectF(cx - s * .6f, cy - s * .1f, cx + s * .6f, cy + s * .8f), s * .15f, s * .15f, mIcon);
            p.moveTo(cx - s * .38f, cy - s * .1f); p.lineTo(cx - s * .38f, cy - s * .4f);
            p.quadTo(cx - s * .38f, cy - s * .85f, cx, cy - s * .85f); p.quadTo(cx + s * .38f, cy - s * .85f, cx + s * .38f, cy - s * .4f);
            p.lineTo(cx + s * .38f, cy - s * .1f); break;
        case I_TARGET: c.drawCircle(cx, cy, s * .75f, mIcon); c.drawCircle(cx, cy, s * .38f, mIcon); c.drawCircle(cx, cy, s * .05f, mIcon);
            return;
        case I_FAKE:   /* dashed target: a pitch point that isn't real */
            mIcon.setPathEffect(new DashPathEffect(new float[] { s * .25f, s * .2f }, 0));
            c.drawCircle(cx, cy, s * .75f, mIcon);
            mIcon.setPathEffect(null);
            c.drawCircle(cx, cy, s * .3f, mIcon);
            return;
        case I_UP: p.moveTo(cx, cy + s * .8f); p.lineTo(cx, cy - s * .8f); p.moveTo(cx - s * .5f, cy - s * .3f); p.lineTo(cx, cy - s * .8f);
            p.lineTo(cx + s * .5f, cy - s * .3f); break;
        case I_DOWN: p.moveTo(cx, cy - s * .8f); p.lineTo(cx, cy + s * .8f); p.moveTo(cx - s * .5f, cy + s * .3f); p.lineTo(cx, cy + s * .8f);
            p.lineTo(cx + s * .5f, cy + s * .3f); break;
        case I_DIRECT:   /* stumps */
            for (int i = -1; i <= 1; i++) c.drawLine(cx + i * s * .35f, cy - s * .5f, cx + i * s * .35f, cy + s * .8f, mIcon);
            c.drawLine(cx - s * .5f, cy - s * .65f, cx + s * .5f, cy - s * .65f, mIcon);
            return;
        case I_SKIP: for (int k = 0; k < 2; k++) { float ox = cx - s * .45f + k * s * .55f;
                p.moveTo(ox, cy - s * .55f); p.lineTo(ox + s * .4f, cy); p.lineTo(ox, cy + s * .55f); }
            break;
        case I_CROSS: p.moveTo(cx - s * .55f, cy - s * .55f); p.lineTo(cx + s * .55f, cy + s * .55f);
            p.moveTo(cx + s * .55f, cy - s * .55f); p.lineTo(cx - s * .55f, cy + s * .55f); break;
        case I_CIRCLE: c.drawCircle(cx, cy, s * .6f, mIcon); return;
        case I_SQUARE: c.drawRect(cx - s * .55f, cy - s * .55f, cx + s * .55f, cy + s * .55f, mIcon); return;
        case I_TRIANGLE: p.moveTo(cx, cy - s * .65f); p.lineTo(cx + s * .65f, cy + s * .5f); p.lineTo(cx - s * .65f, cy + s * .5f); p.close(); break;
        case I_LEFT: p.moveTo(cx + s * .8f, cy); p.lineTo(cx - s * .8f, cy); p.moveTo(cx - s * .3f, cy - s * .5f); p.lineTo(cx - s * .8f, cy);
            p.lineTo(cx - s * .3f, cy + s * .5f); break;
        case I_RIGHT: p.moveTo(cx - s * .8f, cy); p.lineTo(cx + s * .8f, cy); p.moveTo(cx + s * .3f, cy - s * .5f); p.lineTo(cx + s * .8f, cy);
            p.lineTo(cx + s * .3f, cy + s * .5f); break;
        case I_PAUSE: c.drawLine(cx - s * .3f, cy - s * .6f, cx - s * .3f, cy + s * .6f, mIcon); c.drawLine(cx + s * .3f, cy - s * .6f, cx + s * .3f, cy + s * .6f, mIcon);
            return;
        case I_GEAR: c.drawCircle(cx, cy, s * .45f, mIcon); c.drawCircle(cx, cy, s * .15f, mIcon);
            for (int i = 0; i < 8; i++) { double t = i * Math.PI / 4;
                c.drawLine(cx + (float) Math.cos(t) * s * .5f, cy + (float) Math.sin(t) * s * .5f,
                           cx + (float) Math.cos(t) * s * .82f, cy + (float) Math.sin(t) * s * .82f, mIcon); }
            return;
        default: return;
        }
        c.drawPath(p, mIcon);
    }

    private void drawEdit(Canvas c) {
        int w = getWidth(), h = getHeight();
        mFill.setColor(Color.argb(110, 0, 0, 0));
        c.drawRect(0, 0, w, h, mFill);
        for (Btn b : mGlobal) drawButton(c, b, false, 1f);
        for (Btn b : mLayouts[mEditLayout]) drawButton(c, b, (b.flags & F_TOGGLE) != 0 && b.on, 1f);
        if (mEditSel != null) {
            mLine.setColor(Color.argb(230, 120, 180, 255));
            mLine.setStrokeWidth(2.5f * mDp);
            mLine.setPathEffect(new DashPathEffect(new float[] { 8 * mDp, 6 * mDp }, 0));
            c.drawCircle(mEditSel.x, mEditSel.y, mEditSel.r + 5 * mDp, mLine);
            mLine.setPathEffect(null);
        }
        /* title with layout arrows, and reset / done */
        mText.setColor(Color.argb(240, 255, 255, 255));
        mText.setTextSize(0.36f * mU);
        float ty = 0.75f * mU;
        c.drawText("Edit: " + LAYOUT_NAMES[mEditLayout], w / 2f, ty, mText);
        float half = mText.measureText("Edit: " + LAYOUT_NAMES[mEditLayout]) / 2f;
        mEditPrev.set(w / 2f - half - 1.1f * mU, ty - 0.55f * mU, w / 2f - half - 0.2f * mU, ty + 0.35f * mU);
        mEditNext.set(w / 2f + half + 0.2f * mU, ty - 0.55f * mU, w / 2f + half + 1.1f * mU, ty + 0.35f * mU);
        if (!mClassic) {
            c.drawText("<", mEditPrev.centerX(), ty, mText);
            c.drawText(">", mEditNext.centerX(), ty, mText);
        }
        mText.setTextSize(0.28f * mU);
        c.drawText("Drag to move, pinch to resize", w / 2f, ty + 0.55f * mU, mText);
        float by = h - 0.9f * mU;
        mEditReset.set(w / 2f - 2.4f * mU, by - 0.45f * mU, w / 2f - 0.2f * mU, by + 0.45f * mU);
        mEditDone.set(w / 2f + 0.2f * mU, by - 0.45f * mU, w / 2f + 2.4f * mU, by + 0.45f * mU);
        mLine.setColor(Color.argb(200, 255, 255, 255));
        mLine.setStrokeWidth(1.5f * mDp);
        mFill.setColor(Color.argb(150, 0, 0, 0));
        c.drawRoundRect(mEditReset, 0.2f * mU, 0.2f * mU, mFill);
        c.drawRoundRect(mEditReset, 0.2f * mU, 0.2f * mU, mLine);
        mFill.setColor(Color.argb(220, 255, 255, 255));
        c.drawRoundRect(mEditDone, 0.2f * mU, 0.2f * mU, mFill);
        mText.setTextSize(0.34f * mU);
        float tyb = by - (mText.descent() + mText.ascent()) / 2;
        mText.setColor(Color.argb(240, 255, 255, 255));
        c.drawText("Reset layout", mEditReset.centerX(), tyb, mText);
        mText.setColor(Color.argb(240, 20, 24, 28));
        c.drawText("Done", mEditDone.centerX(), tyb, mText);
    }

    /* the settings panel: dynamic or classic controls, edit the layout, close */
    private void drawSettings(Canvas c) {
        int w = getWidth(), h = getHeight();
        mFill.setColor(Color.argb(150, 0, 0, 0));
        c.drawRect(0, 0, w, h, mFill);
        RectF card = new RectF(w / 2f - 4.2f * mU, h / 2f - 3.2f * mU, w / 2f + 4.2f * mU, h / 2f + 3.2f * mU);
        mFill.setColor(Color.argb(235, 24, 28, 34));
        c.drawRoundRect(card, 0.3f * mU, 0.3f * mU, mFill);
        mLine.setStrokeWidth(1.5f * mDp);
        mLine.setColor(Color.argb(120, 255, 255, 255));
        c.drawRoundRect(card, 0.3f * mU, 0.3f * mU, mLine);

        mText.setColor(Color.argb(240, 255, 255, 255));
        mText.setTextSize(0.42f * mU);
        c.drawText("Touch controls", w / 2f, card.top + 0.85f * mU, mText);
        mText.setTextSize(0.27f * mU);
        mText.setColor(Color.argb(180, 255, 255, 255));
        c.drawText(mClassic ? "Every controller button on screen, the same all the time"
                            : "Buttons change with what you're doing: batting, bowling, running...",
                   w / 2f, card.top + 1.45f * mU, mText);

        float sy = card.top + 2.5f * mU;
        mSetDynamic.set(card.left + 0.5f * mU, sy - 0.5f * mU, w / 2f - 0.1f * mU, sy + 0.5f * mU);
        mSetClassic.set(w / 2f + 0.1f * mU, sy - 0.5f * mU, card.right - 0.5f * mU, sy + 0.5f * mU);
        drawChoice(c, mSetDynamic, "Dynamic", !mClassic);
        drawChoice(c, mSetClassic, "Classic controller", mClassic);

        float ey = card.top + 3.9f * mU;
        mSetEdit.set(card.left + 0.5f * mU, ey - 0.5f * mU, card.right - 0.5f * mU, ey + 0.5f * mU);
        drawChoice(c, mSetEdit, "Move and resize buttons", false);

        float cy = card.bottom - 0.9f * mU;
        mSetClose.set(w / 2f - 1.6f * mU, cy - 0.45f * mU, w / 2f + 1.6f * mU, cy + 0.45f * mU);
        drawChoice(c, mSetClose, "Close", true);
    }

    private void drawChoice(Canvas c, RectF r, String label, boolean on) {
        mFill.setColor(on ? Color.argb(230, 255, 255, 255) : Color.argb(120, 0, 0, 0));
        c.drawRoundRect(r, 0.2f * mU, 0.2f * mU, mFill);
        mLine.setColor(Color.argb(200, 255, 255, 255));
        mLine.setStrokeWidth(1.5f * mDp);
        c.drawRoundRect(r, 0.2f * mU, 0.2f * mU, mLine);
        mText.setTextSize(0.33f * mU);
        mText.setColor(on ? Color.argb(240, 20, 24, 28) : Color.argb(240, 255, 255, 255));
        c.drawText(label, r.centerX(), r.centerY() - (mText.descent() + mText.ascent()) / 2, mText);
    }
}
