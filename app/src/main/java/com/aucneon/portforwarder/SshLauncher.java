package com.aucneon.portforwarder;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;
import java.util.List;

public class SshLauncher {
    private static final String TAG = "SshLauncher";

    private static final String[] KNOWN_SSH_PACKAGES = {
        "org.connectbot",
        "com.sonelli.juicessh",
        "com.server.auditor.ssh.client",
        "com.termux"
    };

    /**
     * Launch an SSH connection to the forwarded port.
     * @param config The forwarding config
     * @param username Optional SSH username (can be null)
     * @return true if an SSH client was found and launched
     */
    public static boolean launchSsh(Context context, ForwardConfig config, String username) {
        // Build SSH URI - connect via the local forwarded port
        String host = "127.0.0.1";
        int port = config.listenPort;

        String uriStr;
        if (username != null && !username.isEmpty()) {
            uriStr = "ssh://" + username + "@" + host + ":" + port;
        } else {
            uriStr = "ssh://" + host + ":" + port;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uriStr));
        List<ResolveInfo> resolveInfos = context.getPackageManager()
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

        if (resolveInfos != null && !resolveInfos.isEmpty()) {
            context.startActivity(intent);
            Log.i(TAG, "Launched SSH client for " + uriStr);
            return true;
        }

        Log.w(TAG, "No SSH client found for URI: " + uriStr);
        return false;
    }

    /**
     * Check if any SSH client is installed.
     */
    public static boolean isSshClientAvailable(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : KNOWN_SSH_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                // Continue checking
            }
        }

        // Also try URI-based check
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("ssh://test@127.0.0.1:22"));
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        return resolveInfos != null && !resolveInfos.isEmpty();
    }

    /**
     * Open Play Store to install ConnectBot (popular SSH client).
     */
    public static void openPlayStoreForSshClient(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=org.connectbot"));
            context.startActivity(intent);
        } catch (Exception e) {
            // Fallback to web browser
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=org.connectbot"));
            context.startActivity(intent);
        }
    }
}
