package il.co.arlozorovauto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** Short-lived foreground service that runs the recurring-rules sweep off the
 *  main thread (network), then stops itself. Started ~daily by
 *  RecurringReceiver. */
public class SweepService extends Service {

    private static final String CHANNEL_ID = "fizikal_auto_register";

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        FizikalApi.initDeviceId(this);
        startForeground(2000, buildNotification("בודק שיעורים קבועים..."));
        new Thread(new Worker(this)).start();
        return START_NOT_STICKY;
    }

    private static class Worker implements Runnable {
        private final SweepService service;
        Worker(SweepService service) { this.service = service; }
        @Override public void run() {
            try {
                RecurringSweep.sweepFromNetwork(service);
            } catch (Exception ignored) {
            } finally {
                service.stopForeground(true);
                service.stopSelf();
            }
        }
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "הרשמה אוטומטית", NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ארלוזרוב אוטומטי")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();
    }
}
