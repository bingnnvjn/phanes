package com.gph.fable.app.api.file;

import com.gph.fable.app.api.file.FileReceiverActivity;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FileReceiverActivityTest {

    @Test
    public void staticSharingEntryPointsKeepTheirJvmAbi() throws Exception {
        Method isSharedTextAnUrl = FileReceiverActivity.class.getDeclaredMethod(
            "isSharedTextAnUrl", String.class);
        Assert.assertTrue(Modifier.isStatic(isSharedTextAnUrl.getModifiers()));

        Method updateComponentState = FileReceiverActivity.class.getDeclaredMethod(
            "updateFileReceiverActivityComponentsState", android.content.Context.class);
        Assert.assertTrue(Modifier.isPublic(updateComponentState.getModifiers()));
        Assert.assertTrue(Modifier.isStatic(updateComponentState.getModifiers()));
    }

    @Test
    public void testIsSharedTextAnUrl() {
        List<String> validUrls = new ArrayList<>();
        validUrls.add("http://example.com");
        validUrls.add("https://example.com");
        validUrls.add("https://example.com/path/parameter=foo");
        validUrls.add("magnet:?xt=urn:btih:d540fc48eb12f2833163eed6421d449dd8f1ce1f&dn=Ubuntu+desktop+19.04+%2864bit%29&tr=udp%3A%2F%2Ftracker.openbittorrent.com%3A80&tr=udp%3A%2F%2Ftracker.publicbt.com%3A80&tr=udp%3A%2F%2Ftracker.ccc.de%3A80");
        for (String url : validUrls) {
            Assert.assertTrue(FileReceiverActivity.isSharedTextAnUrl(url));
        }

        List<String> invalidUrls = new ArrayList<>();
        invalidUrls.add("a test with example.com");
        invalidUrls.add("");
        invalidUrls.add(null);
        for (String url : invalidUrls) {
            Assert.assertFalse(FileReceiverActivity.isSharedTextAnUrl(url));
        }
    }

}
