package il.co.arlozorovauto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

/** Foreground service that runs the actual unregister call, triggered by the
 *  "בטל הרשמה" action button on a registration-success notification. Lets
 *  the user cancel straight from the notification - even minutes later,
 *  without opening the app - the moment they notice they can't make it. */
public class UnregisterService extends Service {

    static final String EXTRA_REGISTRATION_ID = "registration_id";
    private static final String CHANNEL_ID = "fizikal_auto_register";

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long scheduledId = intent != null ? intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, -1) : -1;
        String registrationId = intent != null ? intent.getStringExtra(EXTRA_REGISTRATION_ID) : null;

        startForeground(4000 + (int) scheduledId, buildWorkingNotification());

        if (scheduledId == -1 || registrationId == null || registrationId.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        new Thread(new Worker(this, scheduledId, registrationId)).start();
        return START_NOT_STICKY;
    }

    private static class Worker implements Runnable {
        private final UnregisterService service;
        private final long scheduledId;
        private final String registrationId;

        Worker(UnregisterService service, long scheduledId, String registrationId) {
            this.service = service;
            this.scheduledId = scheduledId;
            this.registrationId = registrationId;
        }

        @Override public void run() {
            service.doUnregister(scheduledId, registrationId);
            service.stopForeground(true);
            service.stopSelf();
        }
    }

    private void doUnregister(long scheduledId, String registrationId) {
        DbHelper db = new DbHelper(this);
        ScheduledItem item = db.getScheduledById(scheduledId);
        SessionState session = SessionState.get(this);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(3000 + (int) scheduledId); // dismiss the notification that was tapped

        boolean ok;
        String message;
        try {
            if (!session.ensureFreshToken(this)) {
                ok = false;
                message = "החיבור פג - פתח את האפליקציה והתחבר מחדש כדי לבטל";
            } else {
                JSONObject r = FizikalApi.removeRegistration(registrationId, session.accessToken);
                ok = r.optBoolean("success", false);
                message = ok ? "ההרשמה בוטלה" : FizikalApi.serverMessage(r, "לא ניתן לבטל");
            }
        } catch (Exception e) {
            ok = false;
            message = "שגיאה בביטול ההרשמה";
        }

        if (ok) {
            db.updateRegistrationResult(scheduledId, "cancelled", "ההרשמה בוטלה מההתראה", null);
        }

        String label = item != null
                ? Hebrew.classLabel(item.classDate, item.startTime, item.instructorName, item.description)
                : "השיעור";
        notifyResult(ok ? ("ההרשמה בוטלה: " + label) : ("ביטול ההרשמה נכשל ל" + label + " - " + message));
    }

    private void notifyResult(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "הרשמה אוטומטית", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ארלוזרוב אוטומטי")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();
        nm.notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), n);
    }

    private Notification buildWorkingNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "הרשמה אוטומטית", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ארלוזרוב אוטומטי")
                .setContentText("מבטל הרשמה...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();
    }
}
