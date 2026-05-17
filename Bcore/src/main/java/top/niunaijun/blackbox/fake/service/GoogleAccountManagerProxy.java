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
                if (isRealGmsInstalled()) {
                    Slog.d(TAG, "GoogleAccountManager: No real accounts but real GMS installed, returning empty (no mock)");
                    return new Account[0];
                }
                
                // Only provide mock accounts as fallback when no real GMS is installed
                // and only for basic compatibility (not for actual sign-in)
                Slog.d(TAG, "GoogleAccountManager: No real accounts and no real GMS, returning mock accounts for basic compatibility");
                return createMockGoogleAccounts();
                
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: GetAccounts error", e);
                if (isRealGmsInstalled()) {
                    // With real GMS, return empty rather than fake accounts
                    return new Account[0];
                }
                return createMockGoogleAccounts();
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
                    
                    // No real accounts. When real GMS is installed, do NOT use mock accounts.
                    if (isRealGmsInstalled()) {
                        Slog.d(TAG, "GoogleAccountManager: Real GMS installed, returning empty accounts (no mock)");
                        return new Account[0];
                    }
                    
                    // Only mock for basic compatibility without real GMS
                    if ("com.google".equals(accountType)) {
                        Slog.d(TAG, "GoogleAccountManager: No real GMS, returning mock Google accounts");
                        return createMockGoogleAccounts();
                    }
                }
                
                return method.invoke(who, args);
                
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: GetAccountsByType error", e);
                if (isRealGmsInstalled()) {
                    return new Account[0];
                }
                return createMockGoogleAccounts();
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
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: AddAccount error", e);
                // When real GMS is installed, don't return mock addAccount result.
                // Let the real GMS handle account addition flow.
                if (isRealGmsInstalled()) {
                    // Return null to signal failure — the app should use proper sign-in flow
                    return null;
                }
                // Fallback only without real GMS
                Bundle result = new Bundle();
                result.putString("authAccount", "mock@gmail.com");
                result.putString("accountType", "com.google");
                return result;
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
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: GetAuthToken error", e);
                // Never return mock auth tokens — they will be rejected by Google servers
                // and make sign-in appear to succeed then fail immediately.
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
                return new String[]{"com.google"};
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

    
    private static Account[] createMockGoogleAccounts() {
        try {
            List<Account> accounts = new ArrayList<>();
            
            Account primaryAccount = new Account("mock.user@gmail.com", "com.google");
            accounts.add(primaryAccount);
            
            Slog.d(TAG, "GoogleAccountManager: Created " + accounts.size() + " mock Google accounts (fallback only)");
            return accounts.toArray(new Account[0]);
            
        } catch (Exception e) {
            Slog.e(TAG, "GoogleAccountManager: Failed to create mock accounts", e);
            return new Account[0];
        }
    }
}
