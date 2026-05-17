package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;


public class GmsProxy extends BinderInvocationStub {
    public static final String TAG = "GmsProxy";

    public GmsProxy() {
        super(BRServiceManager.get().getService("gms"));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService("gms");
        if (binder == null) {
            Slog.e(TAG, "Failed to get gms service binder");
            return null;
        }
        try {
            Class<?> stubClass = Class.forName("com.google.android.gms.common.api.internal.IGmsServiceBroker$Stub");
            Method asInterfaceMethod = stubClass.getMethod("asInterface", IBinder.class);
            Object iface = asInterfaceMethod.invoke(null, binder);
            if (iface != null) {
                Slog.d(TAG, "Successfully obtained IGmsServiceBroker interface");
                return iface;
            } else {
                Slog.e(TAG, "Reflection succeeded but returned null interface");
                return null;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to get IGmsServiceBroker interface", e);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("gms");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    /**
     * Check if real GMS is installed in the current virtual user.
     * When real GMS is present, we should NOT use mock/fake responses
     * because they interfere with the real sign-in flow.
     */
    private static boolean isRealGmsInstalled() {
        try {
            int userId = BlackBoxCore.getUserId();
            return BlackBoxCore.get().isInstalled("com.google.android.gms", userId);
        } catch (Exception e) {
            return false;
        }
    }

    
    @ProxyMethod("getService")
    public static class GetService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                // Log the service being requested for diagnostics
                String serviceName = "unknown";
                if (args != null && args.length > 0) {
                    if (args[0] instanceof String) {
                        serviceName = (String) args[0];
                        Slog.d(TAG, "GmsProxy: getService called for: " + serviceName);
                    }
                    // Fix calling package — GMS inside virtual space should use
                    // the host package name for IPC with the real GMS service
                    String callingPackage = (String) args[0];
                    if ("com.google.android.gms".equals(callingPackage)) {
                        args[0] = BlackBoxCore.getHostPkg();
                        Slog.d(TAG, "GmsProxy: Fixed calling package from com.google.android.gms to " + BlackBoxCore.getHostPkg());
                    }
                }
                
                Object result = method.invoke(who, args);
                if (result != null) {
                    Slog.d(TAG, "GmsProxy: getService for " + serviceName + " returned: " + result.getClass().getSimpleName());
                } else {
                    Slog.w(TAG, "GmsProxy: getService for " + serviceName + " returned null");
                }
                return result;
            } catch (Exception e) {
                Slog.e(TAG, "GmsProxy: Error in getService", e);
                // When real GMS is installed, re-throw so the app sees the real error
                if (isRealGmsInstalled()) {
                    Slog.d(TAG, "GmsProxy: Real GMS installed, re-throwing getService error");
                    throw e.getCause() != null ? e.getCause() : e;
                }
                return null;
            }
        }
    }

    
    @ProxyMethod("getServiceBroker")
    public static class GetServiceBroker extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GmsProxy: getServiceBroker called");
                Object result = method.invoke(who, args);
                if (result != null) {
                    Slog.d(TAG, "GmsProxy: getServiceBroker returned: " + result.getClass().getSimpleName());
                } else {
                    Slog.w(TAG, "GmsProxy: getServiceBroker returned null");
                }
                return result;
            } catch (Exception e) {
                Slog.e(TAG, "GmsProxy: Error in getServiceBroker", e);
                // When real GMS is installed, re-throw so the caller sees the real error
                if (isRealGmsInstalled()) {
                    throw e.getCause() != null ? e.getCause() : e;
                }
                return null;
            }
        }
    }

    
    @ProxyMethod("authenticate")
    public static class Authenticate extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GmsProxy: Handling authenticate call");
                Object result = method.invoke(who, args);
                Slog.d(TAG, "GmsProxy: authenticate returned: " + (result != null ? result.getClass().getSimpleName() : "null"));
                return result;
            } catch (Exception e) {
                Slog.w(TAG, "GmsProxy: Authentication error", e);
                // Do NOT return mock auth result when real GMS is installed.
                // Let the error propagate so the caller can handle it properly
                // (e.g. show real sign-in UI or report SERVICE_MISSING).
                if (isRealGmsInstalled()) {
                    Slog.d(TAG, "GmsProxy: Real GMS installed, re-throwing authenticate error");
                    throw e.getCause() != null ? e.getCause() : e;
                }
                // Only return empty Bundle as fallback when no real GMS is available.
                Slog.d(TAG, "GmsProxy: No real GMS installed, returning empty fallback bundle");
                return createEmptyBundle();
            }
        }
    }

    
    @ProxyMethod("getAccount")
    public static class GetAccount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GmsProxy: Handling getAccount call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GmsProxy: GetAccount error, returning null", e);
                return null;
            }
        }
    }

    
    @ProxyMethod("getToken")
    public static class GetToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GmsProxy: Handling getToken call");
                Object result = method.invoke(who, args);
                if (result != null) {
                    Slog.d(TAG, "GmsProxy: getToken returned token (length=" + (result instanceof String ? ((String) result).length() : "unknown") + ")");
                } else {
                    Slog.d(TAG, "GmsProxy: getToken returned null");
                }
                return result;
            } catch (Exception e) {
                Slog.w(TAG, "GmsProxy: GetToken error", e);
                // Do NOT return mock token. A fake token will cause the server
                // to reject the request and make sign-in appear to succeed then fail.
                // When real GMS is installed, let the error propagate.
                if (isRealGmsInstalled()) {
                    Slog.d(TAG, "GmsProxy: Real GMS installed, re-throwing getToken error");
                    throw e.getCause() != null ? e.getCause() : e;
                }
                Slog.d(TAG, "GmsProxy: No real GMS, returning null token instead of mock");
                return null;
            }
        }
    }

    
    @ProxyMethod("invalidateToken")
    public static class InvalidateToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GmsProxy: Handling invalidateToken call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GmsProxy: InvalidateToken error, ignoring", e);
                return null;
            }
        }
    }

    
    @ProxyMethod("clearToken")
    public static class ClearToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GmsProxy: Handling clearToken call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GmsProxy: ClearToken error, ignoring", e);
                return null;
            }
        }
    }

    
    private static Object createEmptyBundle() {
        try {
            Class<?> bundleClass = Class.forName("android.os.Bundle");
            return bundleClass.newInstance();
        } catch (Exception e) {
            Slog.w(TAG, "Failed to create empty bundle", e);
            return null;
        }
    }
}
