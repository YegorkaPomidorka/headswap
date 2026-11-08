// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex;

import android.view.Surface;

/** Private app-to-shell contract. All coordinates belong to the owned secondary display. */
interface IDexUserService {
    int protocolVersion() = 0;
    int start(in Surface surface, IBinder lifetimeToken, int density) = 1;
    void injectPointer(int action, int x, int y, int buttons) = 2;
    void injectKey(int action, int keycode) = 3;
    /** Returns only after the owned display has been released. */
    void stop() = 4;
    // Transaction 5 (setPhoneScreenOff, --screen) is not ported and stays unused.
    /** Protocol 3: mode 0 is Samsung DeX (rejected by the delegated backend), mode 1 is the app's Android Desktop. */
    int startDesktop(in Surface surface, IBinder lifetimeToken, int density, int mode) = 6;
    /** Bring the selected desktop back only on this service's owned secondary display. */
    void showDesktop() = 7;
    /** Protocol 4: child operations require a live parent desktop of this app; tokens never name arbitrary displays. */
    int createChildDisplay(in Surface surface, IBinder childToken, int width, int height, int density) = 8;
    void launchChildApp(IBinder childToken, String packageName, String className) = 9;
    void resizeChildDisplay(IBinder childToken, int width, int height, int density) = 10;
    void injectChildTouch(IBinder childToken, int action, float x, float y) = 11;
    void injectChildBack(IBinder childToken) = 12;
    /** Idempotent. A successful response acknowledges producer release before Surface disposal. */
    void closeChildDisplay(IBinder childToken) = 13;
    /** -1 unknown, 0 absent, 1 present, 2 top and visible, 3 top but not visible (moved to back). Observes only this child's launched package. */
    int childTaskState(IBinder childToken, String packageName) = 14;
    /** Protocol 5: bounded native parent dimensions; producer and encoder must match. */
    int startDesktopSized(in Surface surface, IBinder lifetimeToken, int width, int height, int density, int mode) = 15;
    /** Protocol 5: bounded touch contacts, on this producer's owned displays only. */
    void injectTouchPoints(int action, int actionIndex, in int[] pointerIds, in float[] xs, in float[] ys) = 16;
    void injectChildTouchPoints(IBinder childToken, int action, int actionIndex, in int[] pointerIds, in float[] xs, in float[] ys) = 17;
    /** Empty process-lifetime Binder only; exposes no producer operations. */
    IBinder childProducerLifetime(IBinder childToken) = 18;
    /** Packages of the tasks on this child's own display, most recent first; null when Android does not tell. */
    String[] childTaskPackages(IBinder childToken) = 20;
    /** 1 the launched package hides the status bar (fullscreen), 0 no, -1 unknown. Observes only this child's launched package. */
    int childFullscreen(IBinder childToken, String packageName) = 21;
    /** Press (DOWN+UP) of a navigation key (DPAD, Enter, Tab) on this child's own display: head unit knob in a windowed app. */
    void injectChildKey(IBinder childToken, int keycode) = 22;
    // Terminal shutdown. The delegated backend releases its producers and keeps its process.
    void destroy() = 16777114;
}
