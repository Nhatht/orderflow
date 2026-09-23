package com.orderflow.payment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bật {@code @Scheduled} — cần cho {@code OutboxPoller}. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
