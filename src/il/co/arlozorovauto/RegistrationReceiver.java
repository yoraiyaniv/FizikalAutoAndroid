package il.co.arlozorovauto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class RegistrationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        long scheduledId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, -1);
        if (scheduledId == -1) return;

        Intent serviceIntent = new Intent(context, RegistrationService.class);
        serviceIntent.putExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, scheduledId);
        context.startForegroundService(serviceIntent);
    }
}
