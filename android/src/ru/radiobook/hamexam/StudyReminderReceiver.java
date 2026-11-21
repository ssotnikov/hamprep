package ru.radiobook.hamexam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class StudyReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        StudyReminders reminders = new StudyReminders(context);
        String action = intent.getAction();
        if (StudyReminders.ALARM.equals(action)) {
            reminders.deliver();
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            reminders.refresh();
        }
    }
}
