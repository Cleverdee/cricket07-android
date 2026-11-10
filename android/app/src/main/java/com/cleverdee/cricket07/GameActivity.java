package com.cleverdee.cricket07;

import android.content.Context;
import android.hardware.input.InputManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/* SDL's activity, plus: our own surface (handed to wgpu, see GameSurface), the touch overlay on top
   (hidden while a physical controller is connected), and the screen kept on. Landscape is set in the
   manifest. The game itself lives in libcricket07.so, entered through SDL_main. */
public class GameActivity extends SDLActivity implements InputManager.InputDeviceListener {
    private TouchOverlay mOverlay;
    private InputManager mInput;
    static boolean sRunning;
    private WifiManager.WifiLock mWifiLock;

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "cricket07" };
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        return new GameSurface(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sRunning = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        /* use the whole screen, including the strip beside a camera cut-out */
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
        if (System.getenv("RC_NETPLAY") != null) {   /* keeps Wi-Fi out of power saving: fewer late inputs */
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "cricket07");
            mWifiLock.acquire();
        }
        if (mLayout == null) return;   /* SDL showed an error (e.g. missing libraries) */
        mOverlay = new TouchOverlay(this);
        mLayout.addView(mOverlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                             ViewGroup.LayoutParams.MATCH_PARENT));
        mInput = (InputManager) getSystemService(Context.INPUT_SERVICE);
        mInput.registerInputDeviceListener(this, null);
        updateOverlay();
    }

    /* host: audio keeps the game's sound clock running while paused, and is reopened on return */
    static native void nativeForeground(boolean foreground);

    @Override
    protected void onPause() {
        try { nativeForeground(false); } catch (UnsatisfiedLinkError e) { /* library failed to load */ }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try { nativeForeground(true); } catch (UnsatisfiedLinkError e) { /* library failed to load */ }
    }

    @Override
    protected void onDestroy() {
        if (mInput != null) mInput.unregisterInputDeviceListener(this);
        if (mWifiLock != null && mWifiLock.isHeld()) mWifiLock.release();
        super.onDestroy();
        sRunning = false;
        /* a multiplayer session can't be left running: the next start is a clean process */
        if (System.getenv("RC_NETPLAY") != null) System.exit(0);
    }

    /* A real plugged-in or paired pad. Phones can list built-in parts as joysticks or gamepads, which must not
       hide the touch controls: only external devices with face buttons count. */
    private static boolean isController(InputDevice d) {
        if (d == null || d.isVirtual() || !d.isExternal()) return false;
        if ((d.getSources() & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) return false;
        boolean[] keys = d.hasKeys(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B);
        return keys[0] && keys[1];
    }

    private void updateOverlay() {
        boolean pad = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (isController(d)) { pad = true; Log.i("cricket07", "controller: " + d.getName()); }
        }
        if (mOverlay != null) mOverlay.setControllerConnected(pad);
    }

    @Override public void onInputDeviceAdded(int id) { updateOverlay(); }
    @Override public void onInputDeviceRemoved(int id) { updateOverlay(); }
    @Override public void onInputDeviceChanged(int id) { updateOverlay(); }
}
