package il.co.arlozorovauto;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the "בטל ניסיון הרשמה" action button on the ongoing
 *  "attempting registration..." notification, letting the user stop an
 *  automated attempt without opening the app. Marks the scheduled item
 *  cancelled in the DB; RegistrationService notices on its next pass
 *  through the polling loop and stops itself. */
public class CancelAttemptReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        long scheduledId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, -1);
        if (scheduledId == -1) return;

        DbHelper db = new DbHelper(context);
        db.cancelScheduled(scheduledId); // no-op if it already finished (no longer "pending")
        AlarmScheduler.cancel(context, scheduledId);

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(1000 + (int) scheduledId);
    }
}
