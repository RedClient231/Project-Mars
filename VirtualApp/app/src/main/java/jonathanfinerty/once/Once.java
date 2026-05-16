package jonathanfinerty.once;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Minimal stub replacement for jonathanfinerty/Once library.
 * The original library is no longer available on jcenter/jitpack.
 * This stub provides the same API using SharedPreferences.
 */
public class Once {

    public static final int THIS_APP_INSTALL = 0;

    private static SharedPreferences sPrefs;

    public static void initialise(Context context) {
        sPrefs = context.getSharedPreferences("once_prefs", Context.MODE_PRIVATE);
    }

    public static boolean beenDone(String tag) {
        return sPrefs != null && sPrefs.getBoolean(tag, false);
    }

    public static boolean beenDone(int scope, String tag) {
        return beenDone(tag);
    }

    public static void markDone(String tag) {
        if (sPrefs != null) {
            sPrefs.edit().putBoolean(tag, true).apply();
        }
    }
}
