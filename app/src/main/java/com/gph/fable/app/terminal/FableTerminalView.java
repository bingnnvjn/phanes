package com.gph.fable.app.terminal;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import com.gph.fable.app.terminal.adapter.FableRenderCoreAdapter;
import com.gph.fable.terminal.TerminalSession;
import com.gph.fable.terminal.adapter.CoreAdapter;
import com.gph.fable.view.FableInputTerminalView;

import java.util.HashMap;
import java.util.Map;

/**
 * 主终端 Fable 渲染容器（工单 15）：每会话一个 fable-render 适配器 + 一个
 * SurfaceView（参照工单 10/12 探针结构），内嵌 {@link FableInputTerminalView}
 * 继续承担 IME/手势/长按选择/上下文菜单输入。
 *
 * 渲染器生命周期挂在会话上（{@code TerminalSession#setCoreAdapter}）：
 * - 旋转/Activity 重建：容器只释放视图与 Surface，适配器留在会话，字节不丢；
 * - 会话关闭：onSessionRemoved 解除缝引用并销毁适配器；
 * - 会话进程退出：TerminalSession.cleanupResources 销毁适配器并解除缝引用。
 */
public final class FableTerminalView extends FrameLayout {

    private final Map<TerminalSession, SessionRender> mSessionRenders = new HashMap<>();
    private FableInputTerminalView mInputView;
    private TerminalSession mCurrentSession;
    private SessionRender mCurrentRender;
    private boolean mDetached;

    public FableTerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setInputView(FableInputTerminalView inputView) {
        mInputView = inputView;
    }

    public FableInputTerminalView getInputView() {
        return mInputView;
    }

    /**
     * 切换当前会话到 {@code session} 并接好对应渲染器/SurfaceView。
     * 返回是否实际发生了会话切换（与旧 TerminalView.attachSession 语义一致）。
     */
    public boolean attachSession(TerminalSession session) {
        if (session == null || mDetached || session == mCurrentSession) return false;
        if (mInputView == null) return false;

        FableDiagnostics.append("attachSession switch");
        if (mCurrentRender != null) mCurrentRender.hide();

        SessionRender render = mSessionRenders.get(session);
        if (render == null) {
            CoreAdapter adapter = session.getCoreAdapter();
            if (adapter == null) {
                FableRenderCoreAdapter fresh = new FableRenderCoreAdapter(80, 24);
                if (fresh.isValid()) {
                    session.setCoreAdapter(fresh);
                    adapter = fresh;
                }
            }
            render = new SessionRender(this, session, adapter);
            mSessionRenders.put(session, render);
        }

        mCurrentSession = session;
        mCurrentRender = render;

        boolean changed = mInputView.attachSession(session);
        // 先 attachSession（mEmulator 指向新会话）再绑缝，避免 setCoreAdapter 的
        // updateSize 把还挂着的旧会话 PTY 临时 resize。
        CoreAdapter adapter = session.getCoreAdapter();
        mInputView.setCoreAdapter(isRenderable(adapter) ? adapter : null);
        // 新路径：attach 时把 colors.properties/内置明暗配色板 push 给渲染器。
        FableTerminalPalette.apply(getContext(), isRenderable(adapter) ? adapter : null);
        render.show();
        return changed;
    }

    /** 会话被服务移除（关闭/退出自动清理）时销毁其渲染器并释放视图。 */
    public void onSessionRemoved(TerminalSession session) {
        SessionRender render = mSessionRenders.remove(session);
        if (render == null) return;
        if (render == mCurrentRender) {
            mCurrentRender = null;
            mCurrentSession = null;
        }
        render.destroy();
    }

    private static boolean isRenderable(CoreAdapter adapter) {
        return adapter instanceof FableRenderCoreAdapter
                && ((FableRenderCoreAdapter) adapter).isValid();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mDetached = true;
        FableDiagnostics.append("FableTerminalView.onDetachedFromWindow");
        // 配置变化/后台销毁：只释放视图与 Surface，渲染器留在会话上继续收字节。
        for (SessionRender render : mSessionRenders.values()) {
            render.release();
        }
        mSessionRenders.clear();
        mCurrentRender = null;
        mCurrentSession = null;
        if (mInputView != null) mInputView.setCoreAdapter(null);
    }

    private static final class SessionRender {

        final FableTerminalView host;
        final TerminalSession session;
        final CoreAdapter adapter;
        final SurfaceView surfaceView;
        final SurfaceHolder.Callback callback;

        boolean visible;
        boolean surfaceReady;
        boolean hideRequested;
        int widthPx;
        int heightPx;
        int cols = 80;
        int rows = 24;

