package com.gph.fable.app.terminal

import android.content.Context
import android.util.AttributeSet

/** Kotlin XML entry point for the activity root view. */
class FableActivityRootView : FableActivityRootViewJava {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
}
