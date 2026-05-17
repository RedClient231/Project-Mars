package top.niunaijun.blackbox.fake.service;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedList;
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
        // Note: inject() is intentionally empty. This ClassInvocationStub is NOT
        // currently injected anywhere. The actual AccountManager interception
        // happens through IAccountManagerProxy (BinderInvocationStub) which IS
        // injected via replaceSystemService. The code here is kept correct in
        // case the proxy is ever activated.
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // ======================== ProxyEventTracker ========================

    /**
     * Tracks proxy method invocations for diagnostic purposes.
     * Keeps a bounded list of recent events to help debug account passthrough issues.
     */
    public static class ProxyEventTracker {
        private static final int MAX_EVENTS = 50;
        private static final LinkedList<ProxyEvent> events = new LinkedList<>();

        public static class ProxyEvent {
            public final String methodName;
            public final String callingPackage;
            public final String accountType;
            public final int resultCount;
            public final String exceptionClass;
            public final String exceptionMessage;
            public final boolean hostPassthroughUsed;
            public final long timestamp;

            public ProxyEvent(String methodName, String callingPackage, String accountType,
                              int resultCount, String exceptionClass, String exceptionMessage,
                              boolean hostPassthroughUsed) {
                this.methodName = methodName;
                this.callingPackage = callingPackage;
                this.accountType = accountType;
                this.resultCount = resultCount;
                this.exceptionClass = exceptionClass;
                this.exceptionMessage = exceptionMessage;
                this.hostPassthroughUsed = hostPassthroughUsed;
                this.timestamp = System.currentTimeMillis();
            }
        }

        public static synchronized void logEvent(String methodName, String callingPackage,
                                                  String accountType, int resultCount,
                                                  String exceptionClass, String exceptionMessage,
                                                  boolean hostPassthroughUsed) {
            events.addFirst(new ProxyEvent(methodName, callingPackage, accountType,
                    resultCount, exceptionClass, exceptionMessage, hostPassthroughUsed));
            while (events.size() > MAX_EVENTS) {
                events.removeLast();
            }
        }

        public static synchronized String getEventsLog() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== GoogleAccountManagerProxy Events (last ").append(events.size()).append(") ===\n");
            for (ProxyEvent event : events) {
                sb.append("[").append(event.timestamp).append("] ");
                sb.append(event.methodName);
                sb.append(" pkg=").append(event.callingPackage != null ? event.callingPackage : "?");
                sb.append(" type=").append(event.accountType != null ? event.accountType : "?");
                sb.append(" count=").append(event.resultCount);
                sb.append(" passthrough=").append(event.hostPassthroughUsed);
                if (event.exceptionClass != null) {
                    sb.append(" ERROR: ").append(event.exceptionClass);
                    if (event.exceptionMessage != null) {
                        sb.append(" - ").append(event.exceptionMessage);
                    }
                }
                sb.append("\n");
            }
            sb.append("=== End of Proxy Events ===");
            return sb.toString();
        }
    }

    /**
     * Get the proxy events diagnostic log.
     */
    public static String getProxyEventsDiagnostic() {
        return ProxyEventTracker.getEventsLog();
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
                        ProxyEventTracker.logEvent("getAccounts", BActivityThread.getAppProcessName(),
                                null, accounts.length, null, null, false);
                        return result;
                    }
                }

                // No real accounts from method.invoke. Try host passthrough for Google accounts.
                if (isRealGmsInstalled()) {
                    try {
                        Context hostContext = BlackBoxCore.getContext();
                        if (hostContext != null) {
                            AccountManager hostAm = AccountManager.get(hostContext);
                            Account[] hostAccounts = hostAm.getAccounts();
                            if (hostAccounts != null && hostAccounts.length > 0) {
                                Slog.d(TAG, "GoogleAccountManager: Forwarding " + hostAccounts.length + " host accounts");
                                ProxyEventTracker.logEvent("getAccounts", BActivityThread.getAppProcessName(),
                                        null, hostAccounts.length, null, null, true);
                                return hostAccounts;
                            }
                        }
                    } catch (SecurityException se) {
                        Slog.w(TAG, "GoogleAccountManager: getAccounts host passthrough SecurityException: " + se.getMessage());
                        ProxyEventTracker.logEvent("getAccounts", BActivityThread.getAppProcessName(),
                                null, 0, "SecurityException", se.getMessage(), false);
                    } catch (Exception e) {
                        Slog.w(TAG, "GoogleAccountManager: getAccounts host passthrough error", e);
                        ProxyEventTracker.logEvent("getAccounts", BActivityThread.getAppProcessName(),
                                null, 0, e.getClass().getSimpleName(), e.getMessage(), false);
                    }
                    // With real GMS, return empty rather than fake accounts
                    return new Account[0];
                }

                // Only provide mock accounts as fallback when no real GMS is installed
                Slog.d(TAG, "GoogleAccountManager: No real accounts and no real GMS, returning mock accounts for basic compatibility");
                ProxyEventTracker.logEvent("getAccounts", BActivityThread.getAppProcessName(),
                        null, 1, null, null, false);
                return createMockGoogleAccounts();

            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: GetAccounts error", e);
                if (isRealGmsInstalled()) {
                    ProxyEventTracker.logEvent("getAccounts", BActivityThread.getAppProcessName(),
                            null, 0, e.getClass().getSimpleName(), e.getMessage(), false);
                    return new Account[0];
                }
                ProxyEventTracker.logEvent("getAccounts", BActivityThread.getAppProcessName(),
                        null, 1, e.getClass().getSimpleName(), e.getMessage(), false);
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
                            ProxyEventTracker.logEvent("getAccountsByType", BActivityThread.getAppProcessName(),
                                    accountType, accounts.length, null, null, false);
                            return result;
                        }
                    }

                    // No real accounts from method.invoke. Try host passthrough for Google accounts.
                    if (isRealGmsInstalled() && ("com.google".equals(accountType) || accountType == null)) {
                        try {
                            Context hostContext = BlackBoxCore.getContext();
                            if (hostContext != null) {
                                AccountManager hostAm = AccountManager.get(hostContext);
                                Account[] hostAccounts = hostAm.getAccountsByType(accountType);
                                if (hostAccounts != null && hostAccounts.length > 0) {
                                    Slog.d(TAG, "GoogleAccountManager: Forwarding " + hostAccounts.length + " host Google accounts");
                                    ProxyEventTracker.logEvent("getAccountsByType", BActivityThread.getAppProcessName(),
                                            accountType, hostAccounts.length, null, null, true);
                                    return hostAccounts;
                                }
                            }
                        } catch (SecurityException se) {
                            Slog.w(TAG, "GoogleAccountManager: getAccountsByType host passthrough SecurityException: " + se.getMessage());
                            ProxyEventTracker.logEvent("getAccountsByType", BActivityThread.getAppProcessName(),
                                    accountType, 0, "SecurityException", se.getMessage(), false);
                        } catch (Exception e) {
                            Slog.w(TAG, "GoogleAccountManager: getAccountsByType host passthrough error", e);
                            ProxyEventTracker.logEvent("getAccountsByType", BActivityThread.getAppProcessName(),
                                    accountType, 0, e.getClass().getSimpleName(), e.getMessage(), false);
                        }
                        // With real GMS, return empty rather than fake accounts
                        return new Account[0];
                    }

                    // Only mock for basic compatibility without real GMS
                    if ("com.google".equals(accountType)) {
                        Slog.d(TAG, "GoogleAccountManager: No real GMS, returning mock Google accounts");
                        ProxyEventTracker.logEvent("getAccountsByType", BActivityThread.getAppProcessName(),
                                accountType, 1, null, null, false);
                        return createMockGoogleAccounts();
                    }
                }

                return method.invoke(who, args);

            } catch (Exception e) {
                Slog.w(TAG, "GoogleAccountManager: GetAccountsByType error", e);
                if (isRealGmsInstalled()) {
                    ProxyEventTracker.logEvent("getAccountsByType", BActivityThread.getAppProcessName(),
                            args != null && args.length > 0 ? (String) args[0] : null,
                            0, e.getClass().getSimpleName(), e.getMessage(), false);
                    return new Account[0];
                }
                ProxyEventTracker.logEvent("getAccountsByType", BActivityThread.getAppProcessName(),
                        args != null && args.length > 0 ? (String) args[0] : null,
                        1, e.getClass().getSimpleName(), e.getMessage(), false);
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
                // Check if the error indicates "account already exists"
                String message = e.getMessage();
                if (message != null && (message.contains("already exists") || message.contains("already been added"))) {
                    // Account already exists on the host — this is actually a success case
                    // Try to query existing accounts and return a success Bundle
                    try {
                        Context hostContext = BlackBoxCore.getContext();
                        if (hostContext != null) {
                            AccountManager hostAm = AccountManager.get(hostContext);
                            Account[] googleAccounts = hostAm.getAccountsByType("com.google");
                            if (googleAccounts != null && googleAccounts.length > 0) {
                                Slog.d(TAG, "GoogleAccountManager: addAccount 'already exists' — returning existing account as success");
                                ProxyEventTracker.logEvent("addAccount", BActivityThread.getAppProcessName(),
                                        "com.google", googleAccounts.length, null, null, true);
                                Bundle result = new Bundle();
                                result.putString(AccountManager.KEY_ACCOUNT_NAME, googleAccounts[0].name);
                                result.putString(AccountManager.KEY_ACCOUNT_TYPE, googleAccounts[0].type);
                                return result;
                            }
                        }
                    } catch (Exception inner) {
                        Slog.w(TAG, "GoogleAccountManager: addAccount fallback query error", inner);
                    }
                }
                // When real GMS is installed, don't return mock addAccount result.
                if (isRealGmsInstalled()) {
                    ProxyEventTracker.logEvent("addAccount", BActivityThread.getAppProcessName(),
                            "com.google", 0, e.getClass().getSimpleName(), e.getMessage(), false);
                    return null;
                }
                // Fallback only without real GMS — DO NOT hardcode real emails
                ProxyEventTracker.logEvent("addAccount", BActivityThread.getAppProcessName(),
                        "com.google", 0, e.getClass().getSimpleName(), e.getMessage(), false);
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
                // Must return AuthenticatorDescription[] not String[].
                // Return empty array rather than wrong type.
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
