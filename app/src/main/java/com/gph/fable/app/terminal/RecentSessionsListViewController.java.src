package com.gph.fable.app.terminal;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.gph.fable.R;
import com.gph.fable.app.FableActivity;
import com.gph.fable.app.session.RecentSessionStore;
import com.gph.fable.app.session.RecentSessionStore.RecentSession;

import java.util.List;

/**
 * 最近会话列表（工单 04，ADR-0002 第 4 节方案 B）：
 * 单击一键重开（新 shell 进入记录目录），长按删除记录。
 */
public class RecentSessionsListViewController extends ArrayAdapter<RecentSession>
    implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    private final FableActivity mActivity;
    private final View mHeaderView;
    private final View mListView;

    public RecentSessionsListViewController(FableActivity activity, View listView, View headerView) {
        super(activity.getApplicationContext(), R.layout.item_recent_sessions_list);
        this.mActivity = activity;
        this.mListView = listView;
        this.mHeaderView = headerView;
        reload();
    }

    /** 重新读取持久化记录并刷新（无记录时整组隐藏）。 */
    public void reload() {
        List<RecentSession> sessions = RecentSessionStore.load(mActivity);
        clear();
        addAll(sessions);
        notifyDataSetChanged();

        boolean visible = !sessions.isEmpty();
        mHeaderView.setVisibility(visible ? View.VISIBLE : View.GONE);
        mListView.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View rowView = convertView;
        if (rowView == null) {
            LayoutInflater inflater = mActivity.getLayoutInflater();
            rowView = inflater.inflate(R.layout.item_recent_sessions_list, parent, false);
        }

        RecentSession session = getItem(position);
        if (session == null) return rowView;

        TextView dirView = rowView.findViewById(R.id.recent_session_dir);
        dirView.setText(session.workingDirectory);

        TextView timeView = rowView.findViewById(R.id.recent_session_time);
        timeView.setText(DateUtils.getRelativeTimeSpanString(mActivity, session.timestamp));

        return rowView;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        RecentSession session = getItem(position);
        if (session != null) mActivity.reopenRecentSession(session);
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        RecentSession session = getItem(position);
        if (session != null) {
            RecentSessionStore.remove(mActivity, session.workingDirectory);
            mActivity.showToast(mActivity.getString(R.string.msg_recent_session_removed), true);
            reload();
        }
        return true;
    }
}
