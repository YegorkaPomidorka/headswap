// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex;

import android.view.InputEvent;
import android.view.Surface;

/** Private capability passed only to HeadSwap's non-exported producer service. */
interface IDelegatedInputGateway {
    void registerParent(int displayId, IBinder token, int width, int height);
    void registerChild(int parentId, int displayId, IBinder token, int width, int height);
    void resizeDisplay(int displayId, IBinder token, int width, int height);
    void unregisterDisplay(int displayId, IBinder token);
    void injectInput(int displayId, IBinder token, in InputEvent event, int actionButton);
    int childTaskState(int displayId, IBinder token, String packageName);
    String[] childTaskPackages(int displayId, IBinder token);
    int childFullscreen(int displayId, IBinder token, String packageName);
    /** Prototype vdm1: display inside a VirtualDevice owned by the main process; -1 = use the ordinary path. */
    int createDeviceDisplay(in Surface surface, String name, int width, int height, int density, int flags, IBinder owner);
    void resizeDeviceDisplay(int displayId, int width, int height, int density);
    void releaseDeviceDisplay(int displayId);
}
