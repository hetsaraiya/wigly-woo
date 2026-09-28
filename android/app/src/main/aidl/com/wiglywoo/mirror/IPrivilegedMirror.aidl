package com.wiglywoo.mirror;

import android.os.ParcelFileDescriptor;

/** Narrow shell surface. The caller is checked; this is not a command runner. */
interface IPrivilegedMirror {
    void destroy() = 16777114;
    String start(in ParcelFileDescriptor video, in ParcelFileDescriptor audio, in ParcelFileDescriptor control, in ParcelFileDescriptor meta, String configJson) = 1;
    void stop() = 2;
    String bringUpHotspot() = 5;
    /** near is decided in the app process, which hears the Mac's presence reports. */
    String unlock(String pin, boolean near) = 8;
}
