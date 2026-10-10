package il.co.arlozorovauto;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Turns weekly recurring rules into concrete scheduled registrations.
 *
 *  A rule stores (weekday + start time + description). The sweep looks at the
 *  upcoming schedule, finds each class that matches a rule and isn't already
 *  queued/registered, and inserts a normal scheduled item + exact alarm for
 *  it - so the existing RegistrationService machinery does the actual timed
 *  booking. Runs both from the UI (over the already-loaded schedule) and from
 *  a daily background alarm (fetching the schedule itself). */
public class RecurringSweep {

    private static final int SWEEP_DAYS = 9; // how far ahead to look each sweep

    /** Materialize rules against an already-loaded schedule (date -> classes).
     *  Returns how many new registrations were queued. */
    public static int materialize(Context ctx, java.util.Map<String, JSONArray> dayClasses) {
        DbHelper db = new DbHelper(ctx);
        List<RecurringItem> rules = db.listRecurring();
        if (rules.isEmpty()) return 0;
        int added = 0;
        for (java.util.Map.Entry<String, JSONArray> e : dayClasses.entrySet()) {
            added += scanDay(ctx, db, rules, e.getValue());
        }
        return added;
    }

    /** Background version: refresh the token, fetch the next SWEEP_DAYS of
     *  schedule, and materialize. Safe to call off the main thread. */
    public static int sweepFromNetwork(Context ctx) {
        DbHelper db = new DbHelper(ctx);
        List<RecurringItem> rules = db.listRecurring();
        if (rules.isEmpty()) return 0;

        SessionState session = SessionState.get(ctx);
        if (!session.isLoggedIn()) return 0;
        // Best-effort fresh token; if it fails the calls below just no-op.
        session.tryRefreshNow(ctx);
        if (session.accessToken == null) return 0;

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("Asia/Jerusalem"));
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem"));
        int added = 0;
        for (int d = 0; d < SWEEP_DAYS; d++) {
            String dateStr = iso.format(cal.getTime());
            cal.add(Calendar.DAY_OF_MONTH, 1);
            try {
                JSONArray classes = FizikalApi.getSchedule(dateStr, session.accessToken);
                added += scanDay(ctx, db, rules, classes);
            } catch (Exception perDay) {
                // skip a bad day
            }
        }
        return added;
    }

    private static int scanDay(Context ctx, DbHelper db, List<RecurringItem> rules, JSONArray classes) {
        int added = 0;
        for (int i = 0; i < classes.length(); i++) {
            JSONObject c = classes.optJSONObject(i);
            if (c == null) continue;
            String dateReq = c.optString("dateRequest");
            String start = c.optString("startTime");
            String desc = c.optString("description");
            int weekday = weekdayOf(dateReq);
            if (weekday == 0) continue;

            for (RecurringItem r : rules) {
                if (r.weekday == weekday && r.startTime.equals(start) && r.description.equals(desc)) {
                    int classId = c.optInt("id");
                    if (db.hasActiveScheduledForClass(classId, dateReq)) break; // already handled
                    ScheduledItem s = ScheduledItem.fromClassJson(c);
                    long id = db.addScheduled(s);
                    s.id = id;
                    AlarmScheduler.schedule(ctx, s);
                    added++;
                    break; // one rule match is enough for this class
                }
            }
        }
        return added;
    }

    /** Calendar.DAY_OF_WEEK (1..7) for a yyyy-MM-dd date, or 0 if unparseable. */
    public static int weekdayOf(String isoDate) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("Asia/Jerusalem"));
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem"));
            cal.setTime(f.parse(isoDate));
            return cal.get(Calendar.DAY_OF_WEEK);
        } catch (Exception e) {
            return 0;
        }
    }
}
