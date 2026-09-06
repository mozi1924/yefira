package com.mozi1924.yefira;

import com.mozi1924.yefira.config.YefiraConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ConfigTest {

    @Test
    public void testConfigDefaultsAndSetters() {
        YefiraConfig config = new YefiraConfig();

        Assertions.assertEquals("0.0.0.0", config.getHost());
        Assertions.assertEquals(8765, config.getPort());
        Assertions.assertFalse(config.isAutoStartOnWorldLoad());

        config.setHost("127.0.0.1");
        Assertions.assertEquals("127.0.0.1", config.getHost());

        config.setPort(9000);
        Assertions.assertEquals(9000, config.getPort());

        // Invalid port check (below 1024 or above 65535 should be ignored)
        config.setPort(80);
        Assertions.assertEquals(9000, config.getPort());

        config.setPort(70000);
        Assertions.assertEquals(9000, config.getPort());

        config.setAutoStartOnWorldLoad(true);
        Assertions.assertTrue(config.isAutoStartOnWorldLoad());

        // Soft limit defaults & setters
        Assertions.assertEquals(262144L, config.getMaxVolumeSoftLimit());
        Assertions.assertEquals(256, config.getMaxSideSoftLimit());

        config.setMaxVolumeSoftLimit(500000L);
        Assertions.assertEquals(500000L, config.getMaxVolumeSoftLimit());
        config.setMaxVolumeSoftLimit(-100L); // invalid, should ignore
        Assertions.assertEquals(500000L, config.getMaxVolumeSoftLimit());

        config.setMaxSideSoftLimit(512);
        Assertions.assertEquals(512, config.getMaxSideSoftLimit());
        config.setMaxSideSoftLimit(-1); // invalid, should ignore
        Assertions.assertEquals(512, config.getMaxSideSoftLimit());
    }
}
