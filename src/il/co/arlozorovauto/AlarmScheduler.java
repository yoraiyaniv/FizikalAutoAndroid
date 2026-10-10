package il.co.arlozorovauto;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Schedules the exact wake-up alarm that fires shortly before a class's
 *  registrationStart, giving RegistrationService a head start to warm up
 *  before the exact moment registration opens. */
public class AlarmScheduler {

    // Wake this many seconds before registrationStart, so the service is
    // already polling (and has already refreshed its token) by the time the
    // window actually opens.
    public static final long LOOKAHEAD_SECONDS = 20;

    public static final String EXTRA_SCHEDULED_ID = "scheduled_id";
    private static final int DAILY_SWEEP_REQUEST = 990001;

    /** Arms a repeating ~daily alarm that runs the recurring-rules sweep in
     *  the background, so weekly classes keep getting booked even if the app
     *  is never opened. Inexact + no exact-alarm permission needed - the
     *  sweep only materializes items; each one then gets its own exact alarm. */
    public static void scheduleDailySweep(Context ctx) {
        Intent intent = new Intent(ctx, RecurringReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, DAILY_SWEEP_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        long first = System.currentTimeMillis() + 5 * 60 * 1000; // ~5 min from now, then daily
        try {
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, first, AlarmManager.INTERVAL_DAY, pi);
        } catch (Exception ignored) {}
    }

    /** Schedules the wake-up alarm for this item. Returns true if an EXACT
     *  alarm was set, false if we had to fall back to an inexact one (or the
     *  time couldn't be parsed). Never throws - a denied "Alarms & reminders"
     *  permission must not crash the app when the user taps Auto-register. */
    public static boolean schedule(Context ctx, ScheduledItem item) {
        long start = registrationStartMillis(item.registrationStart);
        if (start == 0) return false; // unparseable registrationStart - skip alarm
        long triggerAtMillis = start - LOOKAHEAD_SECONDS * 1000;
        long now = System.currentTimeMillis();
        if (triggerAtMillis < now) {
            triggerAtMillis = now + 1000; // already due - fire almost immediately
        }

        Intent intent = new Intent(ctx, RegistrationReceiver.class);
        intent.putExtra(EXTRA_SCHEDULED_ID, item.id);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, (int) item.id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            return true;
        } catch (SecurityException noExactPermission) {
            // "Alarms & reminders" not granted - fall back to an inexact alarm
            // so registration still fires (just possibly a little late).
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } catch (Exception ignored) {
                // last resort - a plain set() should never throw
                try { am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi); } catch (Exception ignored2) {}
            }
            return false;
        }
    }

    /** True if the OS will let us schedule exact alarms right now. */
    public static boolean canScheduleExact(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    public static void cancel(Context ctx, long scheduledId) {
        Intent intent = new Intent(ctx, RegistrationReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, (int) scheduledId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi);
    }

    /** registrationStart from the API is a naive local datetime like
     *  "2026-08-20T07:55:00" in Israel local time, regardless of the
     *  device's configured timezone. */
    public static long registrationStartMillis(String isoLocalNoZone) {
        try {
            // Trim any fractional seconds/offset the server might include.
            String s = isoLocalNoZone.trim();
            if (s.length() >= 19) s = s.substring(0, 19);
            LocalDateTime ldt = LocalDateTime.parse(s);
            ZonedDateTime zdt = ldt.atZone(ZoneId.of("Asia/Jerusalem"));
            return zdt.toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }
}
