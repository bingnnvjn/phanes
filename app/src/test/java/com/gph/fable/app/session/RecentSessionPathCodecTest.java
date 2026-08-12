package com.gph.fable.app.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** 工单 28：Java→Kotlin 互调 JVM 测试（AGP 9 内置 Kotlin 试点）。 */
public class RecentSessionPathCodecTest {

    @Test
    public void encodeDecodeRoundTrip() {
        String[] paths = {
            "/data/data/com.gph.fable/files/home",
            "/home/a+b%c/中文 目录\n换行",
            "/"
        };

        for (String path : paths) {
            String encoded = RecentSessionPathCodec.encode(path);
            assertEquals(path, RecentSessionPathCodec.decode(encoded));
        }
    }

    @Test
    public void decodeHandlesNullAndMalformedInput() {
        assertNull(RecentSessionPathCodec.decode(null));
        assertNull(RecentSessionPathCodec.decode("%ZZ"));
    }
}