        SessionRender(FableTerminalView host, TerminalSession session, CoreAdapter adapter) {
            this.host = host;
            this.session = session;
            this.adapter = adapter;
            if (!isRenderable(adapter)) {
                // 渲染器不可用（异常环境）：降级旧路径，由内嵌输入视图自绘。
                surfaceView = null;
                callback = null;
                return;
            }
            surfaceView = new SurfaceView(host.getContext());
            surfaceView.setVisibility(GONE);
            callback = new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    // surface 重建（ActionMode/输入法/窗口变化）后立即重挂，避免
                    // 一直停在分离状态导致整屏空白（fable-v1/04 选择空白遗留）。
                    surfaceReady = true;
                    FableDiagnostics.append("surfaceCreated");
                    if (host.mCurrentRender == SessionRender.this && visible) {
                        attachAndSize();
                    }
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    if (width <= 0 || height <= 0 || !holder.getSurface().isValid()) return;
                    widthPx = width;
                    heightPx = height;
                    surfaceReady = true;
                    FableDiagnostics.append("surfaceChanged w=" + width + " h=" + height);
                    if (host.mCurrentRender == SessionRender.this) {
                        attachAndSize();
                    }
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    surfaceReady = false;
                    FableDiagnostics.append("surfaceDestroyed");
                    adapter.detach();
                    if (!hideRequested && host.isAttachedToWindow()
                        && host.mCurrentRender == SessionRender.this) {
                        // surface 意外销毁（选择/输入法/窗口变化）：自动重建，不等用户唤醒。
                        host.postDelayed(SessionRender.this::autoRecreateSurface, 120);
                    }
                }
            };
            surfaceView.getHolder().addCallback(callback);
            host.addView(surfaceView, 0, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        void show() {
            if (!isRenderable(adapter)) return;
            hideRequested = false;
            FableDiagnostics.append("render.show");
            if (visible) {
                attachIfSurfaceReady();
                return;
            }
            visible = true;
            surfaceView.setVisibility(VISIBLE);
            attachIfSurfaceReady();
            // 与旧 TerminalView.attachSession 一致：切换/重新挂载会话回到最新。
            // 渲染器（会话级存活）可能残留旧视口偏移，用大正 delta 强制回底。
            adapter.scroll(100000);
            adapter.render(widthPx > 0 ? widthPx : surfaceView.getWidth(),
                    heightPx > 0 ? heightPx : surfaceView.getHeight());
        }

        void hide() {
            if (!visible) return;
            visible = false;
            hideRequested = true;
            FableDiagnostics.append("render.hide");
            surfaceView.setVisibility(GONE);
            adapter.detach();
            surfaceReady = false;
        }

        private void attachAndSize() {
            if (!surfaceReady) return;
            int[] cell = new int[2];
            adapter.getCellSize(cell);
            int cellWidth = Math.max(1, cell[0]);
            int cellHeight = Math.max(1, cell[1]);
            cols = Math.max(4, widthPx / cellWidth);
            rows = Math.max(4, heightPx / cellHeight);
            FableDiagnostics.append("attachAndSize cols=" + cols + " rows=" + rows + " cell=" + cellWidth + "x" + cellHeight);
            session.updateSize(cols, rows, cellWidth, cellHeight);
            adapter.attach(surfaceView.getHolder().getSurface(), widthPx, heightPx);
            adapter.render(widthPx, heightPx);
        }

        private void attachIfSurfaceReady() {
            if (!visible || !surfaceReady) return;
            if (surfaceView.getHolder().getSurface().isValid()) {
                adapter.attach(surfaceView.getHolder().getSurface(), widthPx, heightPx);
                adapter.render(widthPx, heightPx);
            } else {
                surfaceReady = false;
            }
        }

        /** 释放视图与 Surface（保留适配器在会话上）。 */
        void release() {
            if (surfaceView != null && callback != null) {
                surfaceView.getHolder().removeCallback(callback);
                adapter.detach();
            }
        }

        /** 解除会话缝引用并销毁适配器 + 释放视图。 */
        void destroy() {
            if (session.getCoreAdapter() == adapter) session.setCoreAdapter(null);
            release();
            if (adapter != null) adapter.destroy();
        }

        /** surface 丢失后强制重建：GONE→VISIBLE 触发系统重建 surface 并回调 surfaceCreated。 */
        private void autoRecreateSurface() {
            if (surfaceReady || !visible || !host.isAttachedToWindow()
                || host.mCurrentRender != SessionRender.this) {
                return;
            }
            FableDiagnostics.append("autoRecreateSurface");
            surfaceView.setVisibility(GONE);
            surfaceView.setVisibility(VISIBLE);
        }
    }
}
