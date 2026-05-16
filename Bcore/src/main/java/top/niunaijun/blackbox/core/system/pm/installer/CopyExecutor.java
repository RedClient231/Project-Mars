package top.niunaijun.blackbox.core.system.pm.installer;


import java.io.File;
import java.io.IOException;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.NativeUtils;
import top.niunaijun.blackbox.utils.Slog;


public class CopyExecutor implements Executor {
    private static final String TAG = "CopyExecutor";

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        try {
            if (!option.isFlag(InstallOption.FLAG_SYSTEM)) {
                // Copy native libs from base APK
                NativeUtils.copyNativeLib(new File(ps.pkg.baseCodePath), BEnvironment.getAppLibDir(ps.pkg.packageName));

                // Copy native libs from split APKs (e.g. ABI splits)
                if (ps.pkg.splitCodePaths != null && ps.pkg.splitCodePaths.length > 0) {
                    File libDir = BEnvironment.getAppLibDir(ps.pkg.packageName);
                    for (String splitPath : ps.pkg.splitCodePaths) {
                        if (splitPath != null) {
                            File splitFile = new File(splitPath);
                            if (splitFile.exists()) {
                                Slog.d(TAG, "Copying native libs from split: " + splitPath);
                                NativeUtils.copyNativeLib(splitFile, libDir);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
        if (option.isFlag(InstallOption.FLAG_STORAGE)) {
            
            File origFile = new File(ps.pkg.baseCodePath);
            File newFile = BEnvironment.getBaseApkDir(ps.pkg.packageName);
            try {
                if (option.isFlag(InstallOption.FLAG_URI_FILE)) {
                    boolean b = FileUtils.renameTo(origFile, newFile);
                    if (!b) {
                        FileUtils.copyFile(origFile, newFile);
                    }
                } else {
                    FileUtils.copyFile(origFile, newFile);
                }
                newFile.setReadOnly();
                
                ps.pkg.baseCodePath = newFile.getAbsolutePath();
            } catch (IOException e) {
                e.printStackTrace();
                return -1;
            }

            // Copy split APKs to virtual split directory
            if (ps.pkg.splitCodePaths != null && ps.pkg.splitCodePaths.length > 0) {
                try {
                    File splitDir = BEnvironment.getSplitApkDir(ps.pkg.packageName);
                    FileUtils.mkdirs(splitDir);

                    String[] newSplitPaths = new String[ps.pkg.splitCodePaths.length];
                    for (int i = 0; i < ps.pkg.splitCodePaths.length; i++) {
                        String oldSplitPath = ps.pkg.splitCodePaths[i];
                        if (oldSplitPath == null) {
                            newSplitPaths[i] = null;
                            continue;
                        }

                        File origSplitFile = new File(oldSplitPath);
                        // Derive split name from splitNames array or from filename
                        String splitName;
                        if (ps.pkg.splitNames != null && i < ps.pkg.splitNames.length && ps.pkg.splitNames[i] != null) {
                            splitName = ps.pkg.splitNames[i];
                        } else {
                            // Derive from filename: config.en.apk -> config.en
                            String fname = origSplitFile.getName();
                            splitName = fname.endsWith(".apk") ? fname.substring(0, fname.length() - 4) : fname;
                        }

                        File destFile = BEnvironment.getSplitApkFile(ps.pkg.packageName, splitName);
                        FileUtils.copyFile(origSplitFile, destFile);
                        destFile.setReadOnly();
                        newSplitPaths[i] = destFile.getAbsolutePath();
                        Slog.d(TAG, "Copied split APK: " + splitName + " -> " + destFile.getAbsolutePath());
                    }
                    ps.pkg.splitCodePaths = newSplitPaths;
                } catch (IOException e) {
                    Slog.e(TAG, "Failed to copy split APKs: " + e.getMessage(), e);
                    return -1;
                }
            }
        } else if (option.isFlag(InstallOption.FLAG_SYSTEM)) {
            
        }
        return 0;
    }
}
