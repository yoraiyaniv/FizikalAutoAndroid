package il.co.arlozorovauto;

import android.database.Cursor;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

public class ScheduledItem {
    public long id;
    public int classId;
    public String classDate;
    public String description;
    public String instructorName;
    public String startTime;
    public String endTime;
    public String registrationStart;
    public int contractId;
    public int purchaseId;
    public String status;
    public String message;
    public String registrationId;
    public Integer totalParticipants;
    public Integer maxParticipants;
    public int attempts;
    public Long openedAt;

    /** Builds a ScheduledItem from a class object out of the schedule API. */
    public static ScheduledItem fromClassJson(JSONObject c) {
        ScheduledItem s = new ScheduledItem();
        s.classId = c.optInt("id");
        s.classDate = c.optString("dateRequest");
        s.description = c.optString("description");
        s.instructorName = c.optString("instructorName", null);
        s.startTime = c.optString("startTime");
        s.endTime = c.optString("endTime");
        s.registrationStart = c.optString("registrationStart");
        s.contractId = c.optInt("contractId", 0);
        s.purchaseId = c.optInt("purchaseId", 0);
        s.totalParticipants = c.optInt("totalParticipants");
        s.maxParticipants = c.optInt("maxParticipants");
        // Present on classes the server reports as registered - needed to cancel.
        if (c.has("registrationId")) s.registrationId = String.valueOf(c.optInt("registrationId"));
        return s;
    }

    public static ScheduledItem fromCursor(Cursor c) {
        ScheduledItem s = new ScheduledItem();
        s.id = c.getLong(c.getColumnIndexOrThrow("id"));
        s.classId = c.getInt(c.getColumnIndexOrThrow("class_id"));
        s.classDate = c.getString(c.getColumnIndexOrThrow("class_date"));
        s.description = c.getString(c.getColumnIndexOrThrow("description"));
        s.instructorName = c.getString(c.getColumnIndexOrThrow("instructor_name"));
        s.startTime = c.getString(c.getColumnIndexOrThrow("start_time"));
        s.endTime = c.getString(c.getColumnIndexOrThrow("end_time"));
        s.registrationStart = c.getString(c.getColumnIndexOrThrow("registration_start"));
        s.contractId = c.getInt(c.getColumnIndexOrThrow("contract_id"));
        s.purchaseId = c.getInt(c.getColumnIndexOrThrow("purchase_id"));
        s.status = c.getString(c.getColumnIndexOrThrow("status"));
        s.message = c.getString(c.getColumnIndexOrThrow("message"));
        s.registrationId = c.getString(c.getColumnIndexOrThrow("registration_id"));
        int totalIdx = c.getColumnIndexOrThrow("total_participants");
        s.totalParticipants = c.isNull(totalIdx) ? null : c.getInt(totalIdx);
        int maxIdx = c.getColumnIndexOrThrow("max_participants");
        s.maxParticipants = c.isNull(maxIdx) ? null : c.getInt(maxIdx);
        s.attempts = c.getInt(c.getColumnIndexOrThrow("attempts"));
        int openedIdx = c.getColumnIndexOrThrow("opened_at");
        s.openedAt = c.isNull(openedIdx) ? null : c.getLong(openedIdx);
        return s;
    }

    /** True once this class's time slot has already ended (Israel local time),
     *  so it no longer belongs in the scheduled/upcoming display. Falls back to
     *  the start time, then the date-end, if a field is missing/unparseable. */
    public boolean isPast() {
        long endMillis = classDateTimeMillis(classDate, endTime);
        if (endMillis == 0) endMillis = classDateTimeMillis(classDate, startTime);
        if (endMillis == 0) endMillis = classDateTimeMillis(classDate, "23:59");
        return endMillis != 0 && endMillis < System.currentTimeMillis();
    }

    private static long classDateTimeMillis(String date, String hhmm) {
        if (date == null || hhmm == null || date.isEmpty() || hhmm.isEmpty()) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("Asia/Jerusalem"));
            return f.parse(date + " " + hhmm).getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}
