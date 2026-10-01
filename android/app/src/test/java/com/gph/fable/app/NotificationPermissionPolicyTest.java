package com.gph.fable.app;

import com.gph.fable.shared.android.PermissionUtils;

import org.junit.Assert;
import org.junit.Test;

/**
 * 工单 05：通知权限请求策略（纯逻辑，可在缝上单测）。
 *
 * 决策规则：
 * - 已授权 → 不再请求；
 * - 未授权但已问过 → 不再自动弹窗（尊重用户选择，可去系统设置开启），
 *   权限被撤销（Android 15 自动撤销/用户划掉）时同样走此分支，只降级不打断会话；
 * - 未授权且未问过 → 请求一次。
 */
public class NotificationPermissionPolicyTest {

    @Test
    public void granted_never_asks() {
        Assert.assertFalse(PermissionUtils.shouldRequestNotificationPermission(true, false));
        Assert.assertFalse(PermissionUtils.shouldRequestNotificationPermission(true, true));
    }

    @Test
    public void denied_and_already_asked_does_not_nag() {
        Assert.assertFalse(PermissionUtils.shouldRequestNotificationPermission(false, true));
    }

    @Test
    public void denied_and_never_asked_requests_once() {
        Assert.assertTrue(PermissionUtils.shouldRequestNotificationPermission(false, false));
    }
}
