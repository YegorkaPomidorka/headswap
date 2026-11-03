// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex;

import android.view.InputEvent;

/** Private capability passed only to HeadSwap's non-exported producer service. */
interface IDelegatedInputGateway {
    void registerParent(int displayId, IBinder token, int width, int height);
    void registerChild(int parentId, int displayId, IBinder token, int width, int height);
    void resizeDisplay(int displayId, IBinder token, int width, int height);
    void unregisterDisplay(int displayId, IBinder token);
    void injectInput(int displayId, IBinder token, in InputEvent event, int actionButton);
    int childTaskState(int displayId, IBinder token, String packageName);
    String[] childTaskPackages(int displayId, IBinder token);
}
