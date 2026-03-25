-- ================================================================
-- Notification Engine Schema
-- ================================================================

CREATE DATABASE IF NOT EXISTS notification_engine;
USE notification_engine;

-- ─── Notification Templates ─────────────────────────────────
CREATE TABLE IF NOT EXISTS notification_templates (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_code   VARCHAR(100)  NOT NULL UNIQUE,
    channel         VARCHAR(20)   NOT NULL COMMENT 'EMAIL, SMS, PUSH',
    subject         VARCHAR(500)  NULL COMMENT 'For email subject line',
    body_template   TEXT          NOT NULL COMMENT 'Template with {{placeholder}} syntax',
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_template_code (template_code),
    INDEX idx_channel (channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─── Notifications ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS notifications (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key     VARCHAR(255) NOT NULL UNIQUE COMMENT 'Client-supplied dedup key',
    user_id             VARCHAR(100) NOT NULL,
    channel             VARCHAR(20)  NOT NULL COMMENT 'EMAIL, SMS, PUSH',
    priority            VARCHAR(10)  NOT NULL DEFAULT 'LOW' COMMENT 'HIGH, LOW',
    status              VARCHAR(20)  NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED, DISPATCHED, DELIVERED, FAILED',
    template_code       VARCHAR(100) NULL,
    subject             VARCHAR(500) NULL,
    body                TEXT         NOT NULL,
    recipient           VARCHAR(255) NOT NULL COMMENT 'email/phone/device-token',
    metadata            JSON         NULL COMMENT 'Extra key-value pairs',
    retry_count         INT          NOT NULL DEFAULT 0,
    max_retries         INT          NOT NULL DEFAULT 5,
    next_retry_at       TIMESTAMP    NULL,
    last_error          TEXT         NULL,
    dispatched_at       TIMESTAMP    NULL,
    delivered_at        TIMESTAMP    NULL,
    failed_at           TIMESTAMP    NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_idempotency_key (idempotency_key),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_channel_status (channel, status),
    INDEX idx_priority_status (priority, status),
    INDEX idx_next_retry (next_retry_at),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─── Delivery Audit Log ─────────────────────────────────────
CREATE TABLE IF NOT EXISTS delivery_audit_log (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    notification_id     BIGINT       NOT NULL,
    previous_status     VARCHAR(20)  NOT NULL,
    new_status          VARCHAR(20)  NOT NULL,
    message             TEXT         NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_notification_id (notification_id),
    INDEX idx_created_at (created_at),

    CONSTRAINT fk_audit_notification
        FOREIGN KEY (notification_id) REFERENCES notifications(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─── Seed Templates ─────────────────────────────────────────
INSERT INTO notification_templates (template_code, channel, subject, body_template) VALUES
('WELCOME_EMAIL', 'EMAIL', 'Welcome to {{appName}}, {{userName}}!',
 'Hi {{userName}},\n\nWelcome to {{appName}}! We are excited to have you on board.\n\nYour account has been created successfully.\n\nBest regards,\nThe {{appName}} Team'),

('OTP_SMS', 'SMS', NULL,
 'Your OTP for {{appName}} is {{otpCode}}. Valid for {{validMinutes}} minutes. Do not share this with anyone.'),

('ORDER_CONFIRMATION_EMAIL', 'EMAIL', 'Order #{{orderId}} Confirmed',
 'Hi {{userName}},\n\nYour order #{{orderId}} has been confirmed.\n\nTotal: {{currency}}{{totalAmount}}\nEstimated Delivery: {{deliveryDate}}\n\nTrack your order: {{trackingUrl}}\n\nThank you for shopping with us!'),

('PAYMENT_SUCCESS_PUSH', 'PUSH', NULL,
 'Payment of {{currency}}{{amount}} received for {{description}}. Transaction ID: {{txnId}}'),

('PASSWORD_RESET_EMAIL', 'EMAIL', 'Reset Your Password - {{appName}}',
 'Hi {{userName}},\n\nWe received a request to reset your password.\n\nClick the link below to reset:\n{{resetLink}}\n\nThis link expires in {{expiryMinutes}} minutes.\n\nIf you did not request this, please ignore this email.'),

('LOW_BALANCE_SMS', 'SMS', NULL,
 'Alert: Your {{appName}} wallet balance is low ({{currency}}{{balance}}). Recharge now to avoid service interruption.'),

('PROMO_PUSH', 'PUSH', NULL,
 '{{promoTitle}}: {{promoDescription}}. Use code {{promoCode}} for {{discountPercent}}% off! Offer ends {{expiryDate}}.');
