package app.hermes.hands.engine;

import android.view.Surface;

/** Private app-to-Shizuku IPC. No arbitrary shell command interface. */
interface IHandsService {
    void destroy() = 16777114;
    String status() = 1;
    int createAgentScreen(in Surface surface, int width, int height, int densityDpi) = 2;
    void destroyAgentScreen() = 3;
    String execute(String action, String arguments, int displayId) = 4;
    String foregroundPackage(int displayId) = 5;
    oneway void cancel() = 6;
    long beginSession() = 7;
    String captureSecureTarget(int displayId, String ref, String snapshotId, String packageName, long sessionEpoch) = 8;
    boolean fillSecureTarget(String handle, String value) = 9;
    void releaseSecureTarget(String handle) = 10;
}
