package top.niunaijun.blackbox.core;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.accounts.BAccountManagerService;
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
                sb.append("    versionCode: ").append(hostPkgInfo != null ? hostPkgInfo.versionCode : "unknown").append("\n");
                sb.append("    splitSourceDirs: ").append(hostAppInfo.splitSourceDirs != null ? Arrays.toString(hostAppInfo.splitSourceDirs) : "none").append("\n");
                sb.append("    nativeLibraryDir: ").append(hostAppInfo.nativeLibraryDir).append("\n");
                sb.append("    nativeLibFiles: ").append(listNativeLibFiles(hostAppInfo.nativeLibraryDir)).append("\n");
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
                        sb.append("    nativeLibDirExists: ").append(virtualAppInfo.nativeLibraryDir != null && new File(virtualAppInfo.nativeLibraryDir).exists()).append("\n");
                        sb.append("    nativeLibFiles: ").append(listNativeLibFiles(virtualAppInfo.nativeLibraryDir)).append("\n");
                        sb.append("    nativeLibCount: ").append(countNativeLibFiles(virtualAppInfo.nativeLibraryDir)).append("\n");
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
                        sb.append("    versionCode: ").append(virtualPkgInfo.versionCode).append("\n");
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

    // ======================== Native Library Diagnostics ========================

    /**
     * List .so files in the given nativeLibraryDir.
     * Returns a formatted string like [lib1.so, lib2.so] or [] if empty/missing.
     */
    private static String listNativeLibFiles(String nativeLibraryDir) {
        if (nativeLibraryDir == null || nativeLibraryDir.isEmpty()) {
            return "[]";
        }
        File dir = new File(nativeLibraryDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return "[] (dir does not exist)";
        }
        String[] files = dir.list((dir1, name) -> name.endsWith(".so"));
        if (files == null || files.length == 0) {
            return "[] (empty)";
        }
        // Sort for consistent output
        Arrays.sort(files);
        // Limit to first 30 to avoid huge output
        if (files.length > 30) {
            String[] subset = new String[30];
            System.arraycopy(files, 0, subset, 0, 30);
            return Arrays.toString(subset) + " ... (" + files.length + " total)";
        }
        return Arrays.toString(files);
    }

    /**
     * Count .so files in the given nativeLibraryDir.
     */
    private static int countNativeLibFiles(String nativeLibraryDir) {
        if (nativeLibraryDir == null || nativeLibraryDir.isEmpty()) {
            return 0;
        }
        File dir = new File(nativeLibraryDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return 0;
        }
        String[] files = dir.list((dir1, name) -> name.endsWith(".so"));
        return files != null ? files.length : 0;
    }

    // ======================== Account Diagnostics ========================

    /**
     * Get AccountManager diagnostic info from the host device.
     * Reports accounts, Google accounts, authenticator types, and Google authenticator availability.
     * This is critical for diagnosing Play Games login hangs.
     *
     * NOTE: This queries the HOST AccountManager, not the virtual one.
     * The virtual AccountManager behavior depends on GoogleAccountManagerProxy,
     * but whether Google's authenticator can even run depends on whether GMS
     * services are properly resolved inside the virtual space.
     */
    public static String getAccountDiagnostic(int userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Account Diagnostic ===\n");
        sb.append("(Queried from host AccountManager)\n\n");

        try {
            Context context = BlackBoxCore.getContext();
            if (context == null) {
                sb.append("ERROR: BlackBoxCore context is null\n");
                return sb.toString();
            }

            AccountManager am = AccountManager.get(context);
            if (am == null) {
                sb.append("ERROR: AccountManager is null\n");
                return sb.toString();
            }

            // All accounts
            Account[] allAccounts = am.getAccounts();
            sb.append("allAccountsCount: ").append(allAccounts != null ? allAccounts.length : 0).append("\n");

            // Google accounts
            Account[] googleAccounts = am.getAccountsByType("com.google");
            sb.append("googleAccountsCount: ").append(googleAccounts != null ? googleAccounts.length : 0).append("\n");

            if (googleAccounts != null && googleAccounts.length > 0) {
                // Mask email addresses for privacy
                StringBuilder maskedEmails = new StringBuilder();
                for (Account account : googleAccounts) {
                    if (maskedEmails.length() > 0) maskedEmails.append(", ");
                    maskedEmails.append(maskEmail(account.name));
                }
                sb.append("googleAccounts: [").append(maskedEmails).append("]\n");
            }

            // Authenticator types
            AuthenticatorDescription[] authTypes = am.getAuthenticatorTypes();
            sb.append("authenticatorTypesCount: ").append(authTypes != null ? authTypes.length : 0).append("\n");

            boolean hasGoogleAuthenticator = false;
            if (authTypes != null) {
                StringBuilder typeList = new StringBuilder();
                for (AuthenticatorDescription desc : authTypes) {
                    if (typeList.length() > 0) typeList.append(", ");
                    typeList.append(desc.type).append("/").append(desc.packageName);
                    if ("com.google".equals(desc.type)) {
                        hasGoogleAuthenticator = true;
                    }
                }
                sb.append("authenticatorTypes: [").append(typeList).append("]\n");
            }

            sb.append("hasGoogleAuthenticator: ").append(hasGoogleAuthenticator).append("\n");

            if (!hasGoogleAuthenticator) {
                sb.append("\nWARNING: No Google authenticator found on host device!\n");
                sb.append("This means Play Games login will likely hang or fail.\n");
                sb.append("The Google account authenticator is provided by com.google.android.gms.\n");
                sb.append("Ensure Google Play Services is enabled on the host device.\n");
            }

            // Check if AccountAuthenticator service resolves for GMS in virtual PackageManager
            sb.append("\n--- Virtual Authenticator Service Check ---\n");
            Intent authIntent = new Intent("android.accounts.AccountAuthenticator");

            // Check SERVICES (not activities) — Google authenticator is a Service
            try {
                List<ResolveInfo> virtualAuthServices = BlackBoxCore.getBPackageManager()
                        .queryIntentServices(authIntent, 0, userId);
                sb.append("virtualAuthenticatorServices: ").append(virtualAuthServices != null ? virtualAuthServices.size() : 0).append("\n");
                if (virtualAuthServices != null) {
                    for (ResolveInfo ri : virtualAuthServices) {
                        if (ri.serviceInfo != null) {
                            sb.append("  service: ").append(ri.serviceInfo.packageName).append("/")
                                    .append(ri.serviceInfo.name).append("\n");
                            sb.append("    processName: ").append(ri.serviceInfo.processName != null ? ri.serviceInfo.processName : ri.serviceInfo.packageName).append("\n");
                            sb.append("    exported: ").append(ri.serviceInfo.exported).append("\n");
                            sb.append("    permission: ").append(ri.serviceInfo.permission != null ? ri.serviceInfo.permission : "none").append("\n");
                        }
                    }
                }
                if (virtualAuthServices == null || virtualAuthServices.isEmpty()) {
                    sb.append("  WARNING: No AccountAuthenticator services found in virtual PackageManager!\n");
                    sb.append("  Play Games/GMS cannot discover Google's authenticator service.\n");
                    sb.append("  This is likely why Play Games hangs during sign-in.\n");
                }
            } catch (Exception e) {
                sb.append("virtualAuthenticatorServices: ERROR - ").append(e.getMessage()).append("\n");
            }

            // Also check activities for completeness (less important but still useful)
            try {
                List<ResolveInfo> virtualAuthActivities = BlackBoxCore.getBPackageManager()
                        .queryIntentActivities(authIntent, 0, null, userId);
                sb.append("virtualAuthenticatorActivities: ").append(virtualAuthActivities != null ? virtualAuthActivities.size() : 0).append("\n");
                if (virtualAuthActivities != null && !virtualAuthActivities.isEmpty()) {
                    for (ResolveInfo ri : virtualAuthActivities) {
                        String pkg = ri.activityInfo != null ? ri.activityInfo.packageName : "unknown";
                        sb.append("  activity: ").append(pkg).append("/").append(ri.activityInfo != null ? ri.activityInfo.name : "?").append("\n");
                    }
                }
            } catch (Exception e) {
                sb.append("virtualAuthenticatorActivities: ERROR - ").append(e.getMessage()).append("\n");
            }

        } catch (SecurityException se) {
            sb.append("ERROR: SecurityException - ").append(se.getMessage()).append("\n");
            sb.append("The app may not have GET_ACCOUNTS permission.\n");
        } catch (Exception e) {
            sb.append("ERROR: ").append(e.getClass().getSimpleName()).append(" - ").append(e.getMessage()).append("\n");
        }

        sb.append("\n=== End of Account Diagnostic ===");
        return sb.toString();
    }

    /**
     * Mask an email address for privacy: a***@gmail.com
     */
    private static String maskEmail(String email) {
        if (email == null || email.isEmpty()) return "";
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) return "***";
        String localPart = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (localPart.length() <= 1) {
            return "*" + domain;
        }
        return localPart.charAt(0) + "***" + domain;
    }

    // ======================== Google Authenticator Diagnostic ========================

    /**
     * Get a focused diagnostic for Google authenticator service visibility.
     * This is the key diagnostic for Play Games sign-in — if Google's
     * AccountAuthenticator service cannot be resolved in the virtual
     * PackageManager, Play Games will hang during sign-in initialization.
     */
    public static String getGoogleAuthenticatorDiagnostic(int userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Google Authenticator Diagnostic ===\n");
        sb.append("User ID: ").append(userId).append("\n\n");

        // 1. Virtual Package Install State
        sb.append("--- Virtual Package Install State ---\n");
        sb.append("GSF virtual: ").append(BlackBoxCore.get().isInstalled(GSF_PKG, userId)).append("\n");
        sb.append("GMS virtual: ").append(BlackBoxCore.get().isInstalled(GMS_PKG, userId)).append("\n");
        sb.append("Play Games virtual: ").append(BlackBoxCore.get().isInstalled(PLAY_GAMES_PKG, userId)).append("\n");
        sb.append("\n");

        // 2. AccountAuthenticator service query
        sb.append("--- AccountAuthenticator Service Query ---\n");
        Intent authIntent = new Intent("android.accounts.AccountAuthenticator");

        try {
            List<ResolveInfo> virtualServices = BlackBoxCore.getBPackageManager()
                    .queryIntentServices(authIntent, 0, userId);
            sb.append("virtualAuthenticatorServicesCount: ").append(virtualServices != null ? virtualServices.size() : 0).append("\n");

            if (virtualServices != null && !virtualServices.isEmpty()) {
                for (ResolveInfo ri : virtualServices) {
                    if (ri.serviceInfo != null) {
                        sb.append("  service: ").append(ri.serviceInfo.packageName)
                                .append("/").append(ri.serviceInfo.name).append("\n");
                        sb.append("    processName: ").append(ri.serviceInfo.processName != null ? ri.serviceInfo.processName : ri.serviceInfo.packageName).append("\n");
                        sb.append("    exported: ").append(ri.serviceInfo.exported).append("\n");
                        sb.append("    permission: ").append(ri.serviceInfo.permission != null ? ri.serviceInfo.permission : "none").append("\n");
                    }
                }
            } else {
                sb.append("  (No services found for AccountAuthenticator intent)\n");
            }
        } catch (Exception e) {
            sb.append("virtualAuthenticatorServicesCount: ERROR - ").append(e.getMessage()).append("\n");
        }

        // 3. Host AccountManager authenticator types
        sb.append("\n--- Host AccountManager Authenticator Types ---\n");
        try {
            AccountManager am = AccountManager.get(BlackBoxCore.getContext());
            if (am != null) {
                AuthenticatorDescription[] authTypes = am.getAuthenticatorTypes();
                sb.append("hostAuthenticatorTypesCount: ").append(authTypes != null ? authTypes.length : 0).append("\n");
                if (authTypes != null) {
                    for (AuthenticatorDescription desc : authTypes) {
                        if ("com.google".equals(desc.type)) {
                            sb.append("  Google authenticator: type=").append(desc.type)
                                    .append(" package=").append(desc.packageName).append("\n");
                        }
                    }
                }
            }
        } catch (Exception e) {
            sb.append("ERROR: ").append(e.getMessage()).append("\n");
        }

        // 4. Direct GMS service lookup
        sb.append("\n--- Direct GMS Authenticator Service Lookup ---\n");
        String[] gmsAuthServiceNames = {
                "com.google.android.gms.auth.DefaultAuthDelegateService",
                "com.google.android.gms.auth.GetToken",
                "com.google.android.gms.auth.GoogleAuthServiceImpl",
                "com.google.android.gms.auth.account.be.PlatformAccountManagerService"
        };

        for (String serviceName : gmsAuthServiceNames) {
            try {
                ComponentName cn = new ComponentName(GMS_PKG, serviceName);
                ServiceInfo info = BlackBoxCore.getBPackageManager().getServiceInfo(cn, 0, userId);
                if (info != null) {
                    sb.append("  ").append(serviceName).append(": FOUND")
                            .append(" (exported=").append(info.exported)
                            .append(", permission=").append(info.permission != null ? info.permission : "none")
                            .append(")\n");
                } else {
                    sb.append("  ").append(serviceName).append(": NOT FOUND\n");
                }
            } catch (Exception e) {
                sb.append("  ").append(serviceName).append(": ERROR - ").append(e.getMessage()).append("\n");
            }
        }

        // 5. GSF login service
        sb.append("\n--- GSF Login Service Lookup ---\n");
        try {
            // GSF also provides an account authenticator
            ComponentName gsfLoginCn = new ComponentName(GSF_PKG,
                    "com.google.android.gsf.login.GoogleLoginService");
            ServiceInfo gsfInfo = BlackBoxCore.getBPackageManager().getServiceInfo(gsfLoginCn, 0, userId);
            if (gsfInfo != null) {
                sb.append("  GoogleLoginService: FOUND")
                        .append(" (exported=").append(gsfInfo.exported)
                        .append(", permission=").append(gsfInfo.permission != null ? gsfInfo.permission : "none")
                        .append(")\n");
            } else {
                sb.append("  GoogleLoginService: NOT FOUND\n");
            }
        } catch (Exception e) {
            sb.append("  GoogleLoginService: ERROR - ").append(e.getMessage()).append("\n");
        }

        // 6. Result
        sb.append("\n--- Result ---\n");
        try {
            List<ResolveInfo> services = BlackBoxCore.getBPackageManager()
                    .queryIntentServices(authIntent, 0, userId);
            if (services != null && !services.isEmpty()) {
                sb.append("Result: AUTHENTICATOR SERVICE PRESENT\n");
                sb.append(services.size()).append(" AccountAuthenticator service(s) found in virtual PackageManager.\n");
            } else {
                sb.append("Result: NOT READY\n");
                sb.append("Reason: No virtual Google AccountAuthenticator service found.\n");
                sb.append("Play Games cannot discover Google's authenticator service through PackageManager.\n");
                sb.append("This is likely why Play Games hangs during sign-in.\n");
                sb.append("\nPossible fixes:\n");
                sb.append("- Ensure GMS services with AccountAuthenticator intent-filter are registered in virtual PM\n");
                sb.append("- Check that BPackageManagerService.componentResolver has GMS services\n");
                sb.append("- Verify the queryIntentServices proxy hook is working\n");
            }
        } catch (Exception e) {
            sb.append("Result: ERROR - ").append(e.getMessage()).append("\n");
        }

        sb.append("\n=== End of Google Authenticator Diagnostic ===");
        return sb.toString();
    }

    // ======================== Launch Test ========================

    /**
     * Result of a Play Games launch test.
     */
    public static class LaunchTestResult {
        public final long timestamp;
        public final boolean launchIntentExists;
        public final String resolvedActivity;
        public final String resolvedProcessName;
        public final String resolvedSourceDir;
        public final String resolvedSplitSourceDirs;
        public final boolean launchResult;
        public final boolean watchdogTimedOut;
        public final String error;

        public LaunchTestResult(long timestamp, boolean launchIntentExists, String resolvedActivity,
                                String resolvedProcessName, String resolvedSourceDir,
                                String resolvedSplitSourceDirs, boolean launchResult,
                                boolean watchdogTimedOut, String error) {
            this.timestamp = timestamp;
            this.launchIntentExists = launchIntentExists;
            this.resolvedActivity = resolvedActivity;
            this.resolvedProcessName = resolvedProcessName;
            this.resolvedSourceDir = resolvedSourceDir;
            this.resolvedSplitSourceDirs = resolvedSplitSourceDirs;
            this.launchResult = launchResult;
            this.watchdogTimedOut = watchdogTimedOut;
            this.error = error;
        }

        public String toReportString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Play Games Launch Test ===\n");
            sb.append("timestamp: ").append(timestamp).append("\n");
            sb.append("launchIntentExists: ").append(launchIntentExists).append("\n");
            sb.append("resolvedActivity: ").append(resolvedActivity != null ? resolvedActivity : "null").append("\n");
            sb.append("resolvedProcessName: ").append(resolvedProcessName != null ? resolvedProcessName : "null").append("\n");
            sb.append("resolvedSourceDir: ").append(resolvedSourceDir != null ? resolvedSourceDir : "null").append("\n");
            sb.append("resolvedSplitSourceDirs: ").append(resolvedSplitSourceDirs != null ? resolvedSplitSourceDirs : "null").append("\n");
            sb.append("launchResult: ").append(launchResult).append("\n");
            sb.append("watchdogTimedOut: ").append(watchdogTimedOut).append("\n");
            if (error != null && !error.isEmpty()) {
                sb.append("error: ").append(error).append("\n");
            }

            if (!launchIntentExists) {
                sb.append("\nDIAGNOSIS: Play Games has no launch intent in virtual PackageManager.\n");
                sb.append("This means Play Games activity cannot be resolved.\n");
                sb.append("Possible fix: Check PackageManager activity resolution for com.google.android.play.games.\n");
            } else if (launchResult && watchdogTimedOut) {
                sb.append("\nDIAGNOSIS: Launch was requested but the 15-second watchdog timed out.\n");
                sb.append("Play Games may be hanging during startup (Application.onCreate, GMS init, or account check).\n");
                sb.append("Check the Runtime Events diagnostic for lifecycle events after this launch.\n");
            } else if (!launchResult) {
                sb.append("\nDIAGNOSIS: launchApk() returned false.\n");
                sb.append("The virtual framework refused to launch the activity.\n");
            }

            sb.append("\n=== End of Launch Test ===");
            return sb.toString();
        }
    }

    /** Store the last launch test result for inclusion in diagnostics */
    private static volatile LaunchTestResult lastLaunchTestResult = null;

    /**
     * Get the last launch test result.
     */
    public static LaunchTestResult getLastLaunchTestResult() {
        return lastLaunchTestResult;
    }

    /**
     * Perform a Play Games launch test.
     * Resolves the launch intent, attempts to launch, and runs a 15-second watchdog.
     * This method is blocking — call from a background thread.
     */
    public static LaunchTestResult testPlayGamesLaunch(int userId) {
        long timestamp = System.currentTimeMillis();
        boolean launchIntentExists = false;
        String resolvedActivity = null;
        String resolvedProcessName = null;
        String resolvedSourceDir = null;
        String resolvedSplitSourceDirs = null;
        boolean launchResult = false;
        boolean watchdogTimedOut = false;
        String error = null;

        try {
            // Step 1: Resolve launch intent
            Intent launchIntent = BlackBoxCore.getBPackageManager()
                    .getLaunchIntentForPackage(PLAY_GAMES_PKG, userId);

            if (launchIntent != null) {
                launchIntentExists = true;

                // Step 2: Resolve the activity info
                try {
                    List<ResolveInfo> resolveInfos = BlackBoxCore.getBPackageManager()
                            .queryIntentActivities(launchIntent, 0, null, userId);
                    if (resolveInfos != null && !resolveInfos.isEmpty()) {
                        ResolveInfo ri = resolveInfos.get(0);
                        if (ri.activityInfo != null) {
                            resolvedActivity = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                            resolvedProcessName = ri.activityInfo.processName;
                            resolvedSourceDir = ri.activityInfo.applicationInfo != null
                                    ? ri.activityInfo.applicationInfo.sourceDir : null;
                            resolvedSplitSourceDirs = ri.activityInfo.applicationInfo != null
                                    ? (ri.activityInfo.applicationInfo.splitSourceDirs != null
                                    ? Arrays.toString(ri.activityInfo.applicationInfo.splitSourceDirs) : null)
                                    : null;
                        }
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Could not resolve activity info for Play Games: " + e.getMessage());
                    resolvedActivity = "resolve_error: " + e.getMessage();
                }
            }

            // Step 3: Attempt launch
            if (launchIntentExists) {
                int eventCountBefore = GoogleRuntimeEventLogger.getEventCount();
                launchResult = BlackBoxCore.get().launchApk(PLAY_GAMES_PKG, userId);

                // Step 4: 15-second watchdog — check if lifecycle events appear
                long watchdogStart = System.currentTimeMillis();
                long watchdogTimeout = 15000;
                while (System.currentTimeMillis() - watchdogStart < watchdogTimeout) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    // Check if new Google events appeared
                    int eventCountAfter = GoogleRuntimeEventLogger.getEventCount();
                    if (eventCountAfter > eventCountBefore) {
                        // Lifecycle events are appearing — the app is at least starting
                        Slog.d(TAG, "Play Games launch: " + (eventCountAfter - eventCountBefore)
                                + " new runtime events detected after launch");
                        break;
                    }
                }

                if (GoogleRuntimeEventLogger.getEventCount() == eventCountBefore) {
                    watchdogTimedOut = true;
                    Slog.w(TAG, "Play Games launch watchdog timed out — no runtime events detected in 15s");
                }
            }

        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            Slog.e(TAG, "Play Games launch test error", e);
        }

        LaunchTestResult result = new LaunchTestResult(
                timestamp, launchIntentExists, resolvedActivity, resolvedProcessName,
                resolvedSourceDir, resolvedSplitSourceDirs, launchResult, watchdogTimedOut, error
        );

        lastLaunchTestResult = result;
        return result;
    }

    // ======================== Provider Diagnostics ========================

    /**
     * Get provider diagnostic info for Google packages.
     * Lists all ContentProviders declared in virtual PackageInfo for GMS and Play Games,
     * then checks if they can be resolved by the virtual PackageManager.
     */
    public static String getProviderDiagnostic(int userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Provider Diagnostic ===\n");
        sb.append("User ID: ").append(userId).append("\n\n");

        String[] packagesToCheck = {GMS_PKG, PLAY_GAMES_PKG, GSF_PKG, VENDING_PKG};
        String[] packageLabels = {"GMS", "Play Games", "GSF", "Play Store"};

        for (int i = 0; i < packagesToCheck.length; i++) {
            String pkg = packagesToCheck[i];
            String label = packageLabels[i];

            sb.append("--- ").append(label).append(" (").append(pkg).append(") ---\n");

            boolean virtualInstalled = BlackBoxCore.get().isInstalled(pkg, userId);
            if (!virtualInstalled) {
                sb.append("  Not installed in virtual — skipping\n\n");
                continue;
            }

            // Get PackageInfo with providers
            try {
                PackageInfo pkgInfo = BlackBoxCore.getBPackageManager()
                        .getPackageInfo(pkg, PackageManager.GET_PROVIDERS, userId);

                if (pkgInfo != null && pkgInfo.providers != null && pkgInfo.providers.length > 0) {
                    sb.append("  Declared providers: ").append(pkgInfo.providers.length).append("\n");
                    for (ProviderInfo provider : pkgInfo.providers) {
                        sb.append("    authority: ").append(provider.authority).append("\n");
                        sb.append("      name: ").append(provider.name).append("\n");
                        sb.append("      packageName: ").append(provider.packageName).append("\n");
                        sb.append("      exported: ").append(provider.exported).append("\n");

                        // Try to resolve this provider in virtual PackageManager
                        try {
                            ProviderInfo resolved = BlackBoxCore.getBPackageManager()
                                    .resolveContentProvider(provider.authority, 0, userId);
                            sb.append("      resolvesInVirtual: ").append(resolved != null).append("\n");
                            if (resolved != null && !resolved.packageName.equals(provider.packageName)) {
                                sb.append("      WARNING: resolved to different package: ").append(resolved.packageName).append("\n");
                            }
                        } catch (Exception e) {
                            sb.append("      resolvesInVirtual: ERROR - ").append(e.getMessage()).append("\n");
                        }
                    }
                } else {
                    sb.append("  Declared providers: 0 (or could not retrieve)\n");
                }
            } catch (Exception e) {
                sb.append("  ERROR getting providers: ").append(e.getMessage()).append("\n");
            }

            sb.append("\n");
        }

        // Also check some well-known GMS authorities
        sb.append("--- Well-known GMS Provider Authority Check ---\n");
        String[] knownAuthorities = {
                "com.google.android.gms.chimera",
                "com.google.android.gms.games",
                "com.google.android.gsf.gservices",
                "com.google.android.gms.measurement.google_measurement_service",
                "com.google.android.gms.auth.accounts",
                "com.google.android.gms.fitness.app_state_provider",
                "com.google.android.gms.appstate.internal"
        };

        for (String authority : knownAuthorities) {
            try {
                ProviderInfo resolved = BlackBoxCore.getBPackageManager()
                        .resolveContentProvider(authority, 0, userId);
                if (resolved != null) {
                    sb.append("  ").append(authority).append(": resolves to ").append(resolved.packageName).append("/").append(resolved.name).append("\n");
                } else {
                    sb.append("  ").append(authority).append(": NOT RESOLVED (null)\n");
                }
            } catch (Exception e) {
                sb.append("  ").append(authority).append(": ERROR - ").append(e.getMessage()).append("\n");
            }
        }

        sb.append("\n=== End of Provider Diagnostic ===");
        return sb.toString();
    }

    // ======================== Service Diagnostics ========================

    /**
     * Get service diagnostic info for Google packages.
     * Checks if AccountAuthenticator service can be resolved for GMS in virtual PackageManager.
     */
    public static String getServiceDiagnostic(int userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Service Diagnostic ===\n");
        sb.append("User ID: ").append(userId).append("\n\n");

        // Check AccountAuthenticator service resolution
        sb.append("--- AccountAuthenticator Service ---\n");
        Intent authIntent = new Intent("android.accounts.AccountAuthenticator");
        try {
            List<ResolveInfo> activities = BlackBoxCore.getBPackageManager()
                    .queryIntentActivities(authIntent, 0, null, userId);
            sb.append("queryIntentActivities(AccountAuthenticator): ").append(activities != null ? activities.size() : 0).append(" results\n");
            if (activities != null) {
                for (ResolveInfo ri : activities) {
                    String actPkg = ri.activityInfo != null ? ri.activityInfo.packageName : "unknown";
                    String actName = ri.activityInfo != null ? ri.activityInfo.name : "unknown";
                    sb.append("  activity: ").append(actPkg).append("/").append(actName).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("queryIntentActivities(AccountAuthenticator): ERROR - ").append(e.getMessage()).append("\n");
        }

        // Also check via ServiceInfo for GMS authenticator
        sb.append("\n--- GMS Authenticator Service (direct lookup) ---\n");
        try {
            // GMS has an AccountAuthenticator service, try to resolve it directly
            ComponentName gmsAuthService = new ComponentName(GMS_PKG,
                    "com.google.android.gms.auth.DefaultAuthDelegateService");
            ServiceInfo serviceInfo = BlackBoxCore.getBPackageManager()
                    .getServiceInfo(gmsAuthService, 0, userId);
            if (serviceInfo != null) {
                sb.append("GMS DefaultAuthDelegateService: FOUND\n");
                sb.append("  packageName: ").append(serviceInfo.packageName).append("\n");
                sb.append("  name: ").append(serviceInfo.name).append("\n");
                sb.append("  exported: ").append(serviceInfo.exported).append("\n");
                sb.append("  permission: ").append(serviceInfo.permission).append("\n");
            } else {
                sb.append("GMS DefaultAuthDelegateService: NOT FOUND (null)\n");
            }
        } catch (Exception e) {
            sb.append("GMS DefaultAuthDelegateService: ERROR - ").append(e.getMessage()).append("\n");
        }

        // Try alternative authenticator service name
        try {
            ComponentName gmsAuthService2 = new ComponentName(GMS_PKG,
                    "com.google.android.gms.auth.GetToken");
            ServiceInfo serviceInfo2 = BlackBoxCore.getBPackageManager()
                    .getServiceInfo(gmsAuthService2, 0, userId);
            if (serviceInfo2 != null) {
                sb.append("GMS GetToken service: FOUND\n");
                sb.append("  packageName: ").append(serviceInfo2.packageName).append("\n");
            } else {
                sb.append("GMS GetToken service: NOT FOUND (null)\n");
            }
        } catch (Exception e) {
            sb.append("GMS GetToken service: ERROR - ").append(e.getMessage()).append("\n");
        }

        // List all services from GMS PackageInfo
        sb.append("\n--- GMS Declared Services (from PackageInfo) ---\n");
        try {
            PackageInfo gmsPkgInfo = BlackBoxCore.getBPackageManager()
                    .getPackageInfo(GMS_PKG, PackageManager.GET_SERVICES, userId);
            if (gmsPkgInfo != null && gmsPkgInfo.services != null) {
                sb.append("Total GMS services: ").append(gmsPkgInfo.services.length).append("\n");
                // Only list services with "auth" or "account" in name to keep output manageable
                int relevantCount = 0;
                for (ServiceInfo svc : gmsPkgInfo.services) {
                    String svcNameLower = svc.name.toLowerCase();
                    if (svcNameLower.contains("auth") || svcNameLower.contains("account")
                            || svcNameLower.contains("login") || svcNameLower.contains("games")) {
                        sb.append("  ").append(svc.name).append(" (exported=").append(svc.exported).append(")\n");
                        relevantCount++;
                    }
                }
                if (relevantCount == 0) {
                    sb.append("  (No auth/account/login/games services found — listing first 10)\n");
                    int count = 0;
                    for (ServiceInfo svc : gmsPkgInfo.services) {
                        sb.append("  ").append(svc.name).append(" (exported=").append(svc.exported).append(")\n");
                        count++;
                        if (count >= 10) {
                            sb.append("  ... and ").append(gmsPkgInfo.services.length - 10).append(" more\n");
                            break;
                        }
                    }
                }
            } else {
                sb.append("No services in PackageInfo (or could not retrieve)\n");
            }
        } catch (Exception e) {
            sb.append("ERROR: ").append(e.getMessage()).append("\n");
        }

        // Play Games services
        sb.append("\n--- Play Games Declared Services ---\n");
        try {
            PackageInfo pgPkgInfo = BlackBoxCore.getBPackageManager()
                    .getPackageInfo(PLAY_GAMES_PKG, PackageManager.GET_SERVICES, userId);
            if (pgPkgInfo != null && pgPkgInfo.services != null) {
                sb.append("Total Play Games services: ").append(pgPkgInfo.services.length).append("\n");
                for (ServiceInfo svc : pgPkgInfo.services) {
                    sb.append("  ").append(svc.name).append(" (exported=").append(svc.exported).append(")\n");
                }
            } else {
                sb.append("No services in PackageInfo (or could not retrieve)\n");
            }
        } catch (Exception e) {
            sb.append("ERROR: ").append(e.getMessage()).append("\n");
        }

        sb.append("\n=== End of Service Diagnostic ===");
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
            sb.append("NOTE: READY means packages are present, NOT that Play Games will work.\n");
            sb.append("If Play Games hangs, use the runtime diagnostics (Account, Launch Test, Provider, Service) to find the exact hang point.\n");
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

    // ======================== Host Account Visibility Diagnostic ========================

    /**
     * Get a diagnostic comparing host Google accounts with virtual Google accounts.
     * This is the key diagnostic for the "account passthrough" feature — if host
     * Google accounts exist but virtual accounts are 0, the passthrough is not working.
     *
     * @param userId the virtual user ID
     * @return formatted diagnostic string
     */
    public static String getHostAccountVisibilityDiagnostic(int userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Host Google Account Visibility Diagnostic ===\n");
        sb.append("User ID: ").append(userId).append("\n\n");

        boolean accountPassthroughUsed = false;

        // 1. Query the HOST AccountManager
        int hostAllAccountsCount = 0;
        int hostGoogleAccountsCount = 0;
        String hostGoogleAccountsMasked = "";
        boolean hasGoogleAuthenticator = false;

        try {
            Context context = BlackBoxCore.getContext();
            if (context == null) {
                sb.append("ERROR: BlackBoxCore context is null\n");
                sb.append("\n=== End of Host Google Account Visibility Diagnostic ===");
                return sb.toString();
            }

            AccountManager am = AccountManager.get(context);
            if (am == null) {
                sb.append("ERROR: Host AccountManager is null\n");
                sb.append("\n=== End of Host Google Account Visibility Diagnostic ===");
                return sb.toString();
            }

            // All host accounts
            Account[] allAccounts = am.getAccounts();
            hostAllAccountsCount = allAccounts != null ? allAccounts.length : 0;

            // Google accounts on host
            Account[] googleAccounts = am.getAccountsByType("com.google");
            hostGoogleAccountsCount = googleAccounts != null ? googleAccounts.length : 0;

            if (googleAccounts != null && googleAccounts.length > 0) {
                StringBuilder maskedBuilder = new StringBuilder();
                for (Account account : googleAccounts) {
                    if (maskedBuilder.length() > 0) maskedBuilder.append(", ");
                    maskedBuilder.append(maskEmail(account.name));
                }
                hostGoogleAccountsMasked = maskedBuilder.toString();
            }

            // Authenticator types
            AuthenticatorDescription[] authTypes = am.getAuthenticatorTypes();
            if (authTypes != null) {
                for (AuthenticatorDescription desc : authTypes) {
                    if ("com.google".equals(desc.type)) {
                        hasGoogleAuthenticator = true;
                        break;
                    }
                }
            }
        } catch (SecurityException se) {
            sb.append("ERROR: SecurityException querying host AccountManager - ").append(se.getMessage()).append("\n");
            sb.append("The app may not have GET_ACCOUNTS permission.\n");
            sb.append("Permission details: android.permission.GET_ACCOUNTS is required on Android 5 and below.\n");
            sb.append("On Android 6+ it is a normal permission. On Android 11+ it may not be needed.\n");
        } catch (Exception e) {
            sb.append("ERROR: ").append(e.getClass().getSimpleName()).append(" querying host AccountManager - ").append(e.getMessage()).append("\n");
        }

        // 2. Query the VIRTUAL account system
        int virtualGoogleAccountsCount = 0;
        try {
            Account[] virtualAccounts = BAccountManagerService.get()
                    .getAccountsAsUser("com.google", userId);
            virtualGoogleAccountsCount = virtualAccounts != null ? virtualAccounts.length : 0;
        } catch (SecurityException se) {
            sb.append("ERROR: SecurityException querying virtual accounts - ").append(se.getMessage()).append("\n");
        } catch (Exception e) {
            sb.append("ERROR: ").append(e.getClass().getSimpleName()).append(" querying virtual accounts - ").append(e.getMessage()).append("\n");
        }

        // Determine if passthrough would be used
        accountPassthroughUsed = hostGoogleAccountsCount > 0 && virtualGoogleAccountsCount == 0;

        // 3. Report
        sb.append("hostAllAccountsCount: ").append(hostAllAccountsCount).append("\n");
        sb.append("hostGoogleAccountsCount: ").append(hostGoogleAccountsCount).append("\n");
        sb.append("hostGoogleAccountsMasked: ").append(hostGoogleAccountsMasked.isEmpty() ? "(none)" : hostGoogleAccountsMasked).append("\n");
        sb.append("hasGoogleAuthenticator: ").append(hasGoogleAuthenticator).append("\n");
        sb.append("\n");
        sb.append("virtualGoogleAccountsCount: ").append(virtualGoogleAccountsCount).append("\n");
        sb.append("accountPassthroughUsed: ").append(accountPassthroughUsed).append("\n");
        sb.append("\n");

        // 4. Diagnosis
        if (hostGoogleAccountsCount > 0 && virtualGoogleAccountsCount == 0) {
            sb.append("PROBLEM: Host Google accounts exist but are not visible to virtual apps.\n");
            sb.append("FIX NEEDED: Account passthrough must forward host accounts to virtual AccountManager.\n");
            sb.append("The IAccountManagerProxy should fall back to the real system AccountManager\n");
            sb.append("when the virtual BAccountManagerService has no Google accounts.\n");
        } else if (hostGoogleAccountsCount > 0 && virtualGoogleAccountsCount > 0) {
            sb.append("OK: Both host and virtual have Google accounts. Passthrough may not be needed,\n");
            sb.append("or passthrough is already working.\n");
        } else if (hostGoogleAccountsCount == 0) {
            sb.append("INFO: No Google accounts found on host device.\n");
            sb.append("The user needs to add a Google account to the real device first.\n");
        }

        sb.append("\n=== End of Host Google Account Visibility Diagnostic ===");
        return sb.toString();
    }
}
