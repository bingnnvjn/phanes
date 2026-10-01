package com.gph.fable.app.terminal

import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView

import com.gph.fable.R
import com.gph.fable.app.FableActivity
import com.gph.fable.app.session.RecentSessionStore
import com.gph.fable.app.session.RecentSessionStore.RecentSession

/**
 * 最近会话列表（工单 04，ADR-0002 第 4 节方案 B）：
 * 单击一键重开（新 shell 进入记录目录），长按删除记录。
 */
class RecentSessionsListViewController(
    private val mActivity: FableActivity,
    private val mListView: View,
    private val mHeaderView: View
) : ArrayAdapter<RecentSession>(mActivity.applicationContext, R.layout.item_recent_sessions_list),
    AdapterView.OnItemClickListener,
    AdapterView.OnItemLongClickListener {

    init {
        reload()
    }

    /** 重新读取持久化记录并刷新（无记录时整组隐藏）。 */
    fun reload() {
        val sessions = RecentSessionStore.load(mActivity)
        clear()
        addAll(sessions)
        notifyDataSetChanged()

        val visible = sessions.isNotEmpty()
        mHeaderView.visibility = if (visible) View.VISIBLE else View.GONE
        mListView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var rowView = convertView
        if (rowView == null) {
            rowView = mActivity.layoutInflater.inflate(
                R.layout.item_recent_sessions_list,
                parent,
                false
            )
        }

        val session = getItem(position) ?: return rowView

        val dirView: TextView = rowView.findViewById(R.id.recent_session_dir)
        dirView.text = session.workingDirectory

        val timeView: TextView = rowView.findViewById(R.id.recent_session_time)
        timeView.text = DateUtils.getRelativeTimeSpanString(mActivity, session.timestamp)

        return rowView
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        getItem(position)?.let { mActivity.reopenRecentSession(it) }
    }

    override fun onItemLongClick(
        parent: AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long
    ): Boolean {
        getItem(position)?.let { session ->
            RecentSessionStore.remove(mActivity, session.workingDirectory)
            mActivity.showToast(mActivity.getString(R.string.msg_recent_session_removed), true)
            reload()
        }
        return true
    }
}
