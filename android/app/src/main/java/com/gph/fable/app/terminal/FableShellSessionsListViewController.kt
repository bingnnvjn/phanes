package com.gph.fable.app.terminal

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.gph.fable.R
import com.gph.fable.app.FableActivity
import com.gph.fable.core.TerminalSession
import com.gph.fable.shared.termux.shell.command.runner.terminal.FableShellSession
import com.gph.fable.shared.theme.NightMode
import com.gph.fable.shared.theme.ThemeUtils
import java.util.List

open class FableShellSessionsListViewController(
    @JvmField val mActivity: FableActivity,
    sessionList: MutableList<FableShellSession>
) : ArrayAdapter<FableShellSession>(
    mActivity.applicationContext,
    R.layout.item_terminal_sessions_list,
    sessionList
), AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    @JvmField
    val boldSpan = StyleSpan(Typeface.BOLD)

    @JvmField
    val italicSpan = StyleSpan(Typeface.ITALIC)

    @SuppressLint("SetTextI18n")
    open override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val sessionRowView = convertView ?: run {
            val inflater: LayoutInflater = mActivity.layoutInflater
            inflater.inflate(R.layout.item_terminal_sessions_list, parent, false)
        }

        val sessionTitleView: TextView = sessionRowView.findViewById(R.id.session_title)

        val sessionAtRow: TerminalSession? = getItem(position)!!.getTerminalSession()
        if (sessionAtRow == null) {
            sessionTitleView.setText(R.string.msg_session_null)
            return sessionRowView
        }

        val shouldEnableDarkTheme = ThemeUtils.shouldEnableDarkTheme(
            mActivity,
            NightMode.getAppNightMode().getName()
        )

        if (shouldEnableDarkTheme) {
            sessionTitleView.background =
                ContextCompat.getDrawable(mActivity, R.drawable.session_background_black_selected)
        }

        val name = sessionAtRow.mSessionName
        val sessionTitle = sessionAtRow.getTitle()

        val numberPart = "[${position + 1}] "
        val sessionNamePart = if (TextUtils.isEmpty(name)) "" else name
        val sessionTitlePart = if (TextUtils.isEmpty(sessionTitle)) {
            ""
        } else {
            (if (sessionNamePart.isEmpty()) "" else "\n") + sessionTitle
        }

        val fullSessionTitle = numberPart + sessionNamePart + sessionTitlePart
        val fullSessionTitleStyled = SpannableString(fullSessionTitle)
        fullSessionTitleStyled.setSpan(
            boldSpan,
            0,
            numberPart.length + sessionNamePart.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        fullSessionTitleStyled.setSpan(
            italicSpan,
            numberPart.length + sessionNamePart.length,
            fullSessionTitle.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        sessionTitleView.text = fullSessionTitleStyled

        val sessionRunning = sessionAtRow.isRunning()

        if (sessionRunning) {
            sessionTitleView.paintFlags =
                sessionTitleView.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        } else {
            sessionTitleView.paintFlags =
                sessionTitleView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        val defaultColor = if (shouldEnableDarkTheme) Color.WHITE else Color.BLACK
        val color = if (sessionRunning || sessionAtRow.getExitStatus() == 0) {
            defaultColor
        } else {
            Color.RED
        }
        sessionTitleView.setTextColor(color)
        return sessionRowView
    }

    open override fun onItemClick(
        parent: AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long
    ) {
        val clickedSession = getItem(position)!!
        mActivity.getFableTerminalSessionClient()
            .setCurrentSession(clickedSession.getTerminalSession())
        mActivity.getDrawer().closeDrawers()
    }

    open override fun onItemLongClick(
        parent: AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long
    ): Boolean {
        val selectedSession = getItem(position)!!
        mActivity.getFableTerminalSessionClient()
            .renameSession(selectedSession.getTerminalSession())
        return true
    }
}
