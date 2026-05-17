package top.niunaijun.blackbox.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import top.niunaijun.blackbox.utils.Slog;

/**
 * In-memory circular buffer logger for Google package runtime events.
 * Captures lifecycle events (app create, activity launch, service bind, etc.)
 * for Google packages only, storing the last 200 events for diagnostic export.
 *
 * This logger runs inside the virtual app process, so it captures what actually
 * happens when a Google app is launched inside BlackBox.
 */
public class GoogleRuntimeEventLogger {
    private static final String TAG = "GoogleEventLogger";

    /** Maximum number of events to keep in the circular buffer */
    private static final int MAX_EVENTS = 200;

    /** Google packages we track */
    private static final String[] TRACKED_PACKAGES = {
            GmsCore.GMS_PKG,
            GmsCore.GSF_PKG,
            GmsCore.VENDING_PKG,
            GmsCore.PLAY_GAMES_PKG
    };

    private static final LinkedList<GoogleRuntimeEvent> events = new LinkedList<>();
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    /**
     * Check if a package should be tracked.
     */
    public static boolean isTrackedPackage(String packageName) {
        if (packageName == null) return false;
        for (String tracked : TRACKED_PACKAGES) {
            if (tracked.equals(packageName)) return true;
        }
        return false;
    }

    /**
     * Log a runtime event for a Google package.
     */
    public static synchronized void logEvent(String packageName, String eventType, String processName, int userId, String details) {
        if (!isTrackedPackage(packageName)) return;

        GoogleRuntimeEvent event = new GoogleRuntimeEvent(
                System.currentTimeMillis(),
                packageName,
                eventType,
                processName,
                userId,
                details != null ? details : ""
        );

        events.add(event);
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }

        Slog.d(TAG, eventType + " | " + packageName + " | process=" + processName + " | user=" + userId + " | " + event.details);
    }

    /**
     * Get all logged events as a formatted string for diagnostic export.
     */
    public static synchronized String getEventsLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Google Runtime Events (last ").append(events.size()).append(" of max ").append(MAX_EVENTS).append(") ===\n");

        if (events.isEmpty()) {
            sb.append("(No Google package runtime events recorded yet. Launch a Google app first.)\n");
        } else {
            sb.append("timestamp           | package                              | event                   | process                   | userId | details\n");
            sb.append("--------------------+--------------------------------------+-------------------------+---------------------------+--------+--------\n");
            for (GoogleRuntimeEvent event : events) {
                String timestamp = dateFormat.format(new Date(event.timestamp));
                sb.append(String.format(Locale.US, "%-20s| %-37s| %-24s| %-26s| %-6d| %s\n",
                        timestamp,
                        truncate(event.packageName, 37),
                        truncate(event.eventType, 24),
                        truncate(event.processName != null ? event.processName : "", 26),
                        event.userId,
                        truncate(event.details, 80)
                ));
            }
        }

        sb.append("\n=== End of Runtime Events ===");
        return sb.toString();
    }

    /**
     * Get a copy of all events (for programmatic access).
     */
    public static synchronized List<GoogleRuntimeEvent> getEvents() {
        return new ArrayList<>(events);
    }

    /**
     * Clear all logged events.
     */
    public static synchronized void clearEvents() {
        events.clear();
        Slog.d(TAG, "Event log cleared");
    }

    /**
     * Get the count of logged events.
     */
    public static synchronized int getEventCount() {
        return events.size();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen - 2) + ".." : s;
    }

    /**
     * Represents a single runtime event for a Google package.
     */
    public static class GoogleRuntimeEvent {
        public final long timestamp;
        public final String packageName;
        public final String eventType;
        public final String processName;
        public final int userId;
        public final String details;

        public GoogleRuntimeEvent(long timestamp, String packageName, String eventType,
                                  String processName, int userId, String details) {
            this.timestamp = timestamp;
            this.packageName = packageName;
            this.eventType = eventType;
            this.processName = processName;
            this.userId = userId;
            this.details = details;
        }
    }
}
