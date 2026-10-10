package il.co.arlozorovauto;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.nayuki.qrcodegen.QrCode;

/** Full-screen entrance QR code - mirrors the original app's own flow
 *  (reverse-engineered from its logcat traffic): fetch the account's
 *  customerId once, open a QR session, then poll every 5s - each poll
 *  rotates the code to a fresh id (an anti-screenshot/anti-replay measure),
 *  until the door scanner flips success to true. FLAG_SECURE is set to
 *  match the original app, which also blocks screenshots of this screen. */
public class QrEntryActivity extends Activity {

    private static final long POLL_INTERVAL_MILLIS = 5000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable pollRunnable = new PollRunnable(this);

    private ImageView qrImage;
    private TextView statusText;
    private int customerId = -1;
    private String currentQrId;
    private boolean stopped = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_qr_entry);

        qrImage = findViewById(R.id.qr_image);
        statusText = findViewById(R.id.qr_status);
        Button closeButton = findViewById(R.id.qr_close_button);
        closeButton.setOnClickListener(new CloseClickListener(this));

        statusText.setText("טוען כרטיס כניסה...");
        io.execute(new StartTask(this));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopped = true;
        mainHandler.removeCallbacks(pollRunnable);
        io.shutdownNow();
    }

    private void onCustomerResolved(int customerId) {
        this.customerId = customerId;
        io.execute(new CreateQrTask(this, customerId));
    }

    private void onQrReady(String qrId) {
        currentQrId = qrId;
        renderQr(qrId);
        statusText.setText("הצג/י את הקוד בכניסה");
        schedulePoll();
    }

    private void schedulePoll() {
        if (!stopped) mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MILLIS);
    }

    private void poll() {
        if (!stopped && customerId != -1 && currentQrId != null) {
            io.execute(new PollTask(this, currentQrId, customerId));
        }
    }

    private void onPollResult(boolean success, String nextId) {
        if (stopped) return;
        if (nextId != null) {
            currentQrId = nextId;
            renderQr(nextId);
        }
        if (success) {
            statusText.setText("נכנסת בהצלחה!");
        } else {
            schedulePoll();
        }
    }

    private void onError(String message) {
        if (stopped) return;
        statusText.setText(message);
        // A transient hiccup shouldn't strand the user at the door - keep
        // trying rather than giving up on the whole session.
        schedulePoll();
    }

    private void renderQr(String text) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int scale = 10;
        int border = 4;
        int size = (qr.size + border * 2) * scale;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int qx = x / scale - border;
                int qy = y / scale - border;
                boolean dark = qx >= 0 && qx < qr.size && qy >= 0 && qy < qr.size && qr.getModule(qx, qy);
                bmp.setPixel(x, y, dark ? Color.BLACK : Color.WHITE);
            }
        }
        qrImage.setImageBitmap(bmp);
    }

    private static class CloseClickListener implements View.OnClickListener {
        private final Activity a;
        CloseClickListener(Activity a) { this.a = a; }
        @Override public void onClick(View v) { a.finish(); }
    }

    private static class PollRunnable implements Runnable {
        private final QrEntryActivity a;
        PollRunnable(QrEntryActivity a) { this.a = a; }
        @Override public void run() { a.poll(); }
    }

    private static class StartTask implements Runnable {
        private final QrEntryActivity a;
        StartTask(QrEntryActivity a) { this.a = a; }

        @Override public void run() {
            try {
                SessionState session = SessionState.get(a);
                if (!session.ensureFreshToken(a)) {
                    a.mainHandler.post(new ErrorRunnable(a, "החיבור פג - פתח/י את האפליקציה והתחבר/י מחדש"));
                    return;
                }
                JSONObject r = FizikalApi.getQrCustomersList(session.accessToken);
                if (!r.optBoolean("success", false)) {
                    a.mainHandler.post(new ErrorRunnable(a, FizikalApi.serverMessage(r, "לא ניתן לטעון כרטיס כניסה")));
                    return;
                }
                JSONArray list = r.getJSONObject("data").getJSONArray("list");
                if (list.length() == 0) {
                    a.mainHandler.post(new ErrorRunnable(a, "לא נמצא כרטיס כניסה לחשבון זה"));
                    return;
                }
                int customerId = list.getJSONObject(0).getInt("customerId");
                a.mainHandler.post(new CustomerResolvedRunnable(a, customerId));
            } catch (Exception e) {
                a.mainHandler.post(new ErrorRunnable(a, "שגיאה בטעינת כרטיס הכניסה"));
            }
        }
    }

    private static class CreateQrTask implements Runnable {
        private final QrEntryActivity a;
        private final int customerId;
        CreateQrTask(QrEntryActivity a, int customerId) {
            this.a = a;
            this.customerId = customerId;
        }

        @Override public void run() {
            try {
                SessionState session = SessionState.get(a);
                JSONObject r = FizikalApi.createQr(customerId, session.accessToken);
                if (!r.optBoolean("success", false)) {
                    a.mainHandler.post(new ErrorRunnable(a, FizikalApi.serverMessage(r, "לא ניתן היה ליצור כרטיס כניסה")));
                    return;
                }
                String qrId = r.getJSONObject("data").getString("id");
                a.mainHandler.post(new QrReadyRunnable(a, qrId));
            } catch (Exception e) {
                a.mainHandler.post(new ErrorRunnable(a, "שגיאה ביצירת כרטיס הכניסה"));
            }
        }
    }

    private static class PollTask implements Runnable {
        private final QrEntryActivity a;
        private final String qrId;
        private final int customerId;
        PollTask(QrEntryActivity a, String qrId, int customerId) {
            this.a = a;
            this.qrId = qrId;
            this.customerId = customerId;
        }

        @Override public void run() {
            try {
                SessionState session = SessionState.get(a);
                JSONObject r = FizikalApi.checkQrStatus(qrId, customerId, session.accessToken);
                if (FizikalApi.isInvalidToken(r)) {
                    session.tryRefreshNow(a);
                    r = FizikalApi.checkQrStatus(qrId, customerId, session.accessToken);
                }
                JSONObject data = r.optJSONObject("data");
                String nextId = data != null && data.has("id") ? data.getString("id") : null;
                boolean success = r.optBoolean("success", false);
                a.mainHandler.post(new PollResultRunnable(a, success, nextId));
            } catch (Exception e) {
                a.mainHandler.post(new ErrorRunnable(a, "שגיאה בבדיקת הכרטיס - מנסה שוב..."));
            }
        }
    }

    private static class CustomerResolvedRunnable implements Runnable {
        private final QrEntryActivity a;
        private final int customerId;
        CustomerResolvedRunnable(QrEntryActivity a, int customerId) {
            this.a = a;
            this.customerId = customerId;
        }
        @Override public void run() { a.onCustomerResolved(customerId); }
    }

    private static class QrReadyRunnable implements Runnable {
        private final QrEntryActivity a;
        private final String qrId;
        QrReadyRunnable(QrEntryActivity a, String qrId) {
            this.a = a;
            this.qrId = qrId;
        }
        @Override public void run() { a.onQrReady(qrId); }
    }

    private static class PollResultRunnable implements Runnable {
        private final QrEntryActivity a;
        private final boolean success;
        private final String nextId;
        PollResultRunnable(QrEntryActivity a, boolean success, String nextId) {
            this.a = a;
            this.success = success;
            this.nextId = nextId;
        }
        @Override public void run() { a.onPollResult(success, nextId); }
    }

    private static class ErrorRunnable implements Runnable {
        private final QrEntryActivity a;
        private final String message;
        ErrorRunnable(QrEntryActivity a, String message) {
            this.a = a;
            this.message = message;
        }
        @Override public void run() { a.onError(message); }
    }
}
