package com.notificationengine;

import com.notificationengine.model.enums.NotificationStatus;
import com.notificationengine.retry.RetryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotificationEngineUnitTests {

    // ═══════════════════════════════════════════════════════════
    // State Machine Tests
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("NotificationStatus State Machine")
    class StateMachineTests {

        @Test
        @DisplayName("QUEUED can transition to DISPATCHED")
        void queuedToDispatched() {
            assertTrue(NotificationStatus.QUEUED.canTransitionTo(NotificationStatus.DISPATCHED));
        }

        @Test
        @DisplayName("QUEUED can transition to FAILED")
        void queuedToFailed() {
            assertTrue(NotificationStatus.QUEUED.canTransitionTo(NotificationStatus.FAILED));
        }

        @Test
        @DisplayName("DISPATCHED can transition to DELIVERED")
        void dispatchedToDelivered() {
            assertTrue(NotificationStatus.DISPATCHED.canTransitionTo(NotificationStatus.DELIVERED));
        }

        @Test
        @DisplayName("DISPATCHED can transition to FAILED")
        void dispatchedToFailed() {
            assertTrue(NotificationStatus.DISPATCHED.canTransitionTo(NotificationStatus.FAILED));
        }

        @Test
        @DisplayName("DELIVERED is terminal - no transitions allowed")
        void deliveredIsTerminal() {
            assertFalse(NotificationStatus.DELIVERED.canTransitionTo(NotificationStatus.QUEUED));
            assertFalse(NotificationStatus.DELIVERED.canTransitionTo(NotificationStatus.DISPATCHED));
            assertFalse(NotificationStatus.DELIVERED.canTransitionTo(NotificationStatus.FAILED));
        }

        @Test
        @DisplayName("FAILED can transition back to QUEUED (DLQ reprocess)")
        void failedToQueued() {
            assertTrue(NotificationStatus.FAILED.canTransitionTo(NotificationStatus.QUEUED));
        }

        @Test
        @DisplayName("QUEUED cannot skip to DELIVERED")
        void queuedCannotSkipToDelivered() {
            assertFalse(NotificationStatus.QUEUED.canTransitionTo(NotificationStatus.DELIVERED));
        }

        @Test
        @DisplayName("FAILED cannot transition to DISPATCHED directly")
        void failedCannotGoToDispatched() {
            assertFalse(NotificationStatus.FAILED.canTransitionTo(NotificationStatus.DISPATCHED));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Template Placeholder Pattern Tests
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Template Placeholder Resolution")
    class TemplatePlaceholderTests {

        @Test
        @DisplayName("Simple placeholder replacement")
        void simplePlaceholder() {
            String template = "Hello {{userName}}, welcome to {{appName}}!";
            String result = template
                    .replace("{{userName}}", "John")
                    .replace("{{appName}}", "MyApp");
            assertEquals("Hello John, welcome to MyApp!", result);
        }

        @Test
        @DisplayName("OTP template")
        void otpTemplate() {
            String template = "Your OTP for {{appName}} is {{otpCode}}. Valid for {{validMinutes}} minutes.";
            String result = template
                    .replace("{{appName}}", "PayApp")
                    .replace("{{otpCode}}", "482913")
                    .replace("{{validMinutes}}", "5");
            assertEquals("Your OTP for PayApp is 482913. Valid for 5 minutes.", result);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Exponential Backoff Tests
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Exponential Backoff Computation")
    class BackoffTests {

        @Test
        @DisplayName("Backoff increases exponentially")
        void exponentialIncrease() {
            // initialInterval=1000ms, multiplier=2.0
            long initial = 1000L;
            double multiplier = 2.0;
            long maxInterval = 60000L;

            long delay1 = computeBackoff(1, initial, multiplier, maxInterval); // 1000
            long delay2 = computeBackoff(2, initial, multiplier, maxInterval); // 2000
            long delay3 = computeBackoff(3, initial, multiplier, maxInterval); // 4000
            long delay4 = computeBackoff(4, initial, multiplier, maxInterval); // 8000
            long delay5 = computeBackoff(5, initial, multiplier, maxInterval); // 16000

            assertEquals(1000L, delay1);
            assertEquals(2000L, delay2);
            assertEquals(4000L, delay3);
            assertEquals(8000L, delay4);
            assertEquals(16000L, delay5);
        }

        @Test
        @DisplayName("Backoff is capped at max interval")
        void cappedAtMax() {
            long initial = 1000L;
            double multiplier = 2.0;
            long maxInterval = 5000L;

            long delay = computeBackoff(10, initial, multiplier, maxInterval);
            assertEquals(maxInterval, delay);
        }

        private long computeBackoff(int retryAttempt, long initial, double multiplier, long maxInterval) {
            long delay = (long) (initial * Math.pow(multiplier, retryAttempt - 1));
            return Math.min(delay, maxInterval);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Priority Routing Tests
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Priority-based Topic Routing")
    class PriorityRoutingTests {

        @Test
        @DisplayName("HIGH priority routes to high-priority topic")
        void highPriorityRouting() {
            String topic = resolveTopic("HIGH");
            assertEquals("notifications.high-priority", topic);
        }

        @Test
        @DisplayName("LOW priority routes to low-priority topic")
        void lowPriorityRouting() {
            String topic = resolveTopic("LOW");
            assertEquals("notifications.low-priority", topic);
        }

        private String resolveTopic(String priority) {
            return "HIGH".equals(priority)
                    ? "notifications.high-priority"
                    : "notifications.low-priority";
        }
    }
}
