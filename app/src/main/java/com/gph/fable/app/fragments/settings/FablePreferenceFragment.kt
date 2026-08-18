package com.gph.fable.app.fragments.settings

import android.os.Bundle
import android.view.View
import androidx.preference.PreferenceFragmentCompat
import com.gph.fable.shared.view.SystemBarInsets

/**
 * 工单 05：所有设置 fragment 的统一基类。
 * 列表底部避让手势条，且可滑入手势条区域（clipToPadding=false），不铺遮罩。
 */
abstract open class FablePreferenceFragment : PreferenceFragmentCompat() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SystemBarInsets.applyBottomNavigationBarInset(listView, false)
    }
}
