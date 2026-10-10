package il.co.arlozorovauto;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

/** Lists weekly recurring rules (reusing the scheduled-row layout). Each row
 *  shows the weekday + time + class name and a "remove" button. */
public class RecurringAdapter extends BaseAdapter {

    public interface OnDeleteClick { void onClick(RecurringItem item); }

    private final Context ctx;
    private final List<RecurringItem> items;
    private final OnDeleteClick listener;

    public RecurringAdapter(Context ctx, List<RecurringItem> items, OnDeleteClick listener) {
        this.ctx = ctx; this.items = items; this.listener = listener;
    }

    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return items.get(position).id; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView != null ? convertView :
                LayoutInflater.from(ctx).inflate(R.layout.row_scheduled, parent, false);
        final RecurringItem r = items.get(position);

        TextView title = v.findViewById(R.id.row_title);
        TextView subtitle = v.findViewById(R.id.row_subtitle);
        TextView status = v.findViewById(R.id.row_status);
        TextView message = v.findViewById(R.id.row_message);
        Button remove = v.findViewById(R.id.row_cancel_button);

        title.setText((r.dayLabel != null ? r.dayLabel : "") + "  " + r.startTime + "  " + r.description);
        subtitle.setText("כל שבוע · נרשם אוטומטית");
        status.setText("קבוע");
        status.setBackgroundResource(R.drawable.badge_blue);
        status.setTextColor(ctx.getResources().getColor(R.color.accent));
        message.setText("");
        remove.setText("הסר");
        remove.setVisibility(View.VISIBLE);
        remove.setOnClickListener(new DeleteClickListener(listener, r));
        return v;
    }

    private static class DeleteClickListener implements View.OnClickListener {
        private final OnDeleteClick listener;
        private final RecurringItem item;
        DeleteClickListener(OnDeleteClick listener, RecurringItem item) {
            this.listener = listener; this.item = item;
        }
        @Override public void onClick(View view) { listener.onClick(item); }
    }
}
