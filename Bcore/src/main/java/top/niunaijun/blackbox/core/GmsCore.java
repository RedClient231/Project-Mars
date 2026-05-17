package top.niunaijun.blackbox.core;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

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

    /**
     * Packages required for game login to work.
     * All four must be present for Play Games sign-in to function.
     */
    public static final Set<String> GAME_LOGIN_REQUIRED = new HashSet<>(Arrays.asList(
            GSF_PKG,
            GMS_PKG,
            VENDING_PKG,
            PLAY_GAMES_PKG
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

        // Check if Play Games was skipped (not on host device) — game login won't work without it
        if (skippedPackages.contains(PLAY_GAMES_PKG)) {
            Slog.w(TAG, "GMS core installed, but Google Play Games is missing on host device");
            return new InstallResult().installError(
                    "GMS core installed, but Google Play Games is missing. Game login may not work. "
                    + "Install Google Play Games on your real device and reinstall GMS here, "
                    + "or import a Play Games APK/XAPK manually via GMS Manager.");
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

    // ======================== ABI Detection ========================

    /**
     * Detect if the given ApplicationInfo points to a 64-bit ARM (arm64-v8a) native library.
     */
    public static boolean detectArm64(ApplicationInfo appInfo) {
        if (appInfo == null) return false;

        // Check nativeLibraryDir
        if (appInfo.nativeLibraryDir != null && appInfo.nativeLibraryDir.contains("/arm64")) {
            return true;
        }

        // Check splitSourceDirs for arm64 splits
        if (appInfo.splitSourceDirs != null) {
            for (String split : appInfo.splitSourceDirs) {
                if (split != null) {
                    String lower = split.toLowerCase();
                    if (lower.contains("arm64_v8a") || lower.contains("arm64-v8a")) {
                        return true;
                    }
                }
            }
        }

        // Check sourceDir for arm64 indicator
        if (appInfo.sourceDir != null && appInfo.sourceDir.toLowerCase().contains("arm64")) {
            return true;
        }

        return false;
    }

    /**
     * Detect if the given ApplicationInfo points to a 32-bit ARM (armeabi-v7a) native library.
     */
    public static boolean detectArmv7(ApplicationInfo appInfo) {
        if (appInfo == null) return false;

        // Check nativeLibraryDir — must contain /arm or /armeabi but NOT /arm64
        if (appInfo.nativeLibraryDir != null) {
            if ((appInfo.nativeLibraryDir.contains("/arm") || appInfo.nativeLibraryDir.contains("/armeabi"))
                    && !appInfo.nativeLibraryDir.contains("/arm64")) {
                return true;
            }
        }

        // Check splitSourceDirs for armeabi_v7a splits
        if (appInfo.splitSourceDirs != null) {
            for (String split : appInfo.splitSourceDirs) {
                if (split != null) {
                    String lower = split.toLowerCase();
                    if (lower.contains("armeabi_v7a") || lower.contains("armeabi-v7a")) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // ======================== Diagnostics ========================

    /**
     * Get comprehensive diagnostic info about Google packages on host and virtual.
     * Reports Android version, ABI info, and per-package details.
     */
    public static String getGmsDiagnosticInfo(int userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== GMS Diagnostic Report ===\n");
        sb.append("User ID: ").append(userId).append("\n");
        sb.append("Android version: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Process 64-bit: ").append(BlackBoxCore.is64Bit()).append("\n");
        sb.append("Virtual user: ").append(userId).append("\n");
        sb.append("\n");

        String[] googlePackages = {GSF_PKG, GMS_PKG, VENDING_PKG, PLAY_GAMES_PKG};
        String[] googleLabels = {"GSF", "GMS", "Play Store", "Play Games"};

        for (int i = 0; i < googlePackages.length; i++) {
            String pkg = googlePackages[i];
            String label = googleLabels[i];

            sb.append("--- ").append(label).append(" (").append(pkg).append(") ---\n");

            // Host info
            ApplicationInfo hostAppInfo = null;
            PackageInfo hostPkgInfo = null;
            boolean hostInstalled = false;
            try {
                hostAppInfo = BlackBoxCore.getContext().getPackageManager().getApplicationInfo(pkg, 0);
                hostPkgInfo = BlackBoxCore.getContext().getPackageManager().getPackageInfo(pkg, 0);
                hostInstalled = true;
            } catch (PackageManager.NameNotFoundException ignored) {}

            sb.append("  [Host]\n");
            sb.append("    installed: ").append(hostInstalled).append("\n");
            if (hostInstalled && hostAppInfo != null) {
                sb.append("    sourceDir: ").append(hostAppInfo.sourceDir).append("\n");
                sb.append("    versionName: ").append(hostPkgInfo != null ? hostPkgInfo.versionName : "unknown").append("\n");
                sb.append("    versionCode: ").append(hostPkgInfo != null ? hostPkgInfo.longVersionCode : "unknown").append("\n");
                sb.append("    splitSourceDirs: ").append(hostAppInfo.splitSourceDirs != null ? Arrays.toString(hostAppInfo.splitSourceDirs) : "none").append("\n");
                sb.append("    nativeLibraryDir: ").append(hostAppInfo.nativeLibraryDir).append("\n");
                sb.append("    hasArm64: ").append(detectArm64(hostAppInfo)).append("\n");
                sb.append("    hasArmv7: ").append(detectArmv7(hostAppInfo)).append("\n");
            }

            // Virtual info
            boolean virtualInstalled = BlackBoxCore.get().isInstalled(pkg, userId);
            sb.append("  [Virtual]\n");
            sb.append("    installed: ").append(virtualInstalled).append("\n");
            if (virtualInstalled) {
                try {
                    ApplicationInfo virtualAppInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(pkg, 0, userId);
                    if (virtualAppInfo != null) {
                        sb.append("    sourceDir: ").append(virtualAppInfo.sourceDir).append("\n");
                        sb.append("    nativeLibraryDir: ").append(virtualAppInfo.nativeLibraryDir).append("\n");
                        sb.append("    splitSourceDirs: ").append(virtualAppInfo.splitSourceDirs != null ? Arrays.toString(virtualAppInfo.splitSourceDirs) : "none").append("\n");
                        sb.append("    hasArm64: ").append(detectArm64(virtualAppInfo)).append("\n");
                        sb.append("    hasArmv7: ").append(detectArmv7(virtualAppInfo)).append("\n");
                    }
                } catch (Exception e) {
                    sb.append("    (could not retrieve virtual ApplicationInfo: ").append(e.getMessage()).append(")\n");
                }
                try {
                    PackageInfo virtualPkgInfo = BlackBoxCore.getBPackageManager().getPackageInfo(pkg, 0, userId);
                    if (virtualPkgInfo != null) {
                        sb.append("    versionName: ").append(virtualPkgInfo.versionName).append("\n");
                        sb.append("    versionCode: ").append(virtualPkgInfo.longVersionCode).append("\n");
                    }
                } catch (Exception e) {
                    sb.append("    versionName: unknown\n");
                    sb.append("    versionCode: unknown\n");
                }
            }

            sb.append("\n");
        }

        sb.append("=== End of Diagnostic Report ===");
        return sb.toString();
    }

    // ======================== Game Login Readiness ========================

    /**
     * Get a detailed game login readiness report for the given virtual user.
     * Checks that all required Google packages are installed and ABI-compatible.
     */
    public static String getGameLoginReadinessReport(int userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Game Login Readiness Report ===\n");
        sb.append("User ID: ").append(userId).append("\n\n");

        boolean allInstalled = true;
        boolean abiCompatible = true;
        String missingPkgs = "";

        // Check each required package
        String[] requiredPkgs = {GSF_PKG, GMS_PKG, VENDING_PKG, PLAY_GAMES_PKG};
        String[] requiredLabels = {"GSF", "GMS (Play Services)", "Play Store", "Play Games"};

        for (int i = 0; i < requiredPkgs.length; i++) {
            String pkg = requiredPkgs[i];
            String label = requiredLabels[i];
            boolean virtualInstalled = BlackBoxCore.get().isInstalled(pkg, userId);
            sb.append(label).append(": ").append(virtualInstalled ? "INSTALLED" : "MISSING").append("\n");
            if (!virtualInstalled) {
                allInstalled = false;
                if (!missingPkgs.isEmpty()) missingPkgs += ", ";
                missingPkgs += label;
            }
        }

        // ABI compatibility check
        boolean is64BitProcess = BlackBoxCore.is64Bit();
        sb.append("\nProcess architecture: ").append(is64BitProcess ? "64-bit" : "32-bit").append("\n");

        if (!is64BitProcess) {
            // 32-bit process: host GMS must have ARMv7 libraries
            try {
                ApplicationInfo gmsAppInfo = BlackBoxCore.getContext().getPackageManager().getApplicationInfo(GMS_PKG, 0);
                boolean hostHasArmv7 = detectArmv7(gmsAppInfo);
                sb.append("Host GMS ARMv7 support: ").append(hostHasArmv7).append("\n");
                if (!hostHasArmv7) {
                    abiCompatible = false;
                    sb.append("WARNING: ARMv7 GMS login is not supported on this device because host Google packages do not include armeabi-v7a splits. Use the arm64-v8a build.\n");
                }
            } catch (PackageManager.NameNotFoundException e) {
                abiCompatible = false;
                sb.append("WARNING: Cannot check host GMS ABI (package not found).\n");
            }
        }

        sb.append("\n--- Result ---\n");
        if (allInstalled && abiCompatible) {
            sb.append("Result: READY\n");
            sb.append("All Google packages are installed and ABI-compatible.\n");
        } else {
            sb.append("Result: NOT READY\n");
            if (!allInstalled) {
                sb.append("Reason: Missing packages: ").append(missingPkgs).append("\n");
                sb.append("Fix: Install the missing Google packages. Run GMS install again or import missing APKs manually via GMS Manager.\n");
            }
            if (!abiCompatible) {
                sb.append("Reason: ABI incompatibility — ARMv7 GMS login is not supported on this device because host Google packages do not include armeabi-v7a splits. Use the arm64-v8a build.\n");
                sb.append("Fix: Use a device with ARMv7 Google Play Services, or switch to a 64-bit environment.\n");
            }
        }

        sb.append("\n=== End of Report ===");
        return sb.toString();
    }

    /**
     * Quick boolean check if game login is ready for the given virtual user.
     */
    public static boolean isGameLoginReady(int userId) {
        // All four required packages must be installed
        if (!BlackBoxCore.get().isInstalled(GSF_PKG, userId)) return false;
        if (!BlackBoxCore.get().isInstalled(GMS_PKG, userId)) return false;
        if (!BlackBoxCore.get().isInstalled(VENDING_PKG, userId)) return false;
        if (!BlackBoxCore.get().isInstalled(PLAY_GAMES_PKG, userId)) return false;

        // ABI compatibility: 32-bit process needs ARMv7 host GMS
        boolean is64BitProcess = BlackBoxCore.is64Bit();
        if (!is64BitProcess) {
            try {
                ApplicationInfo gmsAppInfo = BlackBoxCore.getContext().getPackageManager().getApplicationInfo(GMS_PKG, 0);
                if (!detectArmv7(gmsAppInfo)) return false;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if Play Games is missing in the virtual user.
     */
    public static boolean isPlayGamesMissing(int userId) {
        return !BlackBoxCore.get().isInstalled(PLAY_GAMES_PKG, userId);
    }
}
