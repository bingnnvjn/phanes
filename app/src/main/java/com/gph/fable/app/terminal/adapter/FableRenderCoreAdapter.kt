package com.gph.fable.app.terminal.adapter

import android.content.Context
import android.view.Surface
import com.gph.fable.app.FontAssets
import com.gph.fable.app.RenderCore
import com.gph.fable.app.terminal.FableDiagnostics
import com.gph.fable.core.adapter.CoreAdapter

/**
 * CoreAdapter 的新路径实现：fable-render（libghostty-vt 核心 + Rust wgpu 渲染器，
 * 工单 08-14 产物）。所有调用经 RenderCore JNI 投递到每会话独立渲染线程 mailbox
 * （工单 12：非阻塞 + 保序；selection_text / cell_size 为同步查询）。
 *
 * destroy() 之后所有调用安全忽略；会话层在 destroy 前先解除
 * {@code TerminalSession#setCoreAdapter(null)}，避免主线程排队消息打到已释放句柄。
 */
class FableRenderCoreAdapter : CoreAdapter {

    private companion object {
        private const val LOG_TAG = "FableRenderCoreAdapter"
    }

    fun interface RendererFactory {
        fun create(cols: Int, rows: Int): Long
    }

    private val mLock = Any()
    private val mRendererFactory: RendererFactory

    private var mHandle = 0L
    private var mCols: Int
    private var mRows: Int
    private var mSurface: Surface? = null
    private var mWidthPx = 0
    private var mHeightPx = 0
    private var mAttached = false
    private var mFontSizePx = 0f
    private var mPaletteActive = false
    private var mFgArgb = 0
    private var mBgArgb = 0
    private var mSelectionArgb = 0
    private var mCursorArgb = 0
    private var mAnsiArgb: IntArray? = null

    /**
     * 适配器随 TerminalSession 跨 Activity 重建存活；只能保留 application Context，
     * 供字体 assets 初始化使用，绝不能把 Activity 固定在会话上。
     */
    private val mApplicationContext: Context?

    constructor(cols: Int, rows: Int) : this(null, cols, rows)

    constructor(context: Context?, cols: Int, rows: Int) : this(
        context,
        cols,
        rows,
        RendererFactory { createCols, createRows ->
            RenderCore.rendererCreate(createCols, createRows)
        }
    )

    /** 仅包内测试缝：稳定模拟 native rendererCreate 返回 0。 */
    constructor(cols: Int, rows: Int, rendererFactory: RendererFactory) : this(
        null,
        cols,
        rows,
        rendererFactory
    )

    private constructor(
        context: Context?,
        cols: Int,
        rows: Int,
        rendererFactory: RendererFactory?
    ) {
        mApplicationContext = context?.applicationContext
        mRendererFactory = rendererFactory ?: RendererFactory { createCols, createRows ->
            RenderCore.rendererCreate(createCols, createRows)
        }
        mCols = maxOf(1, cols)
        mRows = maxOf(1, rows)
        mHandle = mRendererFactory.create(mCols, mRows)
        if (mHandle == 0L) return
        // 工单 22：assets 字体拷贝 + JNI 传路径（Rust 侧 mmap + sha256 校验）。
        FontAssets.install(mApplicationContext, mHandle)
        // 工单 26：rendererCreate 后**立即**开启 DECSET 2027（grapheme clustering），
        // 先于任何历史字节回放——否则回放内容按每码位算宽（合成 emoji 4-6 列），
        // 与 2027 的 2 列模型混排，造成"一行半/第二行覆盖"。
        val graphemeOn = "\u001b[?2027h".toByteArray(Charsets.UTF_8)
        RenderCore.rendererWrite(mHandle, graphemeOn, graphemeOn.size)
    }

    fun isValid(): Boolean {
        synchronized(mLock) {
            return mHandle != 0L
        }
    }

    override fun write(data: ByteArray, len: Int) {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        if (handle != 0L) RenderCore.rendererWrite(handle, data, len)
    }

