package com.gph.fable.shared.termux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class FableBootstrapTest {

    @Test
    public void resolvesSupportedVariantsAndPackageManager() {
        assertEquals(
            FableBootstrap.PackageVariant.APT_ANDROID_7,
            FableBootstrap.PackageVariant.variantOf("apt-android-7")
        );
        assertEquals(
            FableBootstrap.PackageVariant.APT_ANDROID_5,
            FableBootstrap.PackageVariant.variantOf("apt-android-5")
        );
        assertEquals(FableBootstrap.PackageManager.APT,
            FableBootstrap.PackageManager.managerOf("apt"));
        assertTrue(FableBootstrap.PackageVariant.variantOf("apt-android-6") == null);
    }

    @Test
    public void setsManagerAndVariantForBothBootstrapVariants() {
        FableBootstrap.setFablePackageManagerAndVariant("apt-android-7");
        assertEquals(FableBootstrap.PackageManager.APT, FableBootstrap.TERMUX_APP_PACKAGE_MANAGER);
        assertEquals(FableBootstrap.PackageVariant.APT_ANDROID_7, FableBootstrap.TERMUX_APP_PACKAGE_VARIANT);
        assertTrue(FableBootstrap.isAppPackageManagerAPT());
        assertTrue(FableBootstrap.isAppPackageVariantAPTAndroid7());

        FableBootstrap.setFablePackageManagerAndVariant("apt-android-5");
        assertEquals(FableBootstrap.PackageVariant.APT_ANDROID_5, FableBootstrap.TERMUX_APP_PACKAGE_VARIANT);
        assertTrue(FableBootstrap.isAppPackageVariantAPTAndroid5());
    }

    @Test
    public void rejectsUnsupportedVariant() {
        try {
            FableBootstrap.setFablePackageManagerAndVariant("apt-android-6");
            fail("unsupported bootstrap variant should be rejected");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("Unsupported TERMUX_APP_PACKAGE_VARIANT"));
        }
    }
}
