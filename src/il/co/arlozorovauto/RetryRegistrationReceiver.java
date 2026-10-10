package il.co.arlozorovauto;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the "נסה שוב" action button on a failed-registration
 *  notification - resets the scheduled item back to pending and starts
 *  RegistrationService immediately (no need to wait for another alarm),
 *  so the user can retry a failed attempt with one tap from the notification. */
public class RetryRegistrationReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        long scheduledId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, -1);
        if (scheduledId == -1) return;

        DbHelper db = new DbHelper(context);
        db.retryScheduled(scheduledId); // no-op if it's not actually "failed" any more

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(3000 + (int) scheduledId);

        Intent svc = new Intent(context, RegistrationService.class);
        svc.putExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, scheduledId);
        context.startForegroundService(svc);
    }
}