    override fun resize(columns: Int, rows: Int) {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
            mCols = maxOf(1, columns)
            mRows = maxOf(1, rows)
        }
        if (handle != 0L) RenderCore.rendererResize(handle, mCols, mRows)
    }

    override fun scroll(delta: Int) {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        if (handle != 0L) RenderCore.rendererScroll(handle, delta)
    }

    override fun setSelection(row: Int, startCol: Int, endCol: Int) {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        if (handle != 0L) {
            RenderCore.rendererSetSelection(
                handle,
                maxOf(0, row),
                maxOf(0, startCol),
                maxOf(0, endCol)
            )
        }
    }

    override fun getSelectionText(): String {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return if (handle == 0L) "" else RenderCore.rendererSelectionText(handle)
    }

    override fun setFontSize(sizePx: Float) {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
            mFontSizePx = sizePx
        }
        if (handle != 0L) RenderCore.rendererSetFontSize(handle, sizePx)
    }

    override fun getCellSize(out: IntArray) {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        if (handle == 0L) {
            if (out.size >= 2) {
                out[0] = 0
                out[1] = 0
            }
            return
        }
        RenderCore.rendererGetCellSize(handle, out)
    }

    override fun setPalette(fgArgb: Int, bgArgb: Int, selectionArgb: Int, cursorArgb: Int) {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
            mPaletteActive = true
            mFgArgb = fgArgb
            mBgArgb = bgArgb
            mSelectionArgb = selectionArgb
            mCursorArgb = cursorArgb
        }
        if (handle != 0L) pushPalette(handle)
    }

    override fun setAnsiPalette(ansiArgb: IntArray?) {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
            mAnsiArgb = ansiArgb?.copyOf()
        }
        // 只有已 push 过主配色板时才算完整配色（避免把 ANSI 单独发给未初始化状态）。
        if (handle != 0L && mPaletteActive) pushPalette(handle)
    }

    private fun pushPalette(handle: Long) {
        val fgArgb: Int
        val bgArgb: Int
        val selectionArgb: Int
        val cursorArgb: Int
        val ansiArgb: IntArray?
        synchronized(mLock) {
            fgArgb = mFgArgb
            bgArgb = mBgArgb
            selectionArgb = mSelectionArgb
            cursorArgb = mCursorArgb
            ansiArgb = mAnsiArgb
        }
        RenderCore.rendererSetPalette16(
            handle,
            fgArgb,
            bgArgb,
            selectionArgb,
            cursorArgb,
            ansiArgb
        )
    }

    override fun resetPalette() {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
            mPaletteActive = false
        }
        if (handle != 0L) RenderCore.rendererResetPalette(handle)
    }

    override fun attach(surface: Surface, widthPx: Int, heightPx: Int) {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
            mSurface = surface
            mWidthPx = widthPx
            mHeightPx = heightPx
            mAttached = true
        }
        if (handle != 0L) {
            RenderCore.rendererAttach(handle, surface, widthPx, heightPx)
            FableDiagnostics.append("adapter.attach w=$widthPx h=$heightPx")
        }
    }

    override fun detach() {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
            mAttached = false
        }
        if (handle != 0L) {
            RenderCore.rendererDetach(handle)
            FableDiagnostics.append("adapter.detach")
        }
    }

    override fun render(widthPx: Int, heightPx: Int) {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
            mWidthPx = widthPx
            mHeightPx = heightPx
        }
        if (handle != 0L) {
            val rendered = RenderCore.rendererRender(handle, widthPx, heightPx)
            if (!rendered) {
                // 渲染线程拒绝本帧（空快照/异常帧/未附着）：写诊断文件供回传。
                FableDiagnostics.append(
                    "render=false reason=" + RenderCore.rendererLastError(handle)
                )
            }
        }
    }

    override fun forceRender(widthPx: Int, heightPx: Int) {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        if (handle != 0L) {
            val rendered = RenderCore.rendererForceRender(handle, widthPx, heightPx)
            if (!rendered) {
                FableDiagnostics.append(
                    "forceRender=false reason=" + RenderCore.rendererLastError(handle)
                )
            }
        }
    }

    override fun reset() {
        val oldHandle: Long
        val cols: Int
        val rows: Int
        val fontSizePx: Float
        val paletteActive: Boolean
        val fgArgb: Int
        val bgArgb: Int
        val selectionArgb: Int
        val cursorArgb: Int
        val ansiArgb: IntArray?
        val surface: Surface?
        val widthPx: Int
        val heightPx: Int
        val attached: Boolean
        synchronized(mLock) {
            oldHandle = mHandle
            cols = mCols
            rows = mRows
            fontSizePx = mFontSizePx
            paletteActive = mPaletteActive
            fgArgb = mFgArgb
            bgArgb = mBgArgb
            selectionArgb = mSelectionArgb
            cursorArgb = mCursorArgb
            ansiArgb = mAnsiArgb
            surface = mSurface
            widthPx = mWidthPx
            heightPx = mHeightPx
            attached = mAttached
        }
        if (oldHandle == 0L) return

        // 新核心没有单命令 reset API：重建渲染器句柄（旧线程 Quit+join），
        // 再恢复字号/配色板/Surface 附着。
        val newHandle = mRendererFactory.create(cols, rows)
        if (newHandle == 0L) return
        val installed: Boolean
        synchronized(mLock) {
            // create 在锁外，避免 native 创建阻塞 adapter 调用；发布与初始化在锁内。
            // destroy/reset 任一方改变 mHandle 后，本次 reset 丢弃新代，绝不复活会话。
            installed = mHandle == oldHandle
            if (installed) {
                mHandle = newHandle
                FontAssets.install(mApplicationContext, newHandle)
                // 工单 22：reset 后必须先恢复 2027，再接收任何迟到会话字节。
                val graphemeOn = "\u001b[?2027h".toByteArray(Charsets.UTF_8)
                RenderCore.rendererWrite(newHandle, graphemeOn, graphemeOn.size)
                if (fontSizePx > 0f) RenderCore.rendererSetFontSize(newHandle, fontSizePx)
                if (paletteActive) {
                    RenderCore.rendererSetPalette16(
                        newHandle,
                        fgArgb,
                        bgArgb,
                        selectionArgb,
                        cursorArgb,
                        ansiArgb
                    )
                }
                if (attached && surface != null && surface.isValid &&
                    widthPx > 0 && heightPx > 0
                ) {
                    // 与 detach 共享 mLock：只要 reset 仍发布新代，就把恢复的 Surface
                    // 绑定到这一代；detach 要么先发生（attached=false），要么随后
                    // 对新代 detach，绝不拿陈旧快照重新挂回旧 Surface。
                    RenderCore.rendererAttach(newHandle, surface, widthPx, heightPx)
                }
            }
        }
        if (!installed) {
            RenderCore.rendererDestroy(newHandle)
            return
        }
        RenderCore.rendererDestroy(oldHandle)
    }

    override fun getCursorPosition(out: IntArray?): Boolean {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        if (handle == 0L || out == null || out.size < 2) return false
        val present = RenderCore.rendererGetCursor(handle, out)
        return present == 1 && out[0] >= 0 && out[1] >= 0
    }

    override fun getTitle(): String {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return if (handle == 0L) "" else RenderCore.rendererGetTitle(handle)
    }

    override fun consumeTitleChanged(): Boolean {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return handle != 0L && RenderCore.rendererConsumeTitleChanged(handle)
    }

    override fun consumeBell(): Boolean {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return handle != 0L && RenderCore.rendererConsumeBell(handle)
    }

    override fun getModeAlternateScreen(): Boolean {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return handle != 0L && RenderCore.rendererGetModeAltScreen(handle)
    }

    override fun getModeMouseTracking(): Boolean {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return handle != 0L && RenderCore.rendererGetModeMouseTracking(handle)
    }

    override fun getModeCursorVisible(): Boolean {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return handle != 0L && RenderCore.rendererGetModeCursorVisible(handle)
    }

    override fun getModeCursorBlink(): Boolean {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return handle != 0L && RenderCore.rendererGetModeCursorBlink(handle)
    }

    override fun getModeCursorKeysApplication(): Boolean {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return handle != 0L && RenderCore.rendererGetModeCursorKeysApplication(handle)
    }

    override fun getModeKeypadApplication(): Boolean {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return handle != 0L && RenderCore.rendererGetModeKeypadApplication(handle)
    }

    override fun getModeBracketedPaste(): Boolean {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return handle != 0L && RenderCore.rendererGetModeBracketedPaste(handle)
    }

    override fun setCursorBlinkState(cursorVisible: Boolean) {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        if (handle != 0L) RenderCore.rendererSetCursorBlinkState(handle, cursorVisible)
    }

    override fun getScrollbackRows(): Int {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return if (handle == 0L) 0 else RenderCore.rendererGetScrollbackRows(handle)
    }

    override fun getText(row: Int, startCol: Int, endCol: Int): String {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return if (handle == 0L) "" else RenderCore.rendererGetText(handle, row, startCol, endCol)
    }

    override fun getWordBoundsAt(column: Int, externalRow: Int): IntArray? {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return if (handle == 0L) null
        else RenderCore.rendererGetWordBoundsAt(handle, column, externalRow)
    }

    override fun getWordAt(column: Int, externalRow: Int): String {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return if (handle == 0L) "" else RenderCore.rendererGetWordAt(handle, column, externalRow)
    }

    override fun getModeMouseSgr(): Boolean {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return handle != 0L && RenderCore.rendererGetModeMouseSgr(handle)
    }

    override fun getModeMouseButtonEvent(): Boolean {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return handle != 0L && RenderCore.rendererGetModeMouseButtonEvent(handle)
    }

    override fun getTranscriptText(linesJoined: Boolean, trim: Boolean): String {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
        }
        return if (handle == 0L) "" else {
            RenderCore.rendererGetTranscriptText(handle, linesJoined, trim)
        }
    }

    override fun destroy() {
        val handle: Long
        synchronized(mLock) {
            handle = mHandle
            mHandle = 0L
            mSurface = null
            mAttached = false
        }
        if (handle != 0L) {
            RenderCore.rendererDetach(handle)
            RenderCore.rendererDestroy(handle)
        }
    }

    override fun supportsSelectionText(): Boolean = true

    override fun supportsFontSize(): Boolean = true

    override fun supportsPalette(): Boolean = true

    override fun supportsScrollback(): Boolean = true
}
