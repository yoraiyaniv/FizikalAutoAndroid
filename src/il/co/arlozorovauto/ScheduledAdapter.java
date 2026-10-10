package il.co.arlozorovauto;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

public class ScheduledAdapter extends BaseAdapter {

    public interface OnCancelClick {
        void onClick(ScheduledItem item);
    }

    private final Context ctx;
    private final List<ScheduledItem> items;
    private final OnCancelClick listener;

    public ScheduledAdapter(Context ctx, List<ScheduledItem> items, OnCancelClick listener) {
        this.ctx = ctx;
        this.items = items;
        this.listener = listener;
    }

    @Override public int getCount() { return items.size(); }

    // See ClassAdapter for why this avoids a covariant-return override.
    @Override public Object getItem(int position) { return getScheduledAt(position); }

    private ScheduledItem getScheduledAt(int position) { return items.get(position); }

    @Override public long getItemId(int position) { return items.get(position).id; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView != null ? convertView :
                LayoutInflater.from(ctx).inflate(R.layout.row_scheduled, parent, false);
        final ScheduledItem s = getScheduledAt(position);

        TextView title = v.findViewById(R.id.row_title);
        TextView subtitle = v.findViewById(R.id.row_subtitle);
        TextView status = v.findViewById(R.id.row_status);
        TextView message = v.findViewById(R.id.row_message);
        Button cancel = v.findViewById(R.id.row_cancel_button);

        String day = Hebrew.dayName(s.classDate);
        title.setText((day.isEmpty() ? "" : day + " ") + Hebrew.ddmm(s.classDate)
                + "  " + s.startTime + "-" + s.endTime + "  " + s.description);
        String occ = (s.totalParticipants != null ? s.totalParticipants : "-") + "/" +
                (s.maxParticipants != null ? s.maxParticipants : "-");
        subtitle.setText((s.instructorName != null ? s.instructorName + "  ·  " : "") + occ +
                "  ·  נפתח " + (s.registrationStart != null ? s.registrationStart.replace("T", " ") : ""));
        status.setText(hebrewStatus(s.status));
        applyBadgeStyle(status, s.status);
        message.setText(s.message != null ? s.message : "");

        boolean pending = "pending".equals(s.status);
        boolean registered = "registered".equals(s.status) || "waitlisted".equals(s.status);
        if (pending) {
            cancel.setVisibility(View.VISIBLE);
            cancel.setText("ביטול");           // just drop it from our queue
        } else if (registered) {
            cancel.setVisibility(View.VISIBLE);
            cancel.setText("בטל הרשמה");        // actually unregister from the class
        } else {
            cancel.setVisibility(View.GONE);
        }
        cancel.setOnClickListener(new CancelClickListener(listener, s));

        return v;
    }

    private String hebrewStatus(String state) {
        switch (state) {
            case "registered": return "נרשם";
            case "waitlisted": return "רשימת המתנה";
            case "failed": return "נכשל";
            case "cancelled": return "בוטל";
            case "pending": return "ממתין";
            default: return state;
        }
    }

    private void applyBadgeStyle(TextView status, String state) {
        int bg, color;
        switch (state) {
            case "registered":
                bg = R.drawable.badge_blue; color = R.color.accent; break;
            case "waitlisted":
                bg = R.drawable.badge_orange; color = R.color.warn; break;
            case "failed":
            case "cancelled":
                bg = R.drawable.badge_red; color = R.color.err; break;
            case "pending":
            default:
                bg = R.drawable.badge_gray; color = R.color.text_muted; break;
        }
        status.setBackgroundResource(bg);
        status.setTextColor(ctx.getResources().getColor(color));
    }

    private static class CancelClickListener implements View.OnClickListener {
        private final OnCancelClick listener;
        private final ScheduledItem item;

        CancelClickListener(OnCancelClick listener, ScheduledItem item) {
            this.listener = listener;
            this.item = item;
        }

        @Override public void onClick(View view) {
            listener.onClick(item);
        }
    }
}
