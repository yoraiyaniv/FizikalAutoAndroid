package il.co.arlozorovauto;

import android.database.Cursor;

/** A weekly recurring registration rule: register any class matching this
 *  weekday + start time + description, every week, automatically. */
public class RecurringItem {
    public long id;
    public int weekday;        // java.util.Calendar.DAY_OF_WEEK, 1 (Sun) .. 7 (Sat)
    public String startTime;   // "HH:mm"
    public String description; // class name to match
    public String dayLabel;    // Hebrew weekday for display
    public int contractId;
    public int purchaseId;

    public static RecurringItem fromCursor(Cursor c) {
        RecurringItem r = new RecurringItem();
        r.id = c.getLong(c.getColumnIndexOrThrow("id"));
        r.weekday = c.getInt(c.getColumnIndexOrThrow("weekday"));
        r.startTime = c.getString(c.getColumnIndexOrThrow("start_time"));
        r.description = c.getString(c.getColumnIndexOrThrow("description"));
        r.dayLabel = c.getString(c.getColumnIndexOrThrow("day_label"));
        r.contractId = c.getInt(c.getColumnIndexOrThrow("contract_id"));
        r.purchaseId = c.getInt(c.getColumnIndexOrThrow("purchase_id"));
        return r;
    }
}
