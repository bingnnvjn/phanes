package com.gph.fable.app;

import com.gph.fable.BuildConfig;
import com.gph.fable.shared.termux.TermuxConstants;

import org.junit.Assert;
import org.junit.Test;

/**
 * Fable 永久身份测试（工单 01）。
 *
 * 包名、App 名称、数据目录、权限名与 provider authority 全部由
 * {@link TermuxConstants#TERMUX_PACKAGE_NAME} 派生，这里把它们锁死为
 * com.gph.fable / Fable，防止回归。
 */
public class FablePackageIdentityTest {

    @Test
    public void applicationId_is_com_gph_fable() {
        Assert.assertEquals("com.gph.fable", BuildConfig.APPLICATION_ID);
    }

    @Test
    public void packageName_constant_is_com_gph_fable() {
        Assert.assertEquals("com.gph.fable", TermuxConstants.TERMUX_PACKAGE_NAME);
    }

    @Test
    public void appName_is_fable() {
        Assert.assertEquals("Fable", TermuxConstants.TERMUX_APP_NAME);
    }

    @Test
    public void dataDir_derives_from_new_package() {
        Assert.assertEquals("/data/data/com.gph.fable",
                TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH);
        Assert.assertEquals("/data/data/com.gph.fable/files/usr",
                TermuxConstants.TERMUX_PREFIX_DIR_PATH);
    }

    @Test
    public void permission_and_authority_derive_from_new_package() {
        Assert.assertEquals("com.gph.fable.permission.RUN_COMMAND",
                TermuxConstants.PERMISSION_RUN_COMMAND);
        Assert.assertEquals("com.gph.fable.files",
                TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY);
    }
}
