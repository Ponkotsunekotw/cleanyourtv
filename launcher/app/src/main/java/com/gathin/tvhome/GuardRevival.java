package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Starts this launcher's process after the firmware has killed it, and surfaces active
 * system notifications to the launcher's top-bar pill when enabled.
 */
public class GuardRevival extends NotificationListenerService {
    private static volatile GuardRevival instance;
    private static volatile Runnable onChange;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private BroadcastReceiver unlocked;

    static void setOnChange(Runnable listener) {
        onChange = listener;
    }

    static StatusBarNotification[] activeNotifications() {
        GuardRevival s = instance;
        if (s == null) return new StatusBarNotification[0];
        try {
            StatusBarNotification[] list = s.getActiveNotifications();
            return list != null ? list : new StatusBarNotification[0];
        } catch (RuntimeException e) {
            return new StatusBarNotification[0];
        }
    }

    static void dismiss(String key) {
        GuardRevival s = instance;
        if (s == null || key == null) return;
        try {
            s.cancelNotification(key);
        } catch (RuntimeException ignored) {
        }
    }

    static void clearAll() {
        GuardRevival s = instance;
        if (s == null) return;
        try {
            s.cancelAllNotifications();
        } catch (RuntimeException ignored) {
        }
    }

    private static void notifyChanged() {
        Runnable r = onChange;
        if (r != null) MAIN.post(r);
    }

    @Override
    public void onListenerConnected() {
        instance = this;
        notifyChanged();
        UserManager users = getSystemService(UserManager.class);
        if (users != null && !users.isUserUnlocked()) {
            if (unlocked != null) return;
            unlocked = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    stopWaiting();
                    GuardKeep.ensureHomeKey(GuardRevival.this);
                }
            };
            registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            if (users.isUserUnlocked()) {
                stopWaiting();
                GuardKeep.ensureHomeKey(this);
            }
            return;
        }
        GuardKeep.ensureHomeKey(this);
    }

    private void stopWaiting() {
        if (unlocked == null) return;
        try {
            unregisterReceiver(unlocked);
        } catch (RuntimeException ignored) {
        }
        unlocked = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        notifyChanged();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        notifyChanged();
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
        notifyChanged();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        stopWaiting();
        super.onDestroy();
    }
}
