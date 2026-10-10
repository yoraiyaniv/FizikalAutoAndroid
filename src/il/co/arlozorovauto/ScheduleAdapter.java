package il.co.arlozorovauto;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;
import java.util.Set;

/** Multi-day schedule list: a flat list mixing day-header rows (String) and
 *  class rows (JSONObject), grouped by day - similar to the real app's
 *  scrollable schedule. Classes already queued for auto-registration show a
 *  disabled "Scheduled" state instead of the register button. */
public class ScheduleAdapter extends BaseAdapter {

    public interface OnRegisterClick {
        void onClick(JSONObject classObj);
    }
    public interface OnUnregisterClick {
        void onClick(JSONObject classObj);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CLASS = 1;

    private final Context ctx;
    private final List<Object> rows;          // String header or JSONObject class
    private final Set<String> scheduledKeys;  // "classId|dateRequest" already queued
    private final OnRegisterClick listener;
    private final OnUnregisterClick unregisterListener;

    public ScheduleAdapter(Context ctx, List<Object> rows, Set<String> scheduledKeys,
                           OnRegisterClick listener, OnUnregisterClick unregisterListener) {
        this.ctx = ctx;
        this.rows = rows;
        this.scheduledKeys = scheduledKeys;
        this.listener = listener;
        this.unregisterListener = unregisterListener;
    }

    @Override public int getCount() { return rows.size(); }
    @Override public Object getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int position) {
        return (rows.get(position) instanceof String) ? TYPE_HEADER : TYPE_CLASS;
    }

    @Override public boolean isEnabled(int position) { return false; } // rows aren't list-selectable

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Object row = rows.get(position);
        if (row instanceof String) {
            return headerView((String) row, convertView, parent);
        }
        return classView((JSONObject) row, convertView, parent);
    }

    private View headerView(String text, View convertView, ViewGroup parent) {
        View v = (convertView != null) ? convertView :
                LayoutInflater.from(ctx).inflate(R.layout.row_day_header, parent, false);
        ((TextView) v.findViewById(R.id.day_header_text)).setText(text);
        return v;
    }

    private View classView(JSONObject c, View convertView, ViewGroup parent) {
        View v = (convertView != null && convertView.findViewById(R.id.row_action_button) != null)
                ? convertView
                : LayoutInflater.from(ctx).inflate(R.layout.row_class, parent, false);

        TextView title = v.findViewById(R.id.row_title);
        TextView subtitle = v.findViewById(R.id.row_subtitle);
        TextView status = v.findViewById(R.id.row_status);
        Button action = v.findViewById(R.id.row_action_button);

        title.setText(c.optString("startTime") + "-" + c.optString("endTime") + "  " + c.optString("description"));
        String instructor = c.optString("instructorName", "");
        subtitle.setText((instructor.isEmpty() ? "" : instructor + "  ·  ") +
                c.optInt("totalParticipants") + "/" + c.optInt("maxParticipants"));

        JSONObject actionObj = c.optJSONObject("action");
        String actionName = actionObj != null ? actionObj.optString("name") : "";
        // Prefer a known label; else fall back to the server's own action text
        // (e.g. "השיעור מלא"); hide the badge entirely if there's nothing.
        String label = describeAction(actionName);
        if (label.isEmpty() && actionObj != null) label = actionObj.optString("text", "");
        if (label.isEmpty()) {
            status.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.VISIBLE);
            status.setText(label);
            applyBadgeStyle(status, actionName);
        }

        String key = c.optInt("id") + "|" + c.optString("dateRequest");
        boolean alreadyScheduled = scheduledKeys.contains(key);
        boolean schedulable = actionName.equals(FizikalApi.ACTION_NOT_YET_OPEN)
                || actionName.equals(FizikalApi.ACTION_OPEN)
                || actionName.equals(FizikalApi.ACTION_WAITLIST);

        boolean alreadyRegistered = FizikalApi.ACTION_ALREADY_REGISTERED.equals(actionName);
        if (alreadyRegistered) {
            // Currently registered - offer to cancel the registration.
            action.setVisibility(View.VISIBLE);
            action.setText("בטל הרשמה");
            action.setEnabled(true);
            action.setAlpha(1f);
            action.setBackgroundResource(R.drawable.bg_button_danger);
            action.setOnClickListener(new UnregisterClickListener(unregisterListener, c));
        } else if (alreadyScheduled) {
            action.setVisibility(View.VISIBLE);
            action.setText("מתוזמן ✓");
            action.setEnabled(false);
            action.setAlpha(0.5f);
            action.setBackgroundResource(R.drawable.bg_button_secondary);
            action.setOnClickListener(null);
        } else if (schedulable) {
            action.setVisibility(View.VISIBLE);
            action.setText("תזמן הרשמה");
            action.setEnabled(true);
            action.setAlpha(1f);
            action.setBackgroundResource(R.drawable.bg_button_primary);
            action.setOnClickListener(new RegisterClickListener(listener, c));
        } else {
            // Not bookable (e.g. class is full with no waitlist) - no button.
            action.setVisibility(View.GONE);
            action.setOnClickListener(null);
        }

        return v;
    }

    private void applyBadgeStyle(TextView status, String actionName) {
        if (FizikalApi.ACTION_OPEN.equals(actionName)) {
            status.setBackgroundResource(R.drawable.badge_green);
            status.setTextColor(ctx.getResources().getColor(R.color.ok));
        } else if (FizikalApi.ACTION_WAITLIST.equals(actionName)) {
            status.setBackgroundResource(R.drawable.badge_orange);
            status.setTextColor(ctx.getResources().getColor(R.color.warn));
        } else if (FizikalApi.ACTION_ALREADY_REGISTERED.equals(actionName)) {
            status.setBackgroundResource(R.drawable.badge_blue);
            status.setTextColor(ctx.getResources().getColor(R.color.accent));
        } else {
            status.setBackgroundResource(R.drawable.badge_gray);
            status.setTextColor(ctx.getResources().getColor(R.color.text_muted));
        }
    }

    private String describeAction(String name) {
        if (FizikalApi.ACTION_OPEN.equals(name)) return "פתוח להרשמה";
        if (FizikalApi.ACTION_WAITLIST.equals(name)) return "מלא · רשימת המתנה";
        if (FizikalApi.ACTION_NOT_YET_OPEN.equals(name)) return "ייפתח בהמשך";
        if (FizikalApi.ACTION_ALREADY_REGISTERED.equals(name)) return "רשום";
        return name;
    }

    private static class RegisterClickListener implements View.OnClickListener {
        private final OnRegisterClick listener;
        private final JSONObject classObj;
        RegisterClickListener(OnRegisterClick listener, JSONObject classObj) {
            this.listener = listener; this.classObj = classObj;
        }
        @Override public void onClick(View view) { listener.onClick(classObj); }
    }

    private static class UnregisterClickListener implements View.OnClickListener {
        private final OnUnregisterClick listener;
        private final JSONObject classObj;
        UnregisterClickListener(OnUnregisterClick listener, JSONObject classObj) {
            this.listener = listener; this.classObj = classObj;
        }
        @Override public void onClick(View view) { listener.onClick(classObj); }
    }
}
