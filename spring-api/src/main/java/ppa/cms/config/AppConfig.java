package ppa.cms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import ppa.cms.crypto.LaravelEncrypter;

@Configuration
public class AppConfig {

    @Bean
    LaravelEncrypter laravelEncrypter(CmsProperties properties) {
        return new LaravelEncrypter(properties.getAppKey());
    }

    @Configuration
    @EnableScheduling
    @Profile("!test & !cli")
    static class SchedulingConfig {
    }
}
