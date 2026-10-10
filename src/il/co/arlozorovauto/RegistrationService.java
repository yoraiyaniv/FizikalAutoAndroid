package il.co.arlozorovauto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

/** Foreground service that does the actual timed registration attempt.
 *  Started by RegistrationReceiver ~20s before a class's registrationStart;
 *  polls until the window opens (or the class was already registered
 *  elsewhere), fires the booking call, falls back to the waitlist if the
 *  class filled first, and gives up after a bounded number of attempts. */
public class RegistrationService extends Service {

    private static final String CHANNEL_ID = "fizikal_auto_register";
    // Outcome notifications (registered/failed/waitlisted) need a HIGH
    // importance channel - a LOW one (like the silent "attempting..."
    // channel above) won't reliably trigger the full-screen alert, since
    // Android only honors setFullScreenIntent() on notifications important
    // enough to show heads-up in the first place.
    private static final String OUTCOME_CHANNEL_ID = "fizikal_auto_outcome";
    private static final String ALERT_CHANNEL_ID = "fizikal_auto_alert";
    private static final int GIVE_UP_SECONDS_PAST_OPEN = 60;
    private static final int MAX_ATTEMPTS = 30;
    // Silent token refresh works reliably (see FizikalApi.refreshToken), so
    // re-login should almost never be needed - it's only a last-resort safety
    // net for if the stored refresh token itself ever becomes invalid (a real
    // server-side logout). If that happens, give the user this long to notice
    // the alert and re-verify with a fresh OTP before giving up on this class.
    private static final long RELOGIN_GRACE_MILLIS = 10 * 60 * 1000;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        FizikalApi.initDeviceId(this);
        long scheduledId = intent != null ? intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, -1) : -1;
        startForeground(1000 + (int) scheduledId, buildOngoingNotification("מבצע הרשמה לשיעור...", scheduledId));

        if (scheduledId == -1) {
            stopSelf();
            return START_NOT_STICKY;
        }

        new Thread(new Worker(this, scheduledId)).start();

        return START_NOT_STICKY;
    }

    private static class Worker implements Runnable {
        private final RegistrationService service;
        private final long scheduledId;

        Worker(RegistrationService service, long scheduledId) {
            this.service = service;
            this.scheduledId = scheduledId;
        }

        @Override public void run() {
            service.runRegistrationFlow(scheduledId);
            service.stopForeground(true);
            service.stopSelf();
        }
    }

    private void runRegistrationFlow(long scheduledId) {
        DbHelper db = new DbHelper(this);
        ScheduledItem item = db.getScheduledById(scheduledId);
        if (item == null || !"pending".equals(item.status)) return;

        SessionState session = SessionState.get(this);

        // Proactively refresh the access token now, ~20s before registration
        // opens, so we go into the split-second race with a guaranteed-fresh
        // token instead of burning a 401 round-trip at the critical moment.
        session.tryRefreshNow(this);

        Long openedAtMillis = null;
        int attempts = 0;
        // Tracks whether we ever got a real, parseable response from the
        // server at all during this attempt - lets the final failure
        // message honestly say "never reached the server" (offline/network
        // issue) instead of lumping it in with "the server rejected us".
        boolean everReachedServer = false;
        long hardDeadline = System.currentTimeMillis() + (AlarmScheduler.LOOKAHEAD_SECONDS + 300) * 1000;

        while (System.currentTimeMillis() < hardDeadline) {
            // The user can tap "בטל ניסיון הרשמה" on the ongoing notification
            // to stop the automated attempt; that just flips this row out of
            // "pending" in the DB, so check for it each pass through the loop.
            ScheduledItem fresh = db.getScheduledById(scheduledId);
            if (fresh == null || !"pending".equals(fresh.status)) return;

            JSONObject rawDetails;
            try {
                rawDetails = FizikalApi.getClassDetailsRaw(item.classId, item.classDate, session.accessToken);
            } catch (Exception e) {
                sleepQuiet(1000);
                continue;
            }

            if (FizikalApi.isInvalidToken(rawDetails)) {
                if (!recoverFromInvalidToken(db, scheduledId, item, session)) return;
                hardDeadline = System.currentTimeMillis() + (AlarmScheduler.LOOKAHEAD_SECONDS + 300) * 1000;
                continue;
            }
            if (!rawDetails.optBoolean("success", false)) {
                sleepQuiet(1000); // transient server error - just retry
                continue;
            }
            everReachedServer = true;

            try {
                JSONObject details = rawDetails.getJSONObject("data");
                JSONObject cls = details.getJSONObject("class");
                String action = cls.getJSONObject("action").getString("name");
                db.updateOccupancy(scheduledId, cls.optInt("totalParticipants"), cls.optInt("maxParticipants"));

                if (FizikalApi.ACTION_ALREADY_REGISTERED.equals(action)) {
                    String alreadyRegId = String.valueOf(cls.optInt("registrationId"));
                    db.updateRegistrationResult(scheduledId, "registered", "כבר רשום", alreadyRegId);
                    updateNotification("נרשמת לשיעור: " + classSummary(item), scheduledId, alreadyRegId,
                            "נרשמת בהצלחה!");
                    return;
                }

                if (FizikalApi.ACTION_NOT_YET_OPEN.equals(action)) {
                    sleepQuiet(1000);
                    continue;
                }

                if (openedAtMillis == null) openedAtMillis = System.currentTimeMillis();

                if (FizikalApi.ACTION_WAITLIST.equals(action)) {
                    JSONObject wl = FizikalApi.joinWaitlist(item.classId, item.classDate, session.accessToken);
                    if (FizikalApi.isInvalidToken(wl)) {
                        if (!recoverFromInvalidToken(db, scheduledId, item, session)) return;
                        continue; // retry from the top with the fresh token
                    }
                    if (wl.optBoolean("success")) {
                        String wlRegId = null;
                        JSONObject wlData = wl.optJSONObject("data");
                        JSONObject wlClass = wlData != null ? wlData.optJSONObject("class") : null;
                        if (wlClass != null && wlClass.has("registrationId")) {
                            wlRegId = String.valueOf(wlClass.optInt("registrationId"));
                        }
                        db.updateRegistrationResult(scheduledId, "waitlisted", "השיעור היה מלא - נרשמת לרשימת המתנה", wlRegId);
                        updateNotification("נוספת לרשימת המתנה: " + classSummary(item), scheduledId, wlRegId,
                                "נוספת לרשימת המתנה");
                    } else {
                        String wlMsg = FizikalApi.serverMessage(wl, "השרת לא ציין סיבה");
                        db.updateRegistrationResult(scheduledId, "failed",
                                "ההרשמה לרשימת המתנה נכשלה: " + wlMsg, null);
                        updateFailureNotification("נכשלה הרשמה לרשימת המתנה ל" + classSummary(item) + " - " + wlMsg,
                                scheduledId, "לא הצלחנו להירשם");
                    }
                    return;
                }

                JSONObject result = FizikalApi.register(item.classId, item.classDate,
                        item.contractId, item.purchaseId, session.accessToken);
                if (FizikalApi.isInvalidToken(result)) {
                    if (!recoverFromInvalidToken(db, scheduledId, item, session)) return;
                    continue; // retry from the top with the fresh token - doesn't count as a booking attempt
                }

                attempts++;
                JSONObject data = result.optJSONObject("data");
                String message;
                if (result.optBoolean("success") && data != null && data.optInt("actionStatus") == 1) {
                    JSONObject rc = data.getJSONObject("class");
                    String registrationId = String.valueOf(rc.optInt("registrationId"));

                    // The server said the booking call itself succeeded, but
                    // don't just trust that response blindly - independently
                    // re-check the class's live status before declaring it
                    // done. If it doesn't actually show us as registered,
                    // treat it like any other failed attempt and retry.
                    if (verifyRegistration(item, session)) {
                        db.updateRegistrationResult(scheduledId, "registered", "ההרשמה הצליחה ואומתה",
                                registrationId);
                        updateNotification("נרשמת לשיעור: " + classSummary(item), scheduledId, registrationId,
                                "נרשמת בהצלחה!");
                        return;
                    }
                    message = "השרת דיווח הצלחה אך האימות מול השיעור לא אישר זאת";
                } else {
                    message = FizikalApi.serverMessage(result, "לא ניתן להירשם");
                }
                db.updateMessage(scheduledId, "ניסיון " + attempts + ": " + message);

                long secondsPastOpen = (System.currentTimeMillis() - openedAtMillis) / 1000;
                if (attempts >= MAX_ATTEMPTS || secondsPastOpen >= GIVE_UP_SECONDS_PAST_OPEN) {
                    db.updateRegistrationResult(scheduledId, "failed",
                            "הופסק לאחר " + attempts + " ניסיונות: " + message, null);
                    updateFailureNotification("לא ניתן להירשם לשיעור: " + classSummary(item) + " - " + message,
                            scheduledId, "לא הצלחנו להירשם");
                    return;
                }
            } catch (Exception e) {
                db.updateMessage(scheduledId, "שגיאה פנימית: " + e);
            }

            sleepQuiet(1000);
        }

        String timeoutReason = everReachedServer
                ? "השיעור לא נפתח להרשמה בזמן שנקבע."
                : "לא הצלחנו להתחבר לשרת בזמן ההרשמה - בדוק את חיבור האינטרנט.";
        db.updateRegistrationResult(scheduledId, "failed", timeoutReason, null);
        updateFailureNotification("לא ניתן להירשם לשיעור: " + classSummary(item) + " - " + timeoutReason,
                scheduledId, "לא הצלחנו להירשם");
    }

    /** Server said the access token is genuinely invalid (401) - try a silent
     *  refresh, and if that fails too, fall back to waiting for the user to
     *  re-login. Returns true if we now have something worth retrying with. */
    private boolean recoverFromInvalidToken(DbHelper db, long scheduledId, ScheduledItem item, SessionState session) {
        if (session.tryRefreshNow(this)) return true;
        return waitForReLogin(db, scheduledId, item, session);
    }

    /** Returns true once the session has a fresh token again (user re-logged
     *  in via MainActivity while this service kept waiting), false if the
     *  grace period ran out first - in which case the scheduled item is
     *  marked failed and the caller should stop. */
    private boolean waitForReLogin(DbHelper db, long scheduledId, ScheduledItem item, SessionState session) {
        db.updateMessage(scheduledId, "החיבור פג - פתח את האפליקציה והתחבר מחדש כדי להמשיך לנסות להירשם לשיעור.");
        sendAlertNotification("פתח את האפליקציה והתחבר - נדרש כדי להירשם לשיעור: " + classSummary(item),
                scheduledId, "צריך שתתחבר/י מחדש לאפליקציה");

        long giveUpAt = System.currentTimeMillis() + RELOGIN_GRACE_MILLIS;
        while (System.currentTimeMillis() < giveUpAt) {
            sleepQuiet(5000);
            if (session.ensureFreshToken(this)) {
                db.updateMessage(scheduledId, "התחברת מחדש - ממשיך.");
                return true;
            }
        }
        db.updateRegistrationResult(scheduledId, "failed",
                "הזמן להתחברות מחדש חלף.", null);
        updateFailureNotification("לא ניתן להירשם לשיעור: " + classSummary(item) + " - נדרשה התחברות מחדש וזמן ההתחברות חלף",
                scheduledId, "לא הצלחנו להירשם");
        return false;
    }

    /** Human-readable "day date · time · instructor · description" label for
     *  a scheduled item, used in every registration-outcome notification. */
    private String classSummary(ScheduledItem item) {
        return Hebrew.classLabel(item.classDate, item.startTime, item.instructorName, item.description);
    }

    /** Independently re-checks the class's live status after a "success"
     *  response from the booking call, so we never declare victory purely
     *  because the server echoed success - a flaky response or a race with
     *  another device could still mean we're not actually registered. A few
     *  short retries absorb transient errors; anything left unconfirmed is
     *  treated as not-yet-registered so the caller retries the whole flow. */
    private boolean verifyRegistration(ScheduledItem item, SessionState session) {
        for (int i = 0; i < 3; i++) {
            try {
                JSONObject check = FizikalApi.getClassDetailsRaw(item.classId, item.classDate, session.accessToken);
                if (FizikalApi.isInvalidToken(check)) {
                    if (!session.tryRefreshNow(this)) return false;
                    continue;
                }
                if (!check.optBoolean("success", false)) {
                    sleepQuiet(700);
                    continue;
                }
                JSONObject cls = check.getJSONObject("data").getJSONObject("class");
                String action = cls.getJSONObject("action").getString("name");
                return FizikalApi.ACTION_ALREADY_REGISTERED.equals(action);
            } catch (Exception e) {
                sleepQuiet(700);
            }
        }
        return false;
    }

    private void sendAlertNotification(String text, long scheduledId, String popupTitle) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    ALERT_CHANNEL_ID, "דורש תשומת לב", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(channel);
        }
        Notification.Builder b = new Notification.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("ארלוזרוב אוטומטי")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true);
        attachFullScreenAlert(b, popupTitle, text, BigAlertActivity.KIND_INFO, scheduledId, null);
        nm.notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), b.build());
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /** The persistent "attempting registration..." notification, with a
     *  "בטל ניסיון הרשמה" action button so the user can stop the automated
     *  attempt straight from the notification without opening the app. */
    private Notification buildOngoingNotification(String text, long scheduledId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "הרשמה אוטומטית", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }

        Intent cancelIntent = new Intent(this, CancelAttemptReceiver.class);
        cancelIntent.putExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, scheduledId);
        PendingIntent cancelPi = PendingIntent.getBroadcast(
                this, 2000 + (int) scheduledId, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action cancelAction = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "בטל ניסיון הרשמה", cancelPi).build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ארלוזרוב אוטומטי")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .addAction(cancelAction)
                .build();
    }

    /** The final outcome notification for this scheduled item. Uses a fixed
     *  ID (not a random one) so it can be found and dismissed later, and -
     *  when a real registrationId is known - carries a "בטל הרשמה" action
     *  so the user can actually cancel the booking straight from here, even
     *  if they only notice the notification minutes afterwards. */
    private void updateNotification(String text, long scheduledId, String registrationId, String popupTitle) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        ensureOutcomeChannel(nm);
        Notification.Builder b = new Notification.Builder(this, OUTCOME_CHANNEL_ID)
                .setContentTitle("ארלוזרוב אוטומטי")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync);

        if (registrationId != null && !registrationId.isEmpty() && !"0".equals(registrationId)) {
            Intent cancelIntent = new Intent(this, CancelRegistrationReceiver.class);
            cancelIntent.putExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, scheduledId);
            cancelIntent.putExtra(UnregisterService.EXTRA_REGISTRATION_ID, registrationId);
            PendingIntent cancelPi = PendingIntent.getBroadcast(
                    this, 3000 + (int) scheduledId, cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "בטל הרשמה", cancelPi).build());
        }

        attachFullScreenAlert(b, popupTitle, text, BigAlertActivity.KIND_SUCCESS, scheduledId, registrationId);
        nm.notify(3000 + (int) scheduledId, b.build());
    }

    /** The final notification for a failed attempt - the text always says
     *  why it failed (server rejection reason, or "never reached the
     *  server"), and carries a "נסה שוב" action that resets the item to
     *  pending and immediately restarts RegistrationService, so the user
     *  can retry with one tap straight from the notification. */
    private void updateFailureNotification(String text, long scheduledId, String popupTitle) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        ensureOutcomeChannel(nm);

        Intent retryIntent = new Intent(this, RetryRegistrationReceiver.class);
        retryIntent.putExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, scheduledId);
        PendingIntent retryPi = PendingIntent.getBroadcast(
                this, 3000 + (int) scheduledId, retryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action retryAction = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.stat_notify_sync),
                "נסה שוב", retryPi).build();

        Notification.Builder b = new Notification.Builder(this, OUTCOME_CHANNEL_ID)
                .setContentTitle("ארלוזרוב אוטומטי")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .addAction(retryAction);
        attachFullScreenAlert(b, popupTitle, text, BigAlertActivity.KIND_FAILURE, scheduledId, null);
        nm.notify(3000 + (int) scheduledId, b.build());
    }

    private void ensureOutcomeChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    OUTCOME_CHANNEL_ID, "תוצאת הרשמה", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(channel);
        }
    }

    /** Wires a Notification.Builder to also pop BigAlertActivity full-screen
     *  the moment it's posted - the "grandma/grandpa accessible" layer on
     *  top of the normal notification. Android shows it automatically when
     *  the phone is locked/asleep; if the phone is actively in use right
     *  then, Android downgrades it to a heads-up notification instead (its
     *  own policy, not something any app can change). */
    private void attachFullScreenAlert(Notification.Builder b, String popupTitle, String message,
                                        String kind, long scheduledId, String registrationId) {
        Intent alertIntent = new Intent(this, BigAlertActivity.class);
        alertIntent.putExtra(BigAlertActivity.EXTRA_TITLE, popupTitle);
        alertIntent.putExtra(BigAlertActivity.EXTRA_MESSAGE, message);
        alertIntent.putExtra(BigAlertActivity.EXTRA_KIND, kind);
        alertIntent.putExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, scheduledId);
        if (registrationId != null) alertIntent.putExtra(UnregisterService.EXTRA_REGISTRATION_ID, registrationId);
        alertIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent alertPi = PendingIntent.getActivity(
                this, 5000 + (int) scheduledId, alertIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setFullScreenIntent(alertPi, true);
        b.setPriority(Notification.PRIORITY_HIGH);
    }
}
