package com.gph.fable.app.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.gph.fable.app.terminal.adapter.FableRenderCoreAdapter
import com.gph.fable.core.TerminalSession
import com.gph.fable.core.adapter.CoreAdapter
import com.gph.fable.view.FableInputTerminalView
import java.util.HashMap

/**
 * 主终端 Fable 渲染容器（工单 15）：每会话一个 fable-render 适配器 + 一个
 * SurfaceView（参照工单 10/12 探针结构），内嵌 [FableInputTerminalView]
 * 继续承担 IME/手势/长按选择/上下文菜单输入。
 *
 * 渲染器生命周期挂在会话上（[TerminalSession.setCoreAdapter]）：
 * - 旋转/Activity 重建：容器只释放视图与 Surface，适配器留在会话，字节不丢；
 * - 会话关闭：onSessionRemoved 解除缝引用并销毁适配器；
 * - 会话进程退出：TerminalSession 退出回调销毁适配器并解除缝引用（工单 15/26）。
 */
class FableTerminalView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    private companion object {
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())

        @JvmStatic
        private fun isRenderable(adapter: CoreAdapter?): Boolean {
            return adapter is FableRenderCoreAdapter && adapter.isValid()
        }
    }

    /** watchdog：surface 静默丢失（无回调）时强制恢复 + 心跳确认进程存活。 */
    private val mSurfaceWatchdog: Runnable = object : Runnable {
        private var mTick = 0

        override fun run() {
            if (mDetached || mCurrentRender == null) return
            mTick++
            val render = mCurrentRender ?: return
            val surfaceView = render.surfaceView
            if (render.visible && surfaceView != null && !render.surfaceReady &&
                !render.hideRequested && !render.autoRecreating && isAttachedToWindow
            ) {
                FableDiagnostics.append("watchdog: surface missing, forcing recovery")
                render.autoRecreateSurface()
            }
            if (mTick % 10 == 0) {
                FableDiagnostics.append(
                    "watchdog alive surfaceReady=${render.surfaceReady}" +
                        " visible=${render.visible}"
                )
            }
            MAIN_HANDLER.postDelayed(this, 500)
        }
    }

    private val mSessionRenders: MutableMap<TerminalSession, SessionRender> = HashMap()
    private var mInputView: FableInputTerminalView? = null
    private var mCurrentSession: TerminalSession? = null
    private var mCurrentRender: SessionRender? = null
    private var mDetached = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mDetached = false
        MAIN_HANDLER.post(mSurfaceWatchdog)
    }

    fun setInputView(inputView: FableInputTerminalView?) {
        mInputView = inputView
    }

    fun getInputView(): FableInputTerminalView? = mInputView

    /**
     * 切换当前会话到 [session] 并接好对应渲染器/SurfaceView。
     * 返回是否实际发生了会话切换（与旧 TerminalView.attachSession 语义一致）。
     */
    fun attachSession(session: TerminalSession?): Boolean {
        if (session == null || mDetached || session === mCurrentSession) return false
        val inputView = mInputView ?: return false

        FableDiagnostics.append("attachSession switch")
        mCurrentRender?.hide()

        var render = mSessionRenders[session]
        if (render == null) {
            var adapter = session.getCoreAdapter()
            if (adapter == null) {
                val fresh = FableRenderCoreAdapter(context, 80, 24)
                if (fresh.isValid()) {
                    session.setCoreAdapter(fresh)
                    adapter = fresh
                }
            }
            render = SessionRender(this, session, adapter)
            mSessionRenders[session] = render
        }

        mCurrentSession = session
        mCurrentRender = render

        val changed = inputView.attachSession(session)
        // 先 attachSession（输入视图指向新会话）再绑缝，避免 setCoreAdapter 的
        // updateSize 把还挂着的旧会话 PTY 临时 resize。
        val adapter = session.getCoreAdapter()
        // 工单 31：旧路径（TerminalView 自绘）已删除；渲染器异常时也绑适配器，
        // 缝方法在句柄无效时安全忽略，视图不再降级到旧画法。
        inputView.setCoreAdapter(adapter)
        // 新路径：attach 时把 colors.properties/内置明暗配色板 push 给渲染器。
        if (isRenderable(adapter)) {
            FableTerminalPalette.apply(context, adapter!!)
        }
        render.show()
        return changed
    }

    /** 会话被服务移除（关闭/退出自动清理）时销毁其渲染器并释放视图。 */
    fun onSessionRemoved(session: TerminalSession?) {
        val render = mSessionRenders.remove(session) ?: return
        if (render === mCurrentRender) {
            mCurrentRender = null
            mCurrentSession = null
            // Stop input delivery before destroying the renderer. The session
            // client may select a replacement immediately afterwards, but a
            // short gap must not leave the input view pointing at a dead
            // adapter.
            mInputView?.let {
                it.attachSession(null)
                it.setCoreAdapter(null)
            }
        }
        render.destroy()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mDetached = true
        FableDiagnostics.append("FableTerminalView.onDetachedFromWindow")
        // 配置变化/后台销毁：只释放视图与 Surface，渲染器留在会话上继续收字节。
        for (render in mSessionRenders.values) {
            render.release()
        }
        mSessionRenders.clear()
        mCurrentRender = null
        mCurrentSession = null
        // Clear only the transient view reference. FableInputTerminalView deliberately
        // leaves the session-owned adapter attached so output/state survive Activity
        // recreation and the same renderer can be reused on reattach.
        mInputView?.let {
            // Reset the transient view/session association as well. The session
            // itself still owns its adapter, so a later attach resets geometry
            // and reuses that adapter without losing terminal state.
            it.attachSession(null)
            it.setCoreAdapter(null)
        }
    }

    private class SessionRender(
        @JvmField val host: FableTerminalView,
        @JvmField val session: TerminalSession,
        @JvmField val adapter: CoreAdapter?
    ) {

        @JvmField
        val surfaceView: SurfaceView?

        @JvmField
        val callback: SurfaceHolder.Callback?

        @JvmField
        var visible = false

        @JvmField
        var surfaceReady = false

        @JvmField
        var hideRequested = false

        @JvmField
        var autoRecreating = false

        @JvmField
        var recreateAttempts = 0

        @JvmField
        var widthPx = 0

        @JvmField
        var heightPx = 0

        @JvmField
        var cols = 80

        @JvmField
        var rows = 24

        init {
            // 工单 22 光标/宽度修复：新会话启用 DECSET 2027（grapheme clustering），
            // 组合 emoji（学生/家庭）按 1 个 cluster 占 2 格，不再拆 4 格留长空白。
            // 幂等：会话切换重发无害。
            if (isRenderable(adapter)) {
                val graphemeOn = "\u001b[?2027h".toByteArray(Charsets.UTF_8)
                adapter!!.write(graphemeOn, graphemeOn.size)
            }

            if (!isRenderable(adapter)) {
                // 渲染器不可用（异常环境）：无 SurfaceView，输入视图保持可用，正文缺失
                //（旧路径已随工单 31 删除）。
                surfaceView = null
                callback = null
            } else {
                val createdSurfaceView = SurfaceView(host.context)
                createdSurfaceView.visibility = View.GONE
                surfaceView = createdSurfaceView
                val callbackImpl = object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        // surface 重建（ActionMode/输入法/窗口变化）后立即重挂，避免
                        // 一直停在分离状态导致整屏空白（fable-v1/04 选择空白遗留）。
                        surfaceReady = true
                        autoRecreating = false
                        recreateAttempts = 0
                        FableDiagnostics.append("surfaceCreated")
                        if (host.mCurrentRender === this@SessionRender && visible) {
                            attachAndSize()
                        }
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        if (width <= 0 || height <= 0 || !holder.surface.isValid) return
                        widthPx = width
                        heightPx = height
                        surfaceReady = true
                        FableDiagnostics.append("surfaceChanged w=$width h=$height")
                        if (host.mCurrentRender === this@SessionRender) {
                            attachAndSize()
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        surfaceReady = false
                        FableDiagnostics.append(
                            "surfaceDestroyed attached=${host.isAttachedToWindow}" +
                                " shown=${host.isShown}" +
                                " vis=${createdSurfaceView.visibility}"
                        )
                        adapter?.detach()
                        val shouldRecreate = !hideRequested && !autoRecreating &&
                            host.isAttachedToWindow &&
                            host.mCurrentRender === this@SessionRender
                        FableDiagnostics.append(
                            "scheduleAutoRecreate=$shouldRecreate" +
                                " hideReq=$hideRequested" +
                                " autoRecreating=$autoRecreating" +
                                " current=${host.mCurrentRender === this@SessionRender}"
                        )
                        if (shouldRecreate) {
                            // 用主线程 Handler（不依赖 View.postDelayed 的 attach 状态），120ms 后强制重建。
                            MAIN_HANDLER.postDelayed({ autoRecreateSurface() }, 120)
                        }
                    }
                }
                callback = callbackImpl
                createdSurfaceView.holder.addCallback(callbackImpl)
                host.addView(
                    createdSurfaceView,
                    0,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                )
            }
        }

        fun show() {
            if (!isRenderable(adapter)) return
            val renderAdapter = adapter ?: return
            val view = surfaceView ?: return
            hideRequested = false
            FableDiagnostics.append("render.show")
            if (visible) {
                attachIfSurfaceReady()
                return
            }
            visible = true
            view.visibility = View.VISIBLE
            attachIfSurfaceReady()
            // 与旧 TerminalView.attachSession 一致：切换/重新挂载会话回到最新。
            // 渲染器（会话级存活）可能残留旧视口偏移，用大正 delta 强制回底。
            renderAdapter.scroll(100000)
            renderAdapter.render(
                if (widthPx > 0) widthPx else view.width,
                if (heightPx > 0) heightPx else view.height
            )
        }

        fun hide() {
            if (!visible) return
            val renderAdapter = adapter ?: return
            val view = surfaceView ?: return
            visible = false
            hideRequested = true
            FableDiagnostics.append("render.hide")
            view.visibility = View.GONE
            renderAdapter.detach()
            surfaceReady = false
        }

        private fun attachAndSize() {
            if (!surfaceReady) return
            val renderAdapter = adapter ?: return
            val view = surfaceView ?: return
            val cell = IntArray(2)
            renderAdapter.getCellSize(cell)
            val cellWidth = maxOf(1, cell[0])
            val cellHeight = maxOf(1, cell[1])
            cols = maxOf(4, widthPx / cellWidth)
            rows = maxOf(4, heightPx / cellHeight)
            FableDiagnostics.append(
                "attachAndSize cols=$cols rows=$rows cell=${cellWidth}x$cellHeight"
            )
            session.updateSize(cols, rows, cellWidth, cellHeight)
            renderAdapter.attach(view.holder.surface, widthPx, heightPx)
            renderAdapter.render(widthPx, heightPx)
            // surface 重建后首帧可能 present 到未上屏缓冲且签名被去重：
            // 延迟强制重绘，保证重建后内容真正上屏（工单 04 选择空白恢复）。
            MAIN_HANDLER.postDelayed({
                if (surfaceReady && widthPx > 0 && heightPx > 0 &&
                    host.mCurrentRender === this@SessionRender
                ) {
                    renderAdapter.forceRender(widthPx, heightPx)
                }
            }, 250)
        }

        private fun attachIfSurfaceReady() {
            if (!visible || !surfaceReady) return
            val renderAdapter = adapter ?: return
            val view = surfaceView ?: return
            if (view.holder.surface.isValid) {
                renderAdapter.attach(view.holder.surface, widthPx, heightPx)
                renderAdapter.render(widthPx, heightPx)
            } else {
                surfaceReady = false
            }
        }

        /** 释放视图与 Surface（保留适配器在会话上）。 */
        fun release() {
            val view = surfaceView
            val callback = callback
            if (view != null && callback != null) {
                view.holder.removeCallback(callback)
                adapter?.detach()
                if (view.parent === host) {
                    host.removeView(view)
                }
            }
        }

        /** 解除会话缝引用并销毁适配器 + 释放视图。 */
        fun destroy() {
            if (session.getCoreAdapter() === adapter) session.setCoreAdapter(null)
            release()
            adapter?.destroy()
        }

        /** surface 丢失后强制重建：GONE→VISIBLE 触发系统重建 surface 并回调 surfaceCreated。 */
        fun autoRecreateSurface() {
            val view = surfaceView ?: return
            FableDiagnostics.append(
                "autoRecreateSurface check surfaceReady=$surfaceReady" +
                    " visible=$visible" +
                    " attached=${host.isAttachedToWindow}" +
                    " current=${host.mCurrentRender === this@SessionRender}"
            )
            if (surfaceReady || !visible || !host.isAttachedToWindow ||
                host.mCurrentRender !== this@SessionRender
            ) {
                return
            }
            autoRecreating = true
            recreateAttempts++
            FableDiagnostics.append("autoRecreateSurface attempt=$recreateAttempts")
            // 真机验证：removeView/addView 不重建；只有尺寸变化（键盘）能重建。
            // 改用跨帧 GONE→VISIBLE（同帧对撞无效），再叠加 1px 尺寸抖动兜底。
            view.visibility = View.GONE
            MAIN_HANDLER.postDelayed({
                if (surfaceReady || !visible || !host.isAttachedToWindow ||
                    host.mCurrentRender !== this@SessionRender
                ) {
                    autoRecreating = false
                    return@postDelayed
                }
                FableDiagnostics.append("recreateSurface visible")
                view.visibility = View.VISIBLE
                // 1px 尺寸抖动：强制 ViewRootImpl 重算 surface（若 GONE→VISIBLE 仍无效）。
                val layoutParams: ViewGroup.LayoutParams? = view.layoutParams
                if (layoutParams != null && layoutParams.height > 1) {
                    layoutParams.height--
                    view.layoutParams = layoutParams
                    MAIN_HANDLER.post {
                        view.layoutParams?.let {
                            it.height++
                            view.requestLayout()
                        }
                    }
                }
                if (recreateAttempts < 3) {
                    MAIN_HANDLER.postDelayed({
                        if (!surfaceReady && !hideRequested && visible &&
                            host.isAttachedToWindow &&
                            host.mCurrentRender === this@SessionRender
                        ) {
                            autoRecreateSurface()
                        } else {
                            autoRecreating = false
                        }
                    }, 400)
                } else {
                    autoRecreating = false
                }
            }, 100)
        }
    }
}
