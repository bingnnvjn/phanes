package com.gph.fable.app.terminal

import com.gph.fable.app.FableActivity

/** Kotlin facade for the terminal view client. */
class FableTerminalViewClient(
    activity: FableActivity,
    sessionClient: FableTerminalSessionActivityClient
) : FableTerminalViewClientJava(activity, sessionClient)
