package io.virtualapp.home;

import android.util.Log;

import com.lody.virtual.client.natives.NativeMethods;

/**
 * Flurry analytics removed - this class kept as a stub.
 * Original functionality was ROM/Camera info collection via Flurry.
 */
public class FlurryROMCollector {

    private static final String TAG = FlurryROMCollector.class.getSimpleName();

    public static void startCollect() {
        Log.d(TAG, "start collect (Flurry disabled)...");
        NativeMethods.init();
        Log.d(TAG, "end collect...");
    }
}
