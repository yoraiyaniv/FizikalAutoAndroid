package il.co.arlozorovauto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/** AlarmManager alarms don't survive a reboot, so re-arm one for every
 *  still-pending scheduled registration when the device comes back up. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        DbHelper db = new DbHelper(context);
        List<ScheduledItem> pending = db.listPending();
        for (ScheduledItem item : pending) {
            AlarmScheduler.schedule(context, item);
        }
        // Re-arm the daily recurring-rules sweep too.
        AlarmScheduler.scheduleDailySweep(context);
    }
}
