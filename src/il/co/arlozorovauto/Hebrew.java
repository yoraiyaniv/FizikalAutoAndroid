package il.co.arlozorovauto;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Small Hebrew date/label helpers shared across the UI and notifications. */
public class Hebrew {

    private static final String[] DAYS = {
            "", "ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת"
    }; // index by Calendar.DAY_OF_WEEK (1=Sunday .. 7=Saturday)

    /** Hebrew weekday name for a yyyy-MM-dd date, or "" if unparseable. */
    public static String dayName(String isoDate) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("Asia/Jerusalem"));
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem"));
            cal.setTime(f.parse(isoDate));
            return DAYS[cal.get(Calendar.DAY_OF_WEEK)];
        } catch (Exception e) {
            return "";
        }
    }

    /** "2026-08-22" -> "22/08"; returns the input if it doesn't match. */
    public static String ddmm(String isoDate) {
        if (isoDate == null) return "";
        String[] p = isoDate.split("-");
        return p.length == 3 ? p[2] + "/" + p[1] : isoDate;
    }

    /** A compact human label: "שבת 22/08 · 09:50 · אדם סגל · ליבה ומתיחות". */
    public static String classLabel(String isoDate, String startTime, String instructor, String description) {
        StringBuilder sb = new StringBuilder();
        String day = dayName(isoDate);
        if (!day.isEmpty()) sb.append(day).append(' ');
        sb.append(ddmm(isoDate));
        if (startTime != null && !startTime.isEmpty()) sb.append(" · ").append(startTime);
        if (instructor != null && !instructor.isEmpty()) sb.append(" · ").append(instructor);
        if (description != null && !description.isEmpty()) sb.append(" · ").append(description);
        return sb.toString();
    }
}
