package com.blink.browser;

import android.os.Bundle;
import android.webkit.WebView;
import java.io.File;

/**
 * Incognito uses its own Android process and a separate Chromium WebView profile.
 * A new private PROCESS purges the previous private profile before WebView starts.
 * This does not hide downloads from Downloads, websites or network operators.
 */
public final class PrivateActivity extends MainActivity {
    private static boolean webViewSuffixSet;

    @Override public void onCreate(Bundle state) {
        // Must run before *any* WebView/CookieManager access in this process.
        if (!webViewSuffixSet) {
            // Android WebView's default profile directory for this suffix.
            // Existing private tabs cannot be alive at first Activity creation in a new process.
            deletePrivateDirectory(new File(getApplicationInfo().dataDir,
                    "app_webview_blink_samaran"));
            WebView.setDataDirectorySuffix("blink_samaran");
            webViewSuffixSet = true;
        }
        super.onCreate(state);
    }

    private void deletePrivateDirectory(File entry) {
        if (!entry.exists()) return;
        if (entry.isDirectory()) {
            File[] children = entry.listFiles();
            if (children != null) for (File child : children) deletePrivateDirectory(child);
        }
        // Refusal to delete should not cause browser crashes; cookies are also cleared in MainActivity.
        entry.delete();
    }
}
