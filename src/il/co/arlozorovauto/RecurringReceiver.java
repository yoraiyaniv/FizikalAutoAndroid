package il.co.arlozorovauto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fired ~daily by the repeating alarm. Kicks off SweepService to look for
 *  upcoming classes matching the user's weekly recurring rules and queue
 *  them for auto-registration. */
public class RecurringReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startForegroundService(new Intent(context, SweepService.class));
    }
}
