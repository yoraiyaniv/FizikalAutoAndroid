package il.co.arlozorovauto;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Local persistence: the logged-in session (refresh token survives app
 *  restarts, same as staying logged into the real app) and the list of
 *  scheduled auto-registrations.
 *
 *  NOTE ON SECURITY: refresh_token is stored in plaintext in this app's
 *  private database. That's normal-ish for a personal tool (the real app
 *  does something similar in its own storage), but be aware this device's
 *  storage now holds a live credential for your Fizikal account. */
public class DbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "fizikal.db";
    private static final int DB_VERSION = 2;

    private final Context appContext;

    public DbHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
        this.appContext = ctx.getApplicationContext();
    }

    /** Every write below that changes something the UI shows fires this, so
     *  MainActivity can refresh immediately - whether the write came from
     *  the app itself, a background service, or a notification action -
     *  the same immediacy as cancelling a class from inside the app. */
    private void notifyDataChanged() {
        appContext.sendBroadcast(new Intent(AppEvents.ACTION_DATA_CHANGED));
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE session (id INTEGER PRIMARY KEY CHECK (id = 1), phone TEXT, refresh_token TEXT)");
        db.execSQL("CREATE TABLE scheduled (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "class_id INTEGER NOT NULL," +
                "class_date TEXT NOT NULL," +
                "description TEXT," +
                "instructor_name TEXT," +
                "start_time TEXT," +
                "end_time TEXT," +
                "registration_start TEXT," +
                "contract_id INTEGER DEFAULT 0," +
                "purchase_id INTEGER DEFAULT 0," +
                "status TEXT NOT NULL DEFAULT 'pending'," +
                "message TEXT," +
                "registration_id TEXT," +
                "total_participants INTEGER," +
                "max_participants INTEGER," +
                "attempts INTEGER DEFAULT 0," +
                "opened_at INTEGER" +
                ")");
        createRecurringTable(db);
    }

    private void createRecurringTable(SQLiteDatabase db) {
        // A weekly rule: register any class matching (weekday + start_time +
        // description) each week. class_id is NOT stored - it changes weekly;
        // the sweep resolves the concrete class for each upcoming week.
        db.execSQL("CREATE TABLE IF NOT EXISTS recurring (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "weekday INTEGER NOT NULL," +      // Calendar.DAY_OF_WEEK 1..7
                "start_time TEXT NOT NULL," +
                "description TEXT NOT NULL," +
                "day_label TEXT," +                // Hebrew weekday for display
                "contract_id INTEGER DEFAULT 0," +
                "purchase_id INTEGER DEFAULT 0," +
                "created_at TEXT NOT NULL DEFAULT (datetime('now'))" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createRecurringTable(db);
    }

    public void saveSession(String phone, String refreshToken) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM session");
        ContentValues cv = new ContentValues();
        cv.put("id", 1);
        cv.put("phone", phone);
        cv.put("refresh_token", refreshToken);
        db.insert("session", null, cv);
    }

    public void clearSession() {
        getWritableDatabase().execSQL("DELETE FROM session");
    }

    /** Returns {phone, refreshToken} or {null, null}. */
    public String[] loadSession() {
        Cursor c = getReadableDatabase().query("session", new String[]{"phone", "refresh_token"},
                "id = 1", null, null, null, null);
        try {
            if (c.moveToFirst()) {
                return new String[]{c.getString(0), c.getString(1)};
            }
            return new String[]{null, null};
        } finally {
            c.close();
        }
    }

    public long addScheduled(ScheduledItem s) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("class_id", s.classId);
        cv.put("class_date", s.classDate);
        cv.put("description", s.description);
        cv.put("instructor_name", s.instructorName);
        cv.put("start_time", s.startTime);
        cv.put("end_time", s.endTime);
        cv.put("registration_start", s.registrationStart);
        cv.put("contract_id", s.contractId);
        cv.put("purchase_id", s.purchaseId);
        cv.put("total_participants", s.totalParticipants);
        cv.put("max_participants", s.maxParticipants);
        cv.put("status", "pending");
        long id = db.insert("scheduled", null, cv);
        notifyDataChanged();
        return id;
    }

    public void updateStatus(long id, String status, String message) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        cv.put("message", message);
        db.update("scheduled", cv, "id = ?", new String[]{String.valueOf(id)});
        notifyDataChanged();
    }

    public void updateOccupancy(long id, int total, int max) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("total_participants", total);
        cv.put("max_participants", max);
        db.update("scheduled", cv, "id = ?", new String[]{String.valueOf(id)});
        notifyDataChanged();
    }

    public void updateMessage(long id, String message) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("message", message);
        db.update("scheduled", cv, "id = ?", new String[]{String.valueOf(id)});
        notifyDataChanged();
    }

    public void updateRegistrationResult(long id, String status, String message, String registrationId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        cv.put("message", message);
        cv.put("registration_id", registrationId);
        db.update("scheduled", cv, "id = ?", new String[]{String.valueOf(id)});
        notifyDataChanged();
    }

    public void incrementAttempts(long id) {
        getWritableDatabase().execSQL("UPDATE scheduled SET attempts = attempts + 1 WHERE id = ?",
                new Object[]{id});
        notifyDataChanged();
    }

    public void markOpenedNow(long id) {
        getWritableDatabase().execSQL(
                "UPDATE scheduled SET opened_at = ? WHERE id = ? AND opened_at IS NULL",
                new Object[]{System.currentTimeMillis(), id});
        notifyDataChanged();
    }

    public void cancelScheduled(long id) {
        getWritableDatabase().execSQL(
                "UPDATE scheduled SET status = 'cancelled' WHERE id = ? AND status = 'pending'",
                new Object[]{id});
        notifyDataChanged();
    }

    /** Resets a failed row back to pending so RegistrationService will race
     *  for it again right away - used by the "נסה שוב" notification action. */
    public void retryScheduled(long id) {
        getWritableDatabase().execSQL(
                "UPDATE scheduled SET status = 'pending', message = 'מנסה שוב...' WHERE id = ? AND status = 'failed'",
                new Object[]{id});
        notifyDataChanged();
    }

    public ScheduledItem getScheduledById(long id) {
        Cursor c = getReadableDatabase().query("scheduled", null, "id = ?",
                new String[]{String.valueOf(id)}, null, null, null);
        try {
            if (c.moveToFirst()) return ScheduledItem.fromCursor(c);
            return null;
        } finally {
            c.close();
        }
    }

    public List<ScheduledItem> listAllScheduled() {
        List<ScheduledItem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("scheduled", null, null, null, null, null,
                "registration_start ASC");
        try {
            while (c.moveToNext()) out.add(ScheduledItem.fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    public List<ScheduledItem> listPending() {
        List<ScheduledItem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("scheduled", null, "status = 'pending'", null, null, null,
                "registration_start ASC");
        try {
            while (c.moveToNext()) out.add(ScheduledItem.fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** True if there's already a scheduled row for this class in a state we
     *  shouldn't disturb (queued or already secured) - used by the recurring
     *  sweep to avoid double-booking the same weekly occurrence. */
    public boolean hasActiveScheduledForClass(int classId, String classDate) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM scheduled WHERE class_id = ? AND class_date = ? " +
                        "AND status IN ('pending','registered','waitlisted') LIMIT 1",
                new String[]{String.valueOf(classId), classDate});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    // ---------------- recurring rules ----------------

    public long addRecurring(RecurringItem r) {
        ContentValues cv = new ContentValues();
        cv.put("weekday", r.weekday);
        cv.put("start_time", r.startTime);
        cv.put("description", r.description);
        cv.put("day_label", r.dayLabel);
        cv.put("contract_id", r.contractId);
        cv.put("purchase_id", r.purchaseId);
        long id = getWritableDatabase().insert("recurring", null, cv);
        notifyDataChanged();
        return id;
    }

    public void deleteRecurring(long id) {
        getWritableDatabase().delete("recurring", "id = ?", new String[]{String.valueOf(id)});
        notifyDataChanged();
    }

    /** True if a rule already exists for this weekday+time+description. */
    public boolean hasRecurring(int weekday, String startTime, String description) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM recurring WHERE weekday = ? AND start_time = ? AND description = ? LIMIT 1",
                new String[]{String.valueOf(weekday), startTime, description});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    public List<RecurringItem> listRecurring() {
        List<RecurringItem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("recurring", null, null, null, null, null,
                "weekday ASC, start_time ASC");
        try {
            while (c.moveToNext()) out.add(RecurringItem.fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }
}
