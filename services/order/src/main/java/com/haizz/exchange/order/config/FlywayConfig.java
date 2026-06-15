package com.haizz.exchange.order.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Arrays;

@Configuration
public class FlywayConfig {

    @Bean
    public Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load();
        flyway.migrate();
        return flyway;
    }

    /**
     * Forces entityManagerFactory to wait for Flyway migrations before
     * Hibernate schema validation runs. Required because Spring Boot 4.0
     * does not wire this dependency automatically.
     */
    @Bean
    public static BeanFactoryPostProcessor flywayJpaDependsOnPostProcessor() {
        return beanFactory -> {
            BeanDefinition emf = beanFactory.getBeanDefinition("entityManagerFactory");
            String[] existing = emf.getDependsOn();
            String[] updated = existing == null
                    ? new String[]{"flyway"}
                    : Arrays.copyOf(existing, existing.length + 1);
            if (existing != null) updated[existing.length] = "flyway";
            emf.setDependsOn(updated);
        };
    }
}
