package top.niunaijun.blackbox.fake.service;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.app.BActivityThread;


public class GoogleAccountManagerProxy extends ClassInvocationStub {
    public static final String TAG = "GoogleAccountManagerProxy";

    public GoogleAccountManagerProxy() {
        super();
    }

    @Override
    protected Object getWho() {
        try {
            Context context = BlackBoxCore.getContext();
            if (context != null) {
                return AccountManager.get(context);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get AccountManager instance", e);
        }
        return null;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    /**
     * Check if real GMS is installed in the current virtual user.
     * When real GMS is installed, we must NOT return mock accounts
     * because they interfere with real Google sign-in flow.
     */
    private static boolean isRealGmsInstalled() {
        try {
            int userId = BActivityThread.getUserId();
            return BlackBoxCore.get().isInstalled("com.google.android.gms", userId);
        } catch (Exception e) {
            return false;
        }
    }

    @ProxyMethod("getAccounts")
    public static class GetAccounts extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling getAccounts call");
                
                // Always try to get real accounts first
                Object result = method.invoke(who, args);
                if (result != null && result instanceof Account[]) {
                    Account[] accounts = (Account[]) result;
                    if (accounts.length > 0) {
                        Slog.d(TAG, "GoogleAccountManager: Found " + accounts.length + " real accounts");
                        return result;
                    }
                }
                
                // No real accounts found.
                // When real GMS is installed, do NOT return mock accounts —
                // the real sign-in flow should handle this.
                // When no real GMS, also do NOT return mock accounts —
                // they never work for actual sign-in and only confuse apps
                // into thinking an account exists when it doesn't.
                Slog.d(TAG, "GoogleAccountManager: No real accounts found, returning empty (no mock accounts)");
                return new Account[0];
                
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: GetAccounts error", e);
                // Always return empty instead of mock accounts.
                // Mock accounts can never be used for real sign-in.
                return new Account[0];
            }
        }
    }

    @ProxyMethod("getAccountsByType")
    public static class GetAccountsByType extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling getAccountsByType call");
                
                if (args != null && args.length > 0) {
                    String accountType = (String) args[0];
                    Slog.d(TAG, "GoogleAccountManager: Requesting accounts of type: " + accountType);
                    
                    // Try real accounts first
                    Object result = method.invoke(who, args);
                    if (result != null && result instanceof Account[]) {
                        Account[] accounts = (Account[]) result;
                        if (accounts.length > 0) {
                            Slog.d(TAG, "GoogleAccountManager: Found " + accounts.length + " real accounts of type " + accountType);
                            return result;
                        }
                    }
                    
                    // No real accounts found. Never return mock accounts —
                    // they cannot be used for real sign-in and only confuse the app
                    // into attempting a sign-in that will always fail server-side.
                    Slog.d(TAG, "GoogleAccountManager: No real accounts of type " + accountType + " found, returning empty");
                    return new Account[0];
                }
                
                return method.invoke(who, args);
                
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: GetAccountsByType error", e);
                // Always return empty instead of mock accounts
                return new Account[0];
            }
        }
    }

    @ProxyMethod("getPassword")
    public static class GetPassword extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling getPassword call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: GetPassword error", e);
                // Never return mock passwords — they don't work for real sign-in
                return null;
            }
        }
    }

    @ProxyMethod("getUserData")
    public static class GetUserData extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling getUserData call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: GetUserData error", e);
                // Never return mock user data — it doesn't work for real sign-in
                return null;
            }
        }
    }

    @ProxyMethod("addAccount")
    public static class AddAccount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling addAccount call");
                Object result = method.invoke(who, args);
                Slog.d(TAG, "GoogleAccountManager: addAccount returned: " + result);
                return result;
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: AddAccount error", e);
                // When real GMS is installed, don't return mock addAccount result.
                // Let the real GMS handle account addition flow.
                // When no real GMS, also don't return mock — it can never work.
                // Return null to signal that account addition is not available.
                // The app should fall back to proper sign-in flow or show
                // an appropriate error to the user.
                if (isRealGmsInstalled()) {
                    Slog.d(TAG, "GoogleAccountManager: Real GMS installed but addAccount failed, returning null");
                } else {
                    Slog.d(TAG, "GoogleAccountManager: No real GMS, addAccount not available, returning null");
                }
                return null;
            }
        }
    }

    @ProxyMethod("removeAccount")
    public static class RemoveAccount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling removeAccount call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: RemoveAccount error", e);
                return true; 
            }
        }
    }

    @ProxyMethod("hasFeatures")
    public static class HasFeatures extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling hasFeatures call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: HasFeatures error", e);
                return false;  // Don't claim features we don't actually have
            }
        }
    }

    @ProxyMethod("getAuthToken")
    public static class GetAuthToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling getAuthToken call");
                Object result = method.invoke(who, args);
                if (result != null && result instanceof Bundle) {
                    Bundle bundle = (Bundle) result;
                    String token = bundle.getString("authtoken");
                    if (token != null) {
                        Slog.d(TAG, "GoogleAccountManager: Got real auth token (length=" + token.length() + ")");
                    } else {
                        Slog.d(TAG, "GoogleAccountManager: getAuthToken returned bundle with no token");
                    }
                }
                return result;
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: GetAuthToken error", e);
                // Never return mock auth tokens — they will be rejected by Google servers
                // and make sign-in appear to succeed then fail immediately.
                // When real GMS is installed, re-throw so the caller sees the real error
                // and can retry or show proper sign-in UI.
                if (isRealGmsInstalled()) {
                    Slog.d(TAG, "GoogleAccountManager: Real GMS installed, re-throwing getAuthToken error");
                    throw e.getCause() != null ? e.getCause() : e;
                }
                // Without real GMS, return null (no token available)
                return null;
            }
        }
    }

    @ProxyMethod("invalidateAuthToken")
    public static class InvalidateAuthToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling invalidateAuthToken call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: InvalidateAuthToken error, ignoring", e);
                return null;
            }
        }
    }

    @ProxyMethod("peekAuthToken")
    public static class PeekAuthToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling peekAuthToken call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: PeekAuthToken error", e);
                // Never return mock tokens
                return null;
            }
        }
    }

    @ProxyMethod("setPassword")
    public static class SetPassword extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling setPassword call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: SetPassword error, ignoring", e);
                return null;
            }
        }
    }

    @ProxyMethod("setUserData")
    public static class SetUserData extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling setUserData call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: SetUserData error, ignoring", e);
                return null;
            }
        }
    }

    @ProxyMethod("getAuthenticatorTypes")
    public static class GetAuthenticatorTypes extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling getAuthenticatorTypes call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: GetAuthenticatorTypes error", e);
                // Return empty array instead of wrong type.
                // The return type of getAuthenticatorTypes() is
                // android.accounts.AuthenticatorDescription[], not String[].
                // Returning an empty array is safer than returning incorrect data.
                return new android.accounts.AuthenticatorDescription[0];
            }
        }
    }

    @ProxyMethod("isAccountPresent")
    public static class IsAccountPresent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling isAccountPresent call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: IsAccountPresent error", e);
                // Don't claim an account is present when it's not
                return false;
            }
        }
    }

    @ProxyMethod("addOnAccountsUpdatedListener")
    public static class AddOnAccountsUpdatedListener extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling addOnAccountsUpdatedListener call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: addOnAccountsUpdatedListener error", e);
                return null;
            }
        }
    }

    @ProxyMethod("removeOnAccountsUpdatedListener")
    public static class RemoveOnAccountsUpdatedListener extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling removeOnAccountsUpdatedListener call");
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: removeOnAccountsUpdatedListener error", e);
                return null;
            }
        }
    }

    @ProxyMethod("blockingGetAuthToken")
    public static class BlockingGetAuthToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Slog.d(TAG, "GoogleAccountManager: Handling blockingGetAuthToken call");
                Object result = method.invoke(who, args);
                if (result != null) {
                    Slog.d(TAG, "GoogleAccountManager: blockingGetAuthToken returned token (length=" + ((String)result).length() + ")");
                }
                return result;
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: blockingGetAuthToken error", e);
                // Never return mock tokens — they will be rejected by Google servers
                if (isRealGmsInstalled()) {
                    throw e.getCause() != null ? e.getCause() : e;
                }
                return null;
            }
        }
    }
}
