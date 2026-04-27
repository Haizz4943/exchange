package com.haizz.exchange.wallet.dev;

import com.haizz.exchange.wallet.application.InitializeWalletsUseCase;
import com.haizz.exchange.wallet.infrastructure.kafka.event.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Seeds test users' wallets in dev mode.
 * Goes through the service layer (not raw SQL) to ensure invariants and audit records.
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevWalletSeeder implements ApplicationRunner {

    private static final List<UUID> DEV_USER_IDS = List.of(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002")
    );

    private final InitializeWalletsUseCase initializeWalletsUseCase;

    @Override
    public void run(ApplicationArguments args) {
        for (UUID userId : DEV_USER_IDS) {
            try {
                UserRegisteredEvent event = new UserRegisteredEvent(
                        userId, "dev-user-" + userId + "@example.com",
                        Instant.now(), "local");
                initializeWalletsUseCase.execute(event);
                log.info("Dev seeder: wallets initialized for userId={}", userId);
            } catch (Exception e) {
                log.warn("Dev seeder: skipping userId={} — {}", userId, e.getMessage());
            }
        }
    }
}
