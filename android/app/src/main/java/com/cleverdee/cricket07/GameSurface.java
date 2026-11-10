package com.cleverdee.cricket07;

import android.content.Context;
import android.view.SurfaceHolder;

import org.libsdl.app.SDLSurface;

/* SDL's surface view, also handing its Surface to the renderer (android/host/window_android.c):
   wgpu presents to it through Vulkan, and stops presenting before Android destroys it. */
public class GameSurface extends SDLSurface {
    static native void nativeSurfaceReady(Object surface);
    static native void nativeSurfaceGone();

    public GameSurface(Context context) {
        super(context);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        super.surfaceChanged(holder, format, width, height);
        nativeSurfaceReady(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        nativeSurfaceGone();   /* returns once no present is in flight */
        super.surfaceDestroyed(holder);
    }
}
