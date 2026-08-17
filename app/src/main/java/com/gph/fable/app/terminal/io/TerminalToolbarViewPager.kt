package com.gph.fable.app.terminal.io

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.gph.fable.R
import com.gph.fable.app.FableActivity
import com.gph.fable.core.TerminalSession
import com.gph.fable.shared.termux.extrakeys.ExtraKeysView

class TerminalToolbarViewPager {

    class PageAdapter(
        @JvmField val mActivity: FableActivity,
        @JvmField var mSavedTextInput: String?
    ) : PagerAdapter() {

        override fun getCount(): Int = 2

        override fun isViewFromObject(view: View, obj: Any): Boolean {
            return view === obj
        }

        override fun instantiateItem(collection: ViewGroup, position: Int): Any {
            val inflater = LayoutInflater.from(mActivity)
            val layout: View
            if (position == 0) {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_extra_keys, collection, false)
                val extraKeysView = layout as ExtraKeysView
                extraKeysView.setExtraKeysViewClient(mActivity.getFableTerminalExtraKeys())
                extraKeysView.setButtonTextAllCaps(
                    mActivity.getProperties().shouldExtraKeysTextBeAllCaps()
                )
                mActivity.setExtraKeysView(extraKeysView)
                extraKeysView.reload(
                    mActivity.getFableTerminalExtraKeys().getExtraKeysInfo(),
                    mActivity.getTerminalToolbarDefaultHeight()
                )

                // apply extra keys fix if enabled in prefs
                if (mActivity.getProperties().isUsingFullScreen() &&
                    mActivity.getProperties().isUsingFullScreenWorkAround()
                ) {
                    FullScreenWorkAround.apply(mActivity)
                }
            } else {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_text_input, collection, false)
                val editText = layout.findViewById<EditText>(R.id.terminal_toolbar_text_input)

                if (mSavedTextInput != null) {
                    editText.setText(mSavedTextInput)
                    mSavedTextInput = null
                }

                editText.setOnEditorActionListener { _, _, _ ->
                    val session: TerminalSession? = mActivity.getCurrentSession()
                    if (session != null) {
                        if (session.isRunning()) {
                            var textToSend = editText.text.toString()
                            if (textToSend.isEmpty()) textToSend = "\r"
                            session.write(textToSend)
                        } else {
                            mActivity.getFableTerminalSessionClient().removeFinishedSession(session)
                        }
                        editText.setText("")
                    }
                    true
                }
            }
            collection.addView(layout)
            return layout
        }

        override fun destroyItem(collection: ViewGroup, position: Int, obj: Any) {
            collection.removeView(obj as View)
        }
    }

    class OnPageChangeListener(
        @JvmField val mActivity: FableActivity,
        @JvmField val mTerminalToolbarViewPager: ViewPager
    ) : ViewPager.SimpleOnPageChangeListener() {

        override fun onPageSelected(position: Int) {
            if (position == 0) {
                mActivity.getTerminalView().requestFocus()
            } else {
                val editText =
                    mTerminalToolbarViewPager.findViewById<EditText>(R.id.terminal_toolbar_text_input)
                editText?.requestFocus()
            }
        }
    }
}
