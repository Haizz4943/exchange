package com.haizz.exchange.matching;

import com.haizz.exchange.matching.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
public class MatchingApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchingApplication.class, args);
    }
}
