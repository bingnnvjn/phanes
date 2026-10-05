package com.gph.fable.view.textselection

/**
 * 执行浮条动作，把回调顺序抽成可测逻辑（与工单 04 的 ActionMode 回调等价）：
 *
 * - 复制：先取当前选中文本（结束选择会清空选区），写剪贴板，再结束选择；
 * - 粘贴：先结束选择，再把剪贴板内容送进会话。
 */
class TextSelectionActionRunner(
    private val selectedText: () -> String?,
    private val copyToClipboard: (String) -> Unit,
    private val stopSelection: () -> Unit,
    private val pasteFromClipboard: () -> Unit
) {
    fun run(action: TextSelectionAction) {
        when (action) {
            TextSelectionAction.COPY -> {
                val text = selectedText() ?: ""
                copyToClipboard(text)
                stopSelection()
            }
            TextSelectionAction.PASTE -> {
                stopSelection()
                pasteFromClipboard()
            }
        }
    }
}
