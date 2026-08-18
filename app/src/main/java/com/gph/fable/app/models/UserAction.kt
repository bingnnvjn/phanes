package com.gph.fable.app.models

enum class UserAction(private val actionName: String) {
    ABOUT("about"),
    REPORT_ISSUE_FROM_TRANSCRIPT("report issue from transcript");

    fun getName(): String = actionName
}
