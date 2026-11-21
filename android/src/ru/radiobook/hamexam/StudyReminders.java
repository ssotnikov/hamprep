package ru.radiobook.hamexam;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

final class StudyReminders {
    static final String CHANNEL = "study_reminders";
    static final String OPEN_STUDY = "ru.radiobook.hamexam.OPEN_STUDY";
    static final String ALARM = "ru.radiobook.hamexam.STUDY_REMINDER";
    private static final int NOTIFICATION_ID = 17;
    private static final int ALARM_ID = 18;
    private static final int FLAGS = PendingIntent.FLAG_IMMUTABLE;
    private static final int DEFAULT_MINUTES = 19 * 60;

    private final Context context;
    private final SharedPreferences settings;
    private final AlarmManager alarms;
    private final NotificationManager notifications;

    StudyReminders(Context context) {
        this.context = context;
        settings = context.getSharedPreferences("study_reminders", Context.MODE_PRIVATE);
        alarms = context.getSystemService(AlarmManager.class);
        notifications = context.getSystemService(NotificationManager.class);
    }

    boolean enabled() {
        return settings.getBoolean("enabled", false);
    }

    int days() {
        int days = settings.getInt("days", 2);
        return validDays(days) ? days : 2;
    }

    int minutes() {
        int minutes = settings.getInt("minutes", DEFAULT_MINUTES);
        return minutes >= 0 && minutes < 24 * 60 ? minutes : DEFAULT_MINUTES;
    }

    boolean hasPermission() {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
    }

    boolean requestedPermission() {
        return settings.getBoolean("permission_requested", false);
    }

    void markPermissionRequested() {
        settings.edit().putBoolean("permission_requested", true).apply();
    }

    boolean canNotify() {
        NotificationChannel channel = notifications.getNotificationChannel(CHANNEL);
        return hasPermission() && notifications.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    void ensureChannel() {
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Напоминания об обучении", NotificationManager.IMPORTANCE_LOW));
    }

    void enable() {
        ensureChannel();
        if (!canNotify()) return;
        settings.edit().putBoolean("enabled", true)
                .putLong("anchor", System.currentTimeMillis()).apply();
        refresh();
    }

    void disable() {
        settings.edit().putBoolean("enabled", false).apply();
        cancel();
    }

    void setSchedule(int days, int minutes) {
        if (!validDays(days) || minutes < 0 || minutes >= 24 * 60) return;
        settings.edit().putInt("days", days).putInt("minutes", minutes).apply();
        refresh();
    }

    void studied() {
        if (!enabled()) return;
        settings.edit().putLong("anchor", System.currentTimeMillis()).apply();
        notifications.cancel(NOTIFICATION_ID);
        refresh();
    }

    void refresh() {
        if (!enabled()) {
            cancel();
            return;
        }
        if (!canNotify()) {
            disable();
            return;
        }

        long now = System.currentTimeMillis();
        alarms.setWindow(AlarmManager.RTC_WAKEUP, Math.max(now, due(now)),
                TimeUnit.HOURS.toMillis(1), alarm(PendingIntent.FLAG_UPDATE_CURRENT));
    }

    void deliver() {
        if (!enabled() || !canNotify()) {
            refresh();
            return;
        }

        long now = System.currentTimeMillis();
        if (now < due(now)) {
            refresh();
            return;
        }

        ensureChannel();
        Intent continueStudy = new Intent(context, MainActivity.class)
                .setAction(OPEN_STUDY).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, NOTIFICATION_ID, continueStudy, FLAGS);
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_study_reminder)
                .setContentTitle("Продолжим подготовку?")
                .setContentText("Откройте материалы или ответьте на несколько вопросов.")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build();
        try {
            notifications.notify(NOTIFICATION_ID, notification);
        } catch (SecurityException denied) {
            disable();
            return;
        }
        settings.edit().putLong("anchor", now).apply();
        refresh();
    }

    private void cancel() {
        PendingIntent pending = alarm(PendingIntent.FLAG_NO_CREATE);
        if (pending != null) {
            alarms.cancel(pending);
            pending.cancel();
        }
        notifications.cancel(NOTIFICATION_ID);
    }

    private PendingIntent alarm(int flag) {
        Intent intent = new Intent(context, StudyReminderReceiver.class).setAction(ALARM);
        return PendingIntent.getBroadcast(context, ALARM_ID, intent, flag | FLAGS);
    }

    private long due(long now) {
        ZoneId zone = ZoneId.systemDefault();
        long anchor = settings.getLong("anchor", now);
        return Instant.ofEpochMilli(Math.min(anchor, now)).atZone(zone)
                .toLocalDate().plusDays(days())
                .atTime(LocalTime.ofSecondOfDay(minutes() * 60L)).atZone(zone)
                .toInstant().toEpochMilli();
    }

    private static boolean validDays(int days) {
        return days == 1 || days == 2 || days == 3 || days == 7;
    }
}
