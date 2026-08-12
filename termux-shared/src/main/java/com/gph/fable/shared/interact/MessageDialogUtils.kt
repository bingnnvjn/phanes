package com.gph.fable.shared.interact

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.gph.fable.shared.R
import com.gph.fable.shared.logger.Logger

object MessageDialogUtils {

    /**
     * Show a message in a dialog
     */
    @JvmStatic
    fun showMessage(context: Context, titleText: String?, messageText: String?, onDismiss: DialogInterface.OnDismissListener?) {
        showMessage(context, titleText, messageText, null, null, null, null, onDismiss)
    }

    /**
     * Show a message in a dialog
     */
    @JvmStatic
    fun showMessage(context: Context, titleText: String?, messageText: String?,
                    positiveText: String?,
                    onPositiveButton: DialogInterface.OnClickListener?,
                    negativeText: String?,
                    onNegativeButton: DialogInterface.OnClickListener?,
                    onDismiss: DialogInterface.OnDismissListener?) {

        val builder = AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)

        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.dialog_show_message, null)
        if (view != null) {
            builder.setView(view)

            val titleView = view.findViewById<TextView>(R.id.dialog_title)
            if (titleView != null)
                titleView.text = titleText

            val messageView = view.findViewById<TextView>(R.id.dialog_message)
            if (messageView != null)
                messageView.text = messageText
        }

        var positiveText = positiveText
        if (positiveText == null)
            positiveText = context.getString(android.R.string.ok)
        builder.setPositiveButton(positiveText, onPositiveButton)

        if (negativeText != null)
            builder.setNegativeButton(negativeText, onNegativeButton)

        if (onDismiss != null)
            builder.setOnDismissListener(onDismiss)

        val dialog = builder.create()

        dialog.setOnShowListener { dialogInterface ->
            Logger.logError("dialog")
            var button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            if (button != null)
                button.setTextColor(Color.BLACK)
            button = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            if (button != null)
                button.setTextColor(Color.BLACK)
        }

        dialog.show()
    }

    @JvmStatic
    fun exitAppWithErrorMessage(context: Context, titleText: String?, messageText: String?) {
        showMessage(context, titleText, messageText) { dialog -> System.exit(0) }
    }
}
