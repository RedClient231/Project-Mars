package top.niunaijun.blackbox.core;

import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.utils.Slog;


public class GmsCore {
    private static final String TAG = "GmsCore";

    private static final HashSet<String> GOOGLE_APP = new HashSet<>();
    private static final HashSet<String> GOOGLE_SERVICE = new HashSet<>();
    public static final String GMS_PKG = "com.google.android.gms";
    public static final String GSF_PKG = "com.google.android.gsf";
    public static final String VENDING_PKG = "com.android.vending";
    public static final String PLAY_GAMES_PKG = "com.google.android.play.games";

    /**
     * Deterministic install order for Google packages.
     * GSF must be installed first, then GMS, then Play Store, then Play Games.
     * Installing out of order can break GMS initialization.
     */
    private static final String[] GOOGLE_INSTALL_ORDER = new String[]{
            GSF_PKG,
            GMS_PKG,
            VENDING_PKG,
            PLAY_GAMES_PKG
    };

    /**
     * Core packages that are required for GMS to function.
     * If these are missing on the host device, GMS install should fail with a clear message.
     */
    private static final Set<String> REQUIRED_PACKAGES = new HashSet<>(Arrays.asList(
            GSF_PKG,
            GMS_PKG,
            VENDING_PKG
    ));

    static {
        GOOGLE_APP.add(VENDING_PKG);
        GOOGLE_APP.add(PLAY_GAMES_PKG);
        GOOGLE_APP.add("com.google.android.wearable.app");
        GOOGLE_APP.add("com.google.android.wearable.app.cn");

        
        GOOGLE_SERVICE.add(GMS_PKG);
        GOOGLE_SERVICE.add(GSF_PKG);
        GOOGLE_SERVICE.add("com.google.android.gsf.login");
        GOOGLE_SERVICE.add("com.google.android.backuptransport");
        GOOGLE_SERVICE.add("com.google.android.backup");
        GOOGLE_SERVICE.add("com.google.android.configupdater");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.contacts");
        GOOGLE_SERVICE.add("com.google.android.feedback");
        GOOGLE_SERVICE.add("com.google.android.onetimeinitializer");
        GOOGLE_SERVICE.add("com.google.android.partnersetup");
        GOOGLE_SERVICE.add("com.google.android.setupwizard");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.calendar");
    }

    public static boolean isGoogleService(String packageName) {
        return GOOGLE_SERVICE.contains(packageName);
    }

    public static boolean isGoogleAppOrService(String str) {
        return GOOGLE_APP.contains(str) || GOOGLE_SERVICE.contains(str);
    }

    public static InstallResult installGApps(int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        List<String> failedPackages = new ArrayList<>();
        List<String> skippedPackages = new ArrayList<>();
        List<String> installedPackages = new ArrayList<>();

        // Install in deterministic order: GSF → GMS → Vending → Play Games
        for (String packageName : GOOGLE_INSTALL_ORDER) {
            // Skip if already installed in this virtual user
            if (blackBoxCore.isInstalled(packageName, userId)) {
                Slog.d(TAG, "Package " + packageName + " already installed for user " + userId);
                installedPackages.add(packageName);
                continue;
            }

            // Check if the package exists on the host device
            try {
                BlackBoxCore.getContext().getPackageManager().getApplicationInfo(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                // Package not on host device
                if (REQUIRED_PACKAGES.contains(packageName)) {
                    Slog.e(TAG, "Required Google package not found on host: " + packageName);
                    failedPackages.add(packageName);
                } else {
                    Slog.w(TAG, "Optional Google package not found on host, skipping: " + packageName);
                    skippedPackages.add(packageName);
                }
                continue;
            }

            // Install the package (split-aware via BlackBoxCore.installPackageAsUser)
            Slog.d(TAG, "Installing Google package: " + packageName + " for user " + userId);
            InstallResult installResult = blackBoxCore.installPackageAsUser(packageName, userId);
            if (!installResult.success) {
                Slog.e(TAG, "Failed to install " + packageName + ": " + installResult.msg);
                if (REQUIRED_PACKAGES.contains(packageName)) {
                    failedPackages.add(packageName);
                } else {
                    skippedPackages.add(packageName);
                }
            } else {
                Slog.d(TAG, "Successfully installed: " + packageName);
                installedPackages.add(packageName);
            }
        }

        // If any required packages failed, uninstall everything and return error
        if (!failedPackages.isEmpty()) {
            Slog.e(TAG, "Required Google packages failed: " + failedPackages);
            uninstallGApps(userId);
            String failedList = String.join(", ", failedPackages);
            return new InstallResult().installError(
                    "Required Google packages not available on this device: " + failedList
                            + ". Please install Google Play Services and Play Store on your phone first.");
        }

        Slog.d(TAG, "GMS install complete. Installed: " + installedPackages
                + ", Skipped (optional): " + skippedPackages);
        return new InstallResult();
    }

    public static void uninstallGApps(int userId) {
        // Uninstall in reverse order
        for (int i = GOOGLE_INSTALL_ORDER.length - 1; i >= 0; i--) {
            String packageName = GOOGLE_INSTALL_ORDER[i];
            BlackBoxCore.get().uninstallPackageAsUser(packageName, userId);
        }
        // Also uninstall any additional Google service packages from the old sets
        for (String packageName : GOOGLE_SERVICE) {
            if (!isInInstallOrder(packageName)) {
                BlackBoxCore.get().uninstallPackageAsUser(packageName, userId);
            }
        }
    }

    private static boolean isInInstallOrder(String packageName) {
        for (String ordered : GOOGLE_INSTALL_ORDER) {
            if (ordered.equals(packageName)) return true;
        }
        return false;
    }

    public static void remove(String packageName) {
        GOOGLE_SERVICE.remove(packageName);
        GOOGLE_APP.remove(packageName);
    }


    public static boolean isSupportGms() {
        try {
            BlackBoxCore.getPackageManager().getPackageInfo(GMS_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }

    public static boolean isInstalledGoogleService(int userId) {
        return BlackBoxCore.get().isInstalled(GMS_PKG, userId);
    }

    /**
     * Check if a specific Google package is installed in the virtual user.
     */
    public static boolean isGooglePackageInstalled(String packageName, int userId) {
        return BlackBoxCore.get().isInstalled(packageName, userId);
    }

    /**
     * Get diagnostic info about which Google packages are installed.
     */
    public static String getGmsDiagnosticInfo(int userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("GMS Diagnostic for user ").append(userId).append(":\n");
        for (String pkg : GOOGLE_INSTALL_ORDER) {
            boolean onHost = false;
            try {
                BlackBoxCore.getContext().getPackageManager().getApplicationInfo(pkg, 0);
                onHost = true;
            } catch (PackageManager.NameNotFoundException ignored) {}
            boolean inVirtual = BlackBoxCore.get().isInstalled(pkg, userId);
            sb.append("  ").append(pkg)
                    .append(": host=").append(onHost)
                    .append(", virtual=").append(inVirtual)
                    .append("\n");
        }
        return sb.toString();
    }
}
