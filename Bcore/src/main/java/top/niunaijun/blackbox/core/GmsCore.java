package top.niunaijun.blackbox.core;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
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

    /**
     * Check whether the host Google packages have native libraries compatible
     * with the current process ABI. If the app is running as a 32-bit process
     * but the host Google packages only provide arm64-v8a splits, the cloned
     * packages will fail to load their native code at runtime.
     *
     * Returns null if the ABI is compatible, or an error message if not.
     */
    private static String checkAbiCompatibility() {
        boolean is64Bit = BlackBoxCore.is64Bit();
        Slog.d(TAG, "ABI check: current process is64Bit=" + is64Bit);

        if (is64Bit) {
            // 64-bit process can load both 32-bit and 64-bit native libs on most devices
            return null;
        }

        // 32-bit process — check whether host Google packages have ARM64-only splits
        boolean foundArm64OnlySplit = false;
        boolean foundArmv7Split = false;
        String[] criticalPkgs = {GMS_PKG, VENDING_PKG, PLAY_GAMES_PKG};

        for (String pkg : criticalPkgs) {
            try {
                PackageInfo pi = BlackBoxCore.getPackageManager().getPackageInfo(pkg, 0);
                if (pi != null && pi.applicationInfo != null) {
                    String[] splits = pi.applicationInfo.splitSourceDirs;
                    if (splits != null) {
                        for (String split : splits) {
                            if (split == null) continue;
                            String name = new File(split).getName().toLowerCase();
                            if (name.contains("arm64_v8a") || name.contains("arm64")) {
                                foundArm64OnlySplit = true;
                            }
                            if (name.contains("armeabi_v7a") || name.contains("armv7") || name.contains("armeabi")) {
                                foundArmv7Split = true;
                            }
                        }
                    }
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                // Package not on host — will be caught during install loop
            }
        }

        Slog.d(TAG, "ABI check: foundArm64OnlySplit=" + foundArm64OnlySplit
                + ", foundArmv7Split=" + foundArmv7Split);

        if (foundArm64OnlySplit && !foundArmv7Split) {
            return "GMS login is not supported in ARMv7 mode on this device because "
                    + "host Google packages do not include armeabi-v7a splits. "
                    + "Use arm64-v8a build of Project Mars instead.";
        }

        return null;
    }

    public static InstallResult installGApps(int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        List<String> failedPackages = new ArrayList<>();
        List<String> skippedPackages = new ArrayList<>();
        List<String> installedPackages = new ArrayList<>();

        // Pre-install ABI compatibility check for 32-bit processes
        String abiError = checkAbiCompatibility();
        if (abiError != null) {
            Slog.e(TAG, "ABI compatibility check failed: " + abiError);
            return new InstallResult().installError(abiError);
        }

        // Log host Google package details before starting install
        Slog.d(TAG, "Starting GMS install for user " + userId);
        logHostGooglePackageDetails();

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

        // Log post-install diagnostic info
        Slog.d(TAG, "GMS install complete. Installed: " + installedPackages
                + ", Skipped (optional): " + skippedPackages);
        Slog.d(TAG, getGmsDiagnosticInfo(userId));

        return new InstallResult();
    }

    /**
     * Log details about host Google packages (sourceDir, splitSourceDirs, nativeLibDir)
     * to help diagnose install issues before they happen.
     */
    private static void logHostGooglePackageDetails() {
        for (String pkg : GOOGLE_INSTALL_ORDER) {
            try {
                PackageInfo pi = BlackBoxCore.getPackageManager().getPackageInfo(pkg,
                        PackageManager.GET_SHARED_LIBRARY_FILES);
                if (pi != null && pi.applicationInfo != null) {
                    ApplicationInfo ai = pi.applicationInfo;
                    Slog.d(TAG, "Host " + pkg + ":"
                            + " sourceDir=" + ai.sourceDir
                            + ", splitSourceDirs=" + Arrays.toString(ai.splitSourceDirs)
                            + ", nativeLibDir=" + ai.nativeLibraryDir
                            + ", versionName=" + pi.versionName
                            + ", versionCode=" + pi.versionCode);
                }
            } catch (PackageManager.NameNotFoundException e) {
                Slog.d(TAG, "Host " + pkg + ": NOT INSTALLED on device");
            }
        }
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
     * Get comprehensive diagnostic info about Google packages for in-app display.
     * Reports device info, host presence, virtual presence, sourceDir, splitSourceDirs,
     * nativeLibDir, versionName, versionCode, and ABI split detection.
     *
     * This is designed to be copy-able so users can paste it without ADB.
     */
    public static String getGmsDiagnosticInfo(int userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Project Mars GMS Diagnostic ===\n\n");

        // Device info
        sb.append("--- Device Info ---\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ")
                .append(Build.MODEL).append("\n");
        sb.append("CPU ABI list: ").append(Build.SUPPORTED_ABIS != null
                ? Arrays.toString(Build.SUPPORTED_ABIS) : "unknown").append("\n");
        sb.append("Process 64-bit: ").append(BlackBoxCore.is64Bit()).append("\n");
        sb.append("Virtual user: ").append(userId).append("\n");
        sb.append("\n");

        // Per-package info
        for (String pkg : GOOGLE_INSTALL_ORDER) {
            sb.append("--- ").append(pkg).append(" ---\n");

            // Host package info
            boolean onHost = false;
            PackageInfo hostPi = null;
            try {
                hostPi = BlackBoxCore.getContext().getPackageManager().getPackageInfo(pkg,
                        PackageManager.GET_SHARED_LIBRARY_FILES);
                onHost = true;
            } catch (PackageManager.NameNotFoundException ignored) {}

            sb.append("  host: ").append(onHost).append("\n");

            if (onHost && hostPi != null && hostPi.applicationInfo != null) {
                ApplicationInfo hai = hostPi.applicationInfo;
                sb.append("  hostSourceDir: ").append(hai.sourceDir).append("\n");
                sb.append("  hostSplits: ").append(Arrays.toString(hai.splitSourceDirs)).append("\n");
                sb.append("  hostNativeLibDir: ").append(hai.nativeLibraryDir).append("\n");
                sb.append("  hostVersionName: ").append(hostPi.versionName).append("\n");
                sb.append("  hostVersionCode: ").append(hostPi.versionCode).append("\n");

                // Detect ABI splits
                boolean hasArm64 = false;
                boolean hasArmv7 = false;
                if (hai.splitSourceDirs != null) {
                    for (String split : hai.splitSourceDirs) {
                        if (split == null) continue;
                        String name = new File(split).getName().toLowerCase();
                        if (name.contains("arm64_v8a") || name.contains("arm64")) hasArm64 = true;
                        if (name.contains("armeabi_v7a") || name.contains("armv7") || name.contains("armeabi")) hasArmv7 = true;
                    }
                }
                sb.append("  hostHasArm64: ").append(hasArm64).append("\n");
                sb.append("  hostHasArmv7: ").append(hasArmv7).append("\n");
            }

            // Virtual package info
            boolean inVirtual = BlackBoxCore.get().isInstalled(pkg, userId);
            sb.append("  virtual: ").append(inVirtual).append("\n");

            if (inVirtual) {
                try {
                    top.niunaijun.blackbox.fake.frameworks.BPackageManager bpm =
                            BlackBoxCore.getBPackageManager();
                    PackageInfo vpi = bpm.getPackageInfo(pkg,
                            PackageManager.GET_SHARED_LIBRARY_FILES, userId);
                    if (vpi != null && vpi.applicationInfo != null) {
                        ApplicationInfo vai = vpi.applicationInfo;
                        sb.append("  virtualSourceDir: ").append(vai.sourceDir).append("\n");
                        sb.append("  virtualSplits: ").append(Arrays.toString(vai.splitSourceDirs)).append("\n");
                        sb.append("  virtualNativeLibDir: ").append(vai.nativeLibraryDir).append("\n");
                        sb.append("  virtualVersionName: ").append(vpi.versionName).append("\n");
                        sb.append("  virtualVersionCode: ").append(vpi.versionCode).append("\n");

                        // Detect ABI splits in virtual
                        boolean vHasArm64 = false;
                        boolean vHasArmv7 = false;
                        if (vai.splitSourceDirs != null) {
                            for (String split : vai.splitSourceDirs) {
                                if (split == null) continue;
                                String name = new File(split).getName().toLowerCase();
                                if (name.contains("arm64_v8a") || name.contains("arm64")) vHasArm64 = true;
                                if (name.contains("armeabi_v7a") || name.contains("armv7") || name.contains("armeabi")) vHasArmv7 = true;
                            }
                        }
                        sb.append("  virtualHasArm64: ").append(vHasArm64).append("\n");
                        sb.append("  virtualHasArmv7: ").append(vHasArmv7).append("\n");
                    } else {
                        sb.append("  virtualPackageInfo: null (package installed but info unavailable)\n");
                    }
                } catch (Exception e) {
                    sb.append("  virtualInfoError: ").append(e.getMessage()).append("\n");
                }
            }

            sb.append("\n");
        }

        return sb.toString();
    }
}
