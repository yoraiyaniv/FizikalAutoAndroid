package il.co.arlozorovauto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the "בטל הרשמה" action button on a registration-success
 *  notification - starts UnregisterService to actually cancel the booking,
 *  so the user can act on the notification even minutes after it landed. */
public class CancelRegistrationReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        long scheduledId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, -1);
        String registrationId = intent.getStringExtra(UnregisterService.EXTRA_REGISTRATION_ID);
        if (scheduledId == -1 || registrationId == null) return;

        Intent svc = new Intent(context, UnregisterService.class);
        svc.putExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, scheduledId);
        svc.putExtra(UnregisterService.EXTRA_REGISTRATION_ID, registrationId);
        context.startForegroundService(svc);
    }
}
