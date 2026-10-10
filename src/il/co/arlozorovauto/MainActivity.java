package il.co.arlozorovauto;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public class MainActivity extends Activity {

    static final int DAYS_AHEAD = 14;
    static final int SCREEN_CLASSES = 0, SCREEN_REG = 1, SCREEN_ACCOUNT = 2;
    static final int SEG_PENDING = 0, SEG_REGISTERED = 1, SEG_RECURRING = 2;

    DbHelper db;
    SessionState session;
    final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Login
    View loginSection;
    EditText phoneInput, codeInput;
    Button sendOtpButton, verifyOtpButton;
    TextView loginError;
    // Content + nav
    View contentArea, screenClasses, screenRegistrations, screenAccount, bottomNav;
    Button navClasses, navRegistrations, navAccount;
    // Classes screen
    Button refreshButton;
    TextView classesError;
    ListView classesList;
    android.widget.LinearLayout dayTabsContainer;
    android.widget.HorizontalScrollView dayTabsScroll;
    // Registrations screen
    Button segPending, segRegistered, segRecurring;
    TextView scheduledEmpty;
    ListView scheduledList;
    // Account screen
    TextView phoneDisplay, alarmWarning;
    Button qrEntryButton, reverifyButton, logoutButton;

    final DataChangedReceiver dataChangedReceiver = new DataChangedReceiver(this);

    String pendingPhone;
    boolean pollingScheduled = false;
    boolean scheduleLoading = false;
    int currentScreen = SCREEN_CLASSES;
    int regSegment = SEG_PENDING;

    final java.util.LinkedHashMap<String, JSONArray> dayClasses = new java.util.LinkedHashMap<>();
    final java.util.LinkedHashMap<String, String> dayLabels = new java.util.LinkedHashMap<>();
    String selectedDate;
    View selectedTabView;
    String lastListSig;
    private final Runnable poller = new Poller(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new DbHelper(this);
        session = SessionState.get(this);
        FizikalApi.initDeviceId(this);

        loginSection = findViewById(R.id.login_section);
        phoneInput = findViewById(R.id.phone_input);
        codeInput = findViewById(R.id.code_input);
        sendOtpButton = findViewById(R.id.send_otp_button);
        verifyOtpButton = findViewById(R.id.verify_otp_button);
        loginError = findViewById(R.id.login_error);

        contentArea = findViewById(R.id.content_area);
        screenClasses = findViewById(R.id.screen_classes);
        screenRegistrations = findViewById(R.id.screen_registrations);
        screenAccount = findViewById(R.id.screen_account);
        bottomNav = findViewById(R.id.bottom_nav);
        navClasses = findViewById(R.id.nav_classes);
        navRegistrations = findViewById(R.id.nav_registrations);
        navAccount = findViewById(R.id.nav_account);

        refreshButton = findViewById(R.id.refresh_button);
        classesError = findViewById(R.id.classes_error);
        classesList = findViewById(R.id.classes_list);
        dayTabsContainer = findViewById(R.id.day_tabs_container);
        dayTabsScroll = findViewById(R.id.day_tabs_scroll);

        segPending = findViewById(R.id.seg_pending);
        segRegistered = findViewById(R.id.seg_registered);
        segRecurring = findViewById(R.id.seg_recurring);
        scheduledEmpty = findViewById(R.id.scheduled_empty);
        scheduledList = findViewById(R.id.scheduled_list);

        phoneDisplay = findViewById(R.id.phone_display);
        alarmWarning = findViewById(R.id.alarm_warning);
        qrEntryButton = findViewById(R.id.qr_entry_button);
        reverifyButton = findViewById(R.id.reverify_button);
        logoutButton = findViewById(R.id.logout_button);

        sendOtpButton.setOnClickListener(new Click(this, Click.SEND_OTP));
        verifyOtpButton.setOnClickListener(new Click(this, Click.VERIFY_OTP));
        refreshButton.setOnClickListener(new Click(this, Click.REFRESH));
        qrEntryButton.setOnClickListener(new Click(this, Click.QR_ENTRY));
        reverifyButton.setOnClickListener(new Click(this, Click.REVERIFY));
        logoutButton.setOnClickListener(new Click(this, Click.LOGOUT));
        alarmWarning.setOnClickListener(new Click(this, Click.FIX_ALARMS));
        navClasses.setOnClickListener(new Click(this, Click.NAV_CLASSES));
        navRegistrations.setOnClickListener(new Click(this, Click.NAV_REG));
        navAccount.setOnClickListener(new Click(this, Click.NAV_ACCOUNT));
        segPending.setOnClickListener(new Click(this, Click.SEG_PENDING));
        segRegistered.setOnClickListener(new Click(this, Click.SEG_REGISTERED));
        segRecurring.setOnClickListener(new Click(this, Click.SEG_RECURRING));

        AlarmScheduler.scheduleDailySweep(this);
        requestRuntimePermissions();
        refreshLoginUi();
    }

    @Override protected void onResume() {
        super.onResume();
        pollingScheduled = true;
        pollLoop();
        updateAlarmWarning();

        IntentFilter filter = new IntentFilter(AppEvents.ACTION_DATA_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dataChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(dataChangedReceiver, filter);
        }

        // The broadcast above only reaches us while the app is actually
        // open (the receiver is unregistered in onPause) - a cancellation
        // made from a notification while the app was backgrounded would
        // otherwise leave the cached schedule/registrations stale until the
        // user happened to hit the manual refresh button. Force a fresh
        // reload every time the app comes back to the foreground instead of
        // only when dayClasses happens to be empty.
        if (session != null && session.isLoggedIn()) {
            lastListSig = null;
            refreshRegistrations();
            if (!scheduleLoading) loadSchedule();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        pollingScheduled = false;
        unregisterReceiver(dataChangedReceiver);
    }

    /** Fired by DbHelper on every write that changes what the lists show -
     *  a background registration/cancellation event should refresh the UI
     *  immediately, the same way cancelling a class from inside the app
     *  does, instead of waiting for the next 5s poll. */
    private static class DataChangedReceiver extends BroadcastReceiver {
        private final MainActivity a;
        DataChangedReceiver(MainActivity a) { this.a = a; }

        @Override public void onReceive(Context context, Intent intent) {
            if (a.session == null || !a.session.isLoggedIn()) return;
            a.lastListSig = null;
            a.refreshRegistrations();
            if (!a.scheduleLoading) a.loadSchedule();
        }
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = getSystemService(AlarmManager.class);
            if (!am.canScheduleExactAlarms()) {
                Toast.makeText(this, "יש לאשר ‘התראות ותזכורות’ כדי שההרשמות יתבצעו בזמן.", Toast.LENGTH_LONG).show();
                openExactAlarmSettings();
            }
        }
    }

    void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "הגדרות ← אפליקציות ← ארלוזרוב אוטומטי ← התראות ותזכורות", Toast.LENGTH_LONG).show();
            }
        }
    }

    void updateAlarmWarning() {
        boolean show = session.isLoggedIn() && !AlarmScheduler.canScheduleExact(this);
        alarmWarning.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ---------------- top-level view state ----------------

    void refreshLoginUi() {
        boolean loggedIn = session.isLoggedIn();
        loginSection.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
        contentArea.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        bottomNav.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        if (loggedIn) {
            phoneDisplay.setText("מחובר כ-" + session.phone);
            setScreen(SCREEN_CLASSES);
            if (dayClasses.isEmpty() && !scheduleLoading) loadSchedule();
            else { rebuildDayTabs(); renderSchedule(); }
        }
        updateAlarmWarning();
    }

    void setScreen(int screen) {
        currentScreen = screen;
        screenClasses.setVisibility(screen == SCREEN_CLASSES ? View.VISIBLE : View.GONE);
        screenRegistrations.setVisibility(screen == SCREEN_REG ? View.VISIBLE : View.GONE);
        screenAccount.setVisibility(screen == SCREEN_ACCOUNT ? View.VISIBLE : View.GONE);
        navClasses.setTextColor(screen == SCREEN_CLASSES ? 0xFF4F8CFF : 0xFF8B93A1);
        navClasses.setTypeface(null, screen == SCREEN_CLASSES ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        navRegistrations.setTextColor(screen == SCREEN_REG ? 0xFF4F8CFF : 0xFF8B93A1);
        navRegistrations.setTypeface(null, screen == SCREEN_REG ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        navAccount.setTextColor(screen == SCREEN_ACCOUNT ? 0xFF4F8CFF : 0xFF8B93A1);
        navAccount.setTypeface(null, screen == SCREEN_ACCOUNT ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        if (screen == SCREEN_REG) { lastListSig = null; refreshRegistrations(); }
        if (screen == SCREEN_ACCOUNT) updateAlarmWarning();
    }

    @Override public void onBackPressed() {
        if (session.isLoggedIn() && currentScreen != SCREEN_CLASSES) { setScreen(SCREEN_CLASSES); return; }
        super.onBackPressed();
    }

    // ---------------- login ----------------

    void onSendOtp() {
        String phone = phoneInput.getText().toString().trim();
        if (phone.isEmpty()) return;
        loginError.setText("");
        pendingPhone = phone;
        io.execute(new SendOtpTask(this, phone));
    }

    void onVerifyOtp() {
        String code = codeInput.getText().toString().trim();
        if (code.isEmpty() || pendingPhone == null) return;
        io.execute(new VerifyOtpTask(this, pendingPhone, code));
    }

    void onLogout() {
        session.logout(this);
        dayClasses.clear(); dayLabels.clear(); selectedDate = null;
        currentScreen = SCREEN_CLASSES; regSegment = SEG_PENDING;
        refreshLoginUi();
    }

    void onReverify() {
        contentArea.setVisibility(View.GONE);
        bottomNav.setVisibility(View.GONE);
        loginSection.setVisibility(View.VISIBLE);
        codeInput.setVisibility(View.GONE);
        verifyOtpButton.setVisibility(View.GONE);
        loginError.setText("");
        if (session.phone != null) phoneInput.setText(session.phone);
    }

    // ---------------- classes screen ----------------

    void onRefresh() { if (!scheduleLoading) loadSchedule(); }

    void loadSchedule() {
        scheduleLoading = true;
        dayClasses.clear(); dayLabels.clear(); selectedDate = null;
        dayTabsContainer.removeAllViews();
        classesError.setText("טוען לוח שיעורים...");
        renderSchedule();
        io.execute(new LoadScheduleTask(this));
    }

    void appendDay(String date, String label, JSONArray classes) {
        dayClasses.put(date, classes);
        dayLabels.put(date, label);
        if (selectedDate == null) selectedDate = date;
        rebuildDayTabs();
        renderSchedule();
    }

    void onScheduleLoadDone() {
        scheduleLoading = false;
        classesError.setText(dayClasses.isEmpty()
                ? "לא נמצאו שיעורים ב-" + DAYS_AHEAD + " הימים הקרובים." : "");
        // Newly-visible weeks may match recurring rules - queue them.
        int added = RecurringSweep.materialize(this, dayClasses);
        if (added > 0) { renderSchedule(); }
        refreshRegistrations();
    }

    void onScheduleLoadError(String msg) {
        scheduleLoading = false;
        classesError.setText(msg);
    }

    void selectDay(String date) { selectedDate = date; rebuildDayTabs(); renderSchedule(); }

    void rebuildDayTabs() {
        dayTabsContainer.removeAllViews();
        selectedTabView = null;
        int pad = Math.round(getResources().getDisplayMetrics().density * 6);
        for (String date : dayLabels.keySet()) {
            Button tab = new Button(this);
            tab.setText(dayLabels.get(date));
            tab.setAllCaps(false);
            tab.setTextSize(13);
            tab.setMinWidth(0); tab.setMinHeight(0);
            tab.setPadding(pad * 3, pad, pad * 3, pad);
            boolean selected = date.equals(selectedDate);
            tab.setBackgroundResource(selected ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
            tab.setTextColor(selected ? 0xFFFFFFFF : 0xFFE6E8EB);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = pad + pad;
            tab.setLayoutParams(lp);
            tab.setOnClickListener(new DayTabListener(this, date));
            dayTabsContainer.addView(tab);
            if (selected) selectedTabView = tab;
        }
        if (selectedTabView != null)
            dayTabsScroll.post(new RevealTabRunnable(dayTabsScroll, dayTabsContainer, selectedTabView));
    }

    void renderSchedule() {
        java.util.List<Object> rows = new java.util.ArrayList<>();
        if (selectedDate != null && dayClasses.containsKey(selectedDate)) {
            JSONArray classes = dayClasses.get(selectedDate);
            for (int i = 0; i < classes.length(); i++) rows.add(classes.optJSONObject(i));
        }
        java.util.Set<String> scheduledKeys = new java.util.HashSet<>();
        for (ScheduledItem s : db.listPending()) scheduledKeys.add(s.classId + "|" + s.classDate);
        classesList.setAdapter(new ScheduleAdapter(this, rows, scheduledKeys,
                new RegisterClickHandler(this), new UnregisterClickHandler(this)));
    }

    /** Class tapped in the schedule - ask one-time vs weekly. */
    void onClassTapped(JSONObject c) {
        String[] choices = new String[]{"הרשמה פעם אחת", "הרשמה כל שבוע"};
        new AlertDialog.Builder(this)
                .setTitle(c.optString("description"))
                .setItems(choices, new BookingChoiceListener(this, c))
                .setNegativeButton("ביטול", null)
                .show();
    }

    void onBookOnce(JSONObject c) {
        ScheduledItem s = ScheduledItem.fromClassJson(c);
        s.id = db.addScheduled(s);
        AlarmScheduler.schedule(this, s);
        Toast.makeText(this, "תוזמן: " + s.description, Toast.LENGTH_SHORT).show();
        renderSchedule();
    }

    void onBookWeekly(JSONObject c) {
        String dateReq = c.optString("dateRequest");
        String start = c.optString("startTime");
        String desc = c.optString("description");
        int weekday = RecurringSweep.weekdayOf(dateReq);
        if (weekday == 0) { onBookOnce(c); return; }
        if (!db.hasRecurring(weekday, start, desc)) {
            RecurringItem r = new RecurringItem();
            r.weekday = weekday;
            r.startTime = start;
            r.description = desc;
            r.dayLabel = c.optString("day", "");
            r.contractId = c.optInt("contractId", 0);
            r.purchaseId = c.optInt("purchaseId", 0);
            db.addRecurring(r);
        }
        AlarmScheduler.scheduleDailySweep(this);
        // Queue every matching class already visible in the loaded schedule.
        RecurringSweep.materialize(this, dayClasses);
        Toast.makeText(this, "נקבע כל שבוע: " + desc, Toast.LENGTH_SHORT).show();
        renderSchedule();
    }

    // ---------------- registrations screen ----------------

    void selectSegment(int seg) {
        regSegment = seg;
        segPending.setBackgroundResource(seg == SEG_PENDING ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        segPending.setTextColor(seg == SEG_PENDING ? 0xFFFFFFFF : 0xFFE6E8EB);
        segRegistered.setBackgroundResource(seg == SEG_REGISTERED ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        segRegistered.setTextColor(seg == SEG_REGISTERED ? 0xFFFFFFFF : 0xFFE6E8EB);
        segRecurring.setBackgroundResource(seg == SEG_RECURRING ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        segRecurring.setTextColor(seg == SEG_RECURRING ? 0xFFFFFFFF : 0xFFE6E8EB);
        lastListSig = null;
        refreshRegistrations();
    }

    void refreshRegistrations() {
        if (regSegment == SEG_RECURRING) {
            List<RecurringItem> rules = db.listRecurring();
            scheduledEmpty.setText("אין שיעורים קבועים. הקש ‘הרשמה כל שבוע’ על שיעור.");
            scheduledEmpty.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
            String sig = "R" + rules.size();
            for (RecurringItem r : rules) sig += "|" + r.id;
            if (!sig.equals(lastListSig)) {
                lastListSig = sig;
                scheduledList.setAdapter(new RecurringAdapter(this, rules, new DeleteRecurringHandler(this)));
            }
            updateNavCount();
            return;
        }

        List<ScheduledItem> items = new java.util.ArrayList<>();
        StringBuilder sig = new StringBuilder(regSegment == SEG_PENDING ? "P" : "G");

        if (regSegment == SEG_REGISTERED) {
            // Source of truth for "what am I registered for" is the SERVER, not
            // our local DB - scan the loaded schedule for classes the server
            // reports as registered (action = RemoveRegistration). This keeps
            // working even if our DB was cleared.
            for (JSONArray classes : dayClasses.values()) {
                for (int i = 0; i < classes.length(); i++) {
                    JSONObject c = classes.optJSONObject(i);
                    if (c == null) continue;
                    JSONObject act = c.optJSONObject("action");
                    String an = act != null ? act.optString("name") : "";
                    if (!FizikalApi.ACTION_ALREADY_REGISTERED.equals(an)) continue;
                    ScheduledItem s = ScheduledItem.fromClassJson(c);
                    if (s.isPast()) continue;
                    s.id = -1;                 // not a DB row - server-derived
                    s.status = "registered";
                    items.add(s);
                    sig.append(s.classId).append(s.classDate).append('|');
                }
            }
            scheduledEmpty.setText(dayClasses.isEmpty()
                    ? "טוען..." : "עדיין אין שיעורים שנרשמת אליהם.");
        } else {
            for (ScheduledItem s : db.listAllScheduled()) {
                if (s.isPast()) continue;
                if ("cancelled".equals(s.status)) continue;
                boolean isRegistered = "registered".equals(s.status) || "waitlisted".equals(s.status);
                if (isRegistered) continue; // registered ones come from the server (above)
                items.add(s);
                sig.append(s.id).append(s.status).append(s.message).append('|');
            }
            scheduledEmpty.setText("אין הרשמות מתוזמנות. הקש ‘תזמן הרשמה’ על שיעור.");
        }

        scheduledEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        String newSig = sig.toString();
        if (!newSig.equals(lastListSig)) {
            lastListSig = newSig;
            scheduledList.setAdapter(new ScheduledAdapter(this, items, new CancelClickHandler(this)));
        }
        updateNavCount();
    }

    /** Show a count of queued (pending, not past) items on the nav tab. */
    void updateNavCount() {
        int pending = 0;
        for (ScheduledItem s : db.listPending()) if (!s.isPast()) pending++;
        navRegistrations.setText(pending > 0 ? "ההרשמות שלי (" + pending + ")" : "ההרשמות שלי");
    }

    void onCancelScheduled(ScheduledItem item) {
        boolean registered = "registered".equals(item.status) || "waitlisted".equals(item.status);
        if (registered) {
            // Real unregister from the class - confirm first.
            new AlertDialog.Builder(this)
                    .setTitle("ביטול הרשמה")
                    .setMessage("לבטל את ההרשמה ל" + item.description + "?")
                    .setPositiveButton("כן, בטל", new ConfirmUnregisterListener(this, item.registrationId, item.id))
                    .setNegativeButton("לא", null)
                    .show();
            return;
        }
        // Pending - just drop it from our queue (no server call).
        db.cancelScheduled(item.id);
        AlarmScheduler.cancel(this, item.id);
        lastListSig = null;
        refreshRegistrations();
        renderSchedule();
    }

    /** Unregister tapped from the schedule screen (a currently-registered class). */
    void onUnregisterClass(JSONObject c) {
        final String regId = c.has("registrationId") ? String.valueOf(c.optInt("registrationId")) : null;
        new AlertDialog.Builder(this)
                .setTitle("ביטול הרשמה")
                .setMessage("לבטל את ההרשמה ל" + c.optString("description") + "?")
                .setPositiveButton("כן, בטל", new ConfirmUnregisterListener(this, regId, -1))
                .setNegativeButton("לא", null)
                .show();
    }

    /** Runs the real remove call off the main thread; scheduledRowId >= 0 means
     *  also mark that queued row cancelled on success. */
    void doRemoveRegistration(String registrationId, long scheduledRowId) {
        if (registrationId == null || registrationId.isEmpty() || "0".equals(registrationId)) {
            Toast.makeText(this, "לא נמצא מזהה הרשמה לביטול.", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "מבטל הרשמה...", Toast.LENGTH_SHORT).show();
        io.execute(new RemoveTask(this, registrationId, scheduledRowId));
    }

    void onRemoveResult(boolean ok, String message, long scheduledRowId) {
        if (ok && scheduledRowId >= 0) {
            db.updateRegistrationResult(scheduledRowId, "cancelled", "ההרשמה בוטלה", null);
        }
        Toast.makeText(this, ok ? "ההרשמה בוטלה" : ("הביטול נכשל: " + message), Toast.LENGTH_LONG).show();
        lastListSig = null;
        refreshRegistrations();
        if (!scheduleLoading) loadSchedule(); // refresh the schedule so the class updates
    }

    void onDeleteRecurring(RecurringItem item) {
        db.deleteRecurring(item.id);
        lastListSig = null;
        refreshRegistrations();
    }

    void pollLoop() {
        if (!pollingScheduled) return;
        if (session.isLoggedIn()) {
            if (currentScreen == SCREEN_REG) refreshRegistrations();
            else updateNavCount();
        }
        mainHandler.postDelayed(poller, 5000);
    }

    // ==================== named helper classes (no lambdas/anon - D8 bug) ====================

    private static class Click implements View.OnClickListener {
        static final int SEND_OTP=1, VERIFY_OTP=2, LOGOUT=3, REFRESH=4, REVERIFY=5, FIX_ALARMS=6,
                NAV_CLASSES=7, NAV_REG=8, NAV_ACCOUNT=9, SEG_PENDING=10, SEG_REGISTERED=11, SEG_RECURRING=12,
                QR_ENTRY=13;
        private final MainActivity a; private final int action;
        Click(MainActivity a, int action) { this.a = a; this.action = action; }
        @Override public void onClick(View v) {
            switch (action) {
                case SEND_OTP: a.onSendOtp(); break;
                case VERIFY_OTP: a.onVerifyOtp(); break;
                case LOGOUT: a.onLogout(); break;
                case REFRESH: a.onRefresh(); break;
                case REVERIFY: a.onReverify(); break;
                case FIX_ALARMS: a.openExactAlarmSettings(); break;
                case NAV_CLASSES: a.setScreen(SCREEN_CLASSES); break;
                case NAV_REG: a.setScreen(SCREEN_REG); break;
                case NAV_ACCOUNT: a.setScreen(SCREEN_ACCOUNT); break;
                case SEG_PENDING: a.selectSegment(MainActivity.SEG_PENDING); break;
                case SEG_REGISTERED: a.selectSegment(MainActivity.SEG_REGISTERED); break;
                case SEG_RECURRING: a.selectSegment(MainActivity.SEG_RECURRING); break;
                case QR_ENTRY: a.startActivity(new Intent(a, QrEntryActivity.class)); break;
            }
        }
    }

    private static class BookingChoiceListener implements DialogInterface.OnClickListener {
        private final MainActivity a; private final JSONObject c;
        BookingChoiceListener(MainActivity a, JSONObject c) { this.a = a; this.c = c; }
        @Override public void onClick(DialogInterface d, int which) {
            if (which == 0) a.onBookOnce(c); else a.onBookWeekly(c);
        }
    }

    private static class SetTextRunnable implements Runnable {
        private final TextView view; private final String text;
        SetTextRunnable(TextView view, String text) { this.view = view; this.text = text; }
        @Override public void run() { view.setText(text); }
    }

    private static class ShowCodeInputRunnable implements Runnable {
        private final MainActivity a;
        ShowCodeInputRunnable(MainActivity a) { this.a = a; }
        @Override public void run() {
            a.codeInput.setVisibility(View.VISIBLE);
            a.verifyOtpButton.setVisibility(View.VISIBLE);
        }
    }

    private static class RefreshLoginUiRunnable implements Runnable {
        private final MainActivity a;
        RefreshLoginUiRunnable(MainActivity a) { this.a = a; }
        @Override public void run() { a.refreshLoginUi(); }
    }

    private static class SendOtpTask implements Runnable {
        private final MainActivity a; private final String phone;
        SendOtpTask(MainActivity a, String phone) { this.a = a; this.phone = phone; }
        @Override public void run() {
            try {
                FizikalApi.sendOtp(phone);
                a.mainHandler.post(new ShowCodeInputRunnable(a));
            } catch (Exception e) {
                a.mainHandler.post(new SetTextRunnable(a.loginError, humanError(e)));
            }
        }
    }

    private static class VerifyOtpTask implements Runnable {
        private final MainActivity a; private final String phone, code;
        VerifyOtpTask(MainActivity a, String phone, String code) { this.a = a; this.phone = phone; this.code = code; }
        @Override public void run() {
            try {
                String[] tokens = FizikalApi.verifyOtp(phone, code);
                a.session.phone = phone;
                a.session.setTokens(tokens[0], tokens[1]);
                a.db.saveSession(phone, tokens[1]);
                a.mainHandler.post(new RefreshLoginUiRunnable(a));
            } catch (Exception e) {
                a.mainHandler.post(new SetTextRunnable(a.loginError, humanError(e)));
            }
        }
    }

    private static class LoadScheduleTask implements Runnable {
        private final MainActivity a;
        LoadScheduleTask(MainActivity a) { this.a = a; }
        @Override public void run() {
            try {
                if (!a.session.ensureFreshToken(a))
                    throw new Exception("החיבור פג. הקש ‘התחבר מחדש’ והתחבר שוב.");
                java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                iso.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Jerusalem"));
                java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jerusalem"));
                for (int d = 0; d < DAYS_AHEAD; d++) {
                    String dateStr = iso.format(cal.getTime());
                    cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
                    try {
                        JSONArray classes = FizikalApi.getSchedule(dateStr, a.session.accessToken);
                        if (classes.length() > 0) {
                            JSONObject first = classes.optJSONObject(0);
                            String label = first.optString("day", "") + "\n" + first.optString("date", dateStr);
                            a.mainHandler.post(new AppendDayRunnable(a, dateStr, label, classes));
                        }
                    } catch (Exception perDay) { /* skip a bad day */ }
                }
                a.mainHandler.post(new LoadDoneRunnable(a));
            } catch (Exception e) {
                a.mainHandler.post(new LoadErrorRunnable(a, humanError(e)));
            }
        }
    }

    private static class AppendDayRunnable implements Runnable {
        private final MainActivity a; private final String date, label; private final JSONArray classes;
        AppendDayRunnable(MainActivity a, String date, String label, JSONArray classes) {
            this.a = a; this.date = date; this.label = label; this.classes = classes;
        }
        @Override public void run() { a.appendDay(date, label, classes); }
    }

    private static class LoadDoneRunnable implements Runnable {
        private final MainActivity a;
        LoadDoneRunnable(MainActivity a) { this.a = a; }
        @Override public void run() { a.onScheduleLoadDone(); }
    }

    private static class LoadErrorRunnable implements Runnable {
        private final MainActivity a; private final String msg;
        LoadErrorRunnable(MainActivity a, String msg) { this.a = a; this.msg = msg; }
        @Override public void run() { a.onScheduleLoadError(msg); }
    }

    private static class DayTabListener implements View.OnClickListener {
        private final MainActivity a; private final String date;
        DayTabListener(MainActivity a, String date) { this.a = a; this.date = date; }
        @Override public void onClick(View v) { a.selectDay(date); }
    }

    private static class RevealTabRunnable implements Runnable {
        private final android.widget.HorizontalScrollView scroll; private final View container; private final View tab;
        RevealTabRunnable(android.widget.HorizontalScrollView scroll, View container, View tab) {
            this.scroll = scroll; this.container = container; this.tab = tab;
        }
        @Override public void run() {
            android.graphics.Rect r = new android.graphics.Rect(tab.getLeft(), tab.getTop(), tab.getRight(), tab.getBottom());
            scroll.requestChildRectangleOnScreen(container, r, true);
        }
    }

    private static class RegisterClickHandler implements ScheduleAdapter.OnRegisterClick {
        private final MainActivity a;
        RegisterClickHandler(MainActivity a) { this.a = a; }
        @Override public void onClick(JSONObject classObj) { a.onClassTapped(classObj); }
    }

    private static class CancelClickHandler implements ScheduledAdapter.OnCancelClick {
        private final MainActivity a;
        CancelClickHandler(MainActivity a) { this.a = a; }
        @Override public void onClick(ScheduledItem item) { a.onCancelScheduled(item); }
    }

    private static class UnregisterClickHandler implements ScheduleAdapter.OnUnregisterClick {
        private final MainActivity a;
        UnregisterClickHandler(MainActivity a) { this.a = a; }
        @Override public void onClick(JSONObject classObj) { a.onUnregisterClass(classObj); }
    }

    private static class ConfirmUnregisterListener implements DialogInterface.OnClickListener {
        private final MainActivity a; private final String registrationId; private final long rowId;
        ConfirmUnregisterListener(MainActivity a, String registrationId, long rowId) {
            this.a = a; this.registrationId = registrationId; this.rowId = rowId;
        }
        @Override public void onClick(DialogInterface d, int which) {
            a.doRemoveRegistration(registrationId, rowId);
        }
    }

    private static class RemoveTask implements Runnable {
        private final MainActivity a; private final String registrationId; private final long rowId;
        RemoveTask(MainActivity a, String registrationId, long rowId) {
            this.a = a; this.registrationId = registrationId; this.rowId = rowId;
        }
        @Override public void run() {
            boolean ok = false; String msg = "";
            try {
                if (!a.session.ensureFreshToken(a)) { msg = "החיבור פג"; }
                else {
                    JSONObject r = FizikalApi.removeRegistration(registrationId, a.session.accessToken);
                    ok = r.optBoolean("success", false);
                    if (!ok) msg = FizikalApi.serverMessage(r, "לא ניתן לבטל");
                }
            } catch (Exception e) { msg = humanError(e); }
            final boolean fok = ok; final String fmsg = msg;
            a.mainHandler.post(new RemoveResultRunnable(a, fok, fmsg, rowId));
        }
    }

    private static class RemoveResultRunnable implements Runnable {
        private final MainActivity a; private final boolean ok; private final String msg; private final long rowId;
        RemoveResultRunnable(MainActivity a, boolean ok, String msg, long rowId) {
            this.a = a; this.ok = ok; this.msg = msg; this.rowId = rowId;
        }
        @Override public void run() { a.onRemoveResult(ok, msg, rowId); }
    }

    private static class DeleteRecurringHandler implements RecurringAdapter.OnDeleteClick {
        private final MainActivity a;
        DeleteRecurringHandler(MainActivity a) { this.a = a; }
        @Override public void onClick(RecurringItem item) { a.onDeleteRecurring(item); }
    }

    private static class Poller implements Runnable {
        private final MainActivity a;
        Poller(MainActivity a) { this.a = a; }
        @Override public void run() { a.pollLoop(); }
    }

    static String humanError(Exception e) {
        String cls = e.getClass().getName().toLowerCase();
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (cls.contains("unknownhost") || cls.contains("connectexception")
                || cls.contains("sockettimeout") || msg.toLowerCase().contains("unable to resolve host")
                || msg.toLowerCase().contains("failed to connect")) {
            return "אין חיבור לאינטרנט. בדוק את החיבור ונסה שוב.";
        }
        return msg.isEmpty() ? "משהו השתבש. נסה שוב." : msg;
    }
}
