package il.co.arlozorovauto;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

/** Full-screen, high-contrast alert shown alongside every registration
 *  notification - built for a user who won't notice or won't know how to
 *  use a notification's small action buttons. Launched via
 *  Notification.setFullScreenIntent(), the standard Android mechanism for
 *  "show this over the lock screen the moment it happens" (the same one
 *  incoming-call and alarm apps use). Android shows it automatically when
 *  the phone is locked/asleep - the common case for a scheduled
 *  registration - and falls back to a normal heads-up notification if the
 *  phone is actively being used at that exact moment; that fallback is
 *  Android's own policy and no app can override it. */
public class BigAlertActivity extends Activity {

    static final String EXTRA_TITLE = "title";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_KIND = "kind";
    static final String KIND_SUCCESS = "success";
    static final String KIND_FAILURE = "failure";
    static final String KIND_INFO = "info";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_big_alert);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String message = getIntent().getStringExtra(EXTRA_MESSAGE);
        String kind = getIntent().getStringExtra(EXTRA_KIND);
        long scheduledId = getIntent().getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, -1);
        String registrationId = getIntent().getStringExtra(UnregisterService.EXTRA_REGISTRATION_ID);

        TextView titleView = findViewById(R.id.alert_title);
        TextView messageView = findViewById(R.id.alert_message);
        Button actionButton = findViewById(R.id.alert_action_button);
        Button closeButton = findViewById(R.id.alert_close_button);

        titleView.setText(title != null ? title : "");
        messageView.setText(message != null ? message : "");

        int titleColor;
        if (KIND_SUCCESS.equals(kind)) titleColor = getColor(R.color.ok);
        else if (KIND_FAILURE.equals(kind)) titleColor = getColor(R.color.err);
        else titleColor = getColor(R.color.accent);
        titleView.setTextColor(titleColor);

        if (KIND_FAILURE.equals(kind) && scheduledId != -1) {
            actionButton.setVisibility(View.VISIBLE);
            actionButton.setText("נסה שוב");
            actionButton.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.accent)));
            actionButton.setTextColor(getColor(R.color.text_primary));
            actionButton.setOnClickListener(new RetryClickListener(this, scheduledId));
        } else if (KIND_SUCCESS.equals(kind) && registrationId != null
                && !registrationId.isEmpty() && !"0".equals(registrationId)) {
            actionButton.setVisibility(View.VISIBLE);
            actionButton.setText("בטל הרשמה");
            actionButton.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.err)));
            actionButton.setTextColor(getColor(R.color.text_primary));
            actionButton.setOnClickListener(new CancelClickListener(this, scheduledId, registrationId));
        }

        closeButton.setOnClickListener(new CloseClickListener(this));
    }

    private static class CloseClickListener implements View.OnClickListener {
        private final Activity a;
        CloseClickListener(Activity a) { this.a = a; }
        @Override public void onClick(View v) { a.finish(); }
    }

    private static class RetryClickListener implements View.OnClickListener {
        private final Activity a;
        private final long scheduledId;
        RetryClickListener(Activity a, long scheduledId) {
            this.a = a;
            this.scheduledId = scheduledId;
        }
        @Override public void onClick(View v) {
            Intent i = new Intent(a, RetryRegistrationReceiver.class);
            i.putExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, scheduledId);
            a.sendBroadcast(i);
            a.finish();
        }
    }

    private static class CancelClickListener implements View.OnClickListener {
        private final Activity a;
        private final long scheduledId;
        private final String registrationId;
        CancelClickListener(Activity a, long scheduledId, String registrationId) {
            this.a = a;
            this.scheduledId = scheduledId;
            this.registrationId = registrationId;
        }
        @Override public void onClick(View v) {
            Intent i = new Intent(a, CancelRegistrationReceiver.class);
            i.putExtra(AlarmScheduler.EXTRA_SCHEDULED_ID, scheduledId);
            i.putExtra(UnregisterService.EXTRA_REGISTRATION_ID, registrationId);
            a.sendBroadcast(i);
            a.finish();
        }
    }
}
