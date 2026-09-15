package com.synechisveltiosi.commerce.platform;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

public final class Bootstrap {
    private Bootstrap() {
    }

    public static <T> T bean(Class<T> type) {
        return Holder.CONTEXT.getBean(type);
    }

    public static boolean local() {
        return "local".equals(System.getenv("APP_ENV"));
    }

    private static final class Holder {
        static final ConfigurableApplicationContext CONTEXT = start();

        static ConfigurableApplicationContext start() {
            var app = new SpringApplication(RuntimeConfiguration.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setBannerMode(Banner.Mode.OFF);
            app.setLogStartupInfo(false);
            return app.run();
        }
    }
}
