package android.service.notification;

import android.os.Bundle;
import android.os.UserHandle;

/**
 * Compile-only stand-in for the hidden framework class. The APK does not
 * package this file. The device loads the real class.
 */
public final class Adjustment {
    public static final String KEY_IMPORTANCE = "key_importance";

    public Adjustment(String pkg, String key, Bundle signals, CharSequence explanation,
            UserHandle user) {
    }
}
