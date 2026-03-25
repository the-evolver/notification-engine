# High-Throughput Notification Engine

A production-grade notification system built with **Java 17**, **Spring Boot 3**, **Apache Kafka**, **MySQL**, and **Redis**. Designed to reliably deliver **50,000+ notifications per hour** across Email, SMS, and Push channels — the kind of system that powers real-world fintech apps like Razorpay, Juspay, and Groww.

---

## Table of Contents

- [What Does This Project Do?](#what-does-this-project-do)
- [Tech Stack — Why Each Tool?](#tech-stack--why-each-tool)
- [How It Works — Architecture](#how-it-works--architecture)
- [Core Concepts Explained](#core-concepts-explained)
- [Project Structure](#project-structure)
- [Quick Start](#quick-start)
- [API Reference](#api-reference)
- [Monitoring](#monitoring)
- [Resume-Ready Bullets](#resume-ready-bullets)

---

## What Does This Project Do?

Imagine you're building an app like a banking or e-commerce platform. When a user signs up, completes a payment, or requests an OTP, your system needs to send them a notification — fast, reliably, and without duplicates.

This engine handles all of that:

- A user triggers an action (e.g., payment success)
- Your app calls this engine's REST API
- The engine queues the notification, processes it, and delivers it via the right channel (Email / SMS / Push)
- If delivery fails, it retries automatically with increasing delays
- Every step is tracked and logged so you can always know the state of any notification

---

## Tech Stack — Why Each Tool?

| Tool | What It Is | Why We Use It Here |
|------|-----------|-------------------|
| **Java 17 + Spring Boot 3** | The main programming language and framework | Industry standard for backend services; Spring Boot removes boilerplate so you focus on business logic |
| **Apache Kafka** | A distributed message queue / event streaming platform | Decouples the API from the processing; lets us handle traffic spikes without dropping notifications |
| **MySQL 8** | A relational database | Stores all notifications, templates, and audit logs permanently |
| **Redis 7** | An in-memory key-value store (like a super-fast dictionary) | Used for two things: preventing duplicate sends, and caching notification status for fast reads |
| **Docker Compose** | A tool to run multiple services together locally | Lets you start Kafka + MySQL + Redis + the app with a single command |
| **Micrometer + Prometheus** | Metrics collection tools | Expose numbers like "how many notifications succeeded/failed" so you can monitor the system |

---

## How It Works — Architecture

Here is the full flow from API call to notification delivery:

```
┌──────────────┐     ┌─────────────────────────────────────────────────────────────┐
│  REST API    │     │                    KAFKA CLUSTER                            │
│              │     │  ┌──────────────────────┐  ┌──────────────────────┐         │
│ POST /send   │────▶│  │ HIGH-PRIORITY TOPIC  │  │  LOW-PRIORITY TOPIC  │         │
│ POST /batch  │     │  │ (6 partitions)       │  │  (3 partitions)      │         │
└──────┬───────┘     │  └──────────┬───────────┘  └──────────┬───────────┘         │
       │             │             │                         │                     │
       │             │  ┌──────────▼───────────┐  ┌──────────▼───────────┐         │
       │             │  │ 6 Consumer Threads   │  │ 3 Consumer Threads   │         │
       │             │  └──────────┬───────────┘  └──────────┬───────────┘         │
       │             │             │                         │                     │
       │             │             └────────┬────────────────┘                     │
       │             │                      │                                      │
       │             │  ┌───────────────────▼──────────────────┐                   │
       │             │  │         PROCESSING PIPELINE          │                   │
       │             │  │  1. Idempotency Check (Redis)        │                   │
       │             │  │  2. Status: QUEUED → DISPATCHED      │                   │
       │             │  │  3. Channel Dispatch (Email/SMS/Push) │                  │
       │             │  │  4. Status: DISPATCHED → DELIVERED   │                   │
       │             │  └──────────┬──────────┬────────────────┘                   │
       │             │             │          │                                     │
       │             │          Success     Failure                                │
       │             │             │          │                                     │
       │             │             │   ┌──────▼──────────────┐                     │
       │             │             │   │ Exponential Backoff │                     │
       │             │             │   │ retry < max? ──Yes──│──▶ Re-queue         │
       │             │             │   │       │             │                     │
       │             │             │   │      No             │                     │
       │             │             │   │       │             │                     │
       │             │             │   │  ┌────▼────────┐    │                     │
       │             │             │   │  │  DLQ TOPIC  │    │                     │
       │             │             │   │  └─────────────┘    │                     │
       │             │             │   └─────────────────────┘                     │
       │             └─────────────────────────────────────────────────────────────┘
       │
  ┌────▼─────┐    ┌──────────┐
  │  MySQL   │    │  Redis   │
  │          │    │          │
  │ • Notifs │    │ • Idemp  │
  │ • Audit  │    │   keys   │
  │ • Tmpls  │    │ • Status │
  └──────────┘    │   cache  │
                  └──────────┘
```

### Step-by-step walkthrough

1. **Your app calls the REST API** — e.g., `POST /api/v1/notifications` with the user's details and what kind of notification to send.
2. **The API saves the notification to MySQL** and publishes a message (event) to a Kafka topic based on priority.
3. **Kafka consumers pick up the message** — high-priority notifications get more consumer threads (6 vs 3) so they're processed faster.
4. **Before processing**, Redis is checked to ensure this exact notification hasn't already been processed (idempotency check — prevents duplicates).
5. **The notification is dispatched** via the appropriate channel (Email, SMS, or Push).
6. **On success**, the status is updated to `DELIVERED` in MySQL and cached in Redis.
7. **On failure**, the retry system kicks in with exponential backoff. After max retries, it goes to the Dead Letter Queue (DLQ) for manual review.

---

## Core Concepts Explained

### 1. Why Kafka? (And What Is It?)

Kafka is a message queue — think of it as a post office between your API and your processing workers.

Without Kafka, if 10,000 users trigger notifications at the same time, your service would be overwhelmed. With Kafka, notifications are placed into a queue and workers consume them at a controlled pace.

This project uses **two separate topics** (queues):
- `HIGH-PRIORITY` — OTPs, payment alerts → 6 worker threads
- `LOW-PRIORITY` — promotions, newsletters → 3 worker threads

This ensures urgent notifications never get stuck behind bulk marketing messages.

**Partitions** split a topic across multiple workers. More partitions = more parallel processing = higher throughput.

---

### 2. Idempotency — Preventing Duplicate Notifications

**Idempotency** means: "no matter how many times you trigger the same action, the result happens only once."

Why does this matter? Kafka can sometimes redeliver a message (e.g., after a crash). Without protection, a user could receive the same OTP twice.

How this project solves it:
1. Every notification request includes a unique `idempotencyKey` (you generate this — e.g., `"otp-user-42-1711234567"`)
2. Before processing, we do a `SETNX` (Set if Not eXists) in Redis — this atomically claims the key
3. If claimed successfully → process the notification
4. If the key already exists → skip (it was already processed)
5. On failure → release the key so a retry can re-claim it

```
Request with idempotencyKey: "otp-user-42-001"
        │
        ▼
Redis: SETNX "otp-user-42-001" →
        ├── Got it (key was free)  → process notification
        └── Already exists        → skip, return existing result
```

---

### 3. Exponential Backoff + Dead Letter Queue (DLQ)

When a notification fails (e.g., email provider is down), we don't retry immediately — that would hammer an already-struggling service. Instead, we wait progressively longer between retries:

```
Attempt 1: wait  1 second,  try again
Attempt 2: wait  2 seconds, try again
Attempt 3: wait  4 seconds, try again
Attempt 4: wait  8 seconds, try again
Attempt 5: wait 16 seconds, try again
           ↓
        Still failing → send to DLQ (Dead Letter Queue)
```

The **Dead Letter Queue** is a special Kafka topic that holds notifications that have exhausted all retries. An engineer can review these, fix the underlying issue, and manually reprocess them.

You can tune retry behavior in `application.yml`:

```yaml
notification:
  retry:
    max-attempts: 5
    initial-interval-ms: 1000
    multiplier: 2.0
    max-interval-ms: 60000
```

---

### 4. Delivery Status State Machine

Every notification moves through a defined set of states. Think of it like a package tracking system:

```
QUEUED ──→ DISPATCHED ──→ DELIVERED
  │              │
  └──────────────└──→ FAILED ──→ QUEUED (retry / DLQ reprocess)
```

| Status | Meaning |
|--------|---------|
| `QUEUED` | Notification received, waiting to be processed |
| `DISPATCHED` | Sent to the channel provider (e.g., email gateway) |
| `DELIVERED` | Confirmed delivery |
| `FAILED` | All retry attempts exhausted |

Every state transition is:
- **Validated** — you can't jump from `QUEUED` directly to `DELIVERED`
- **Persisted** in MySQL
- **Audit-logged** — you get a full history of every transition
- **Cached** in Redis — so status lookups are fast (< 50ms)

---

### 5. Template Engine

Instead of hardcoding messages, templates let you define reusable content with dynamic placeholders:

```
Template: "Hello {{userName}}, your OTP for {{appName}} is {{otp}}."
Params:   { "userName": "Rahul", "appName": "FinanceApp", "otp": "482913" }
Result:   "Hello Rahul, your OTP for FinanceApp is 482913."
```

Pre-seeded templates included:

| Template Code | Channel | Purpose |
|---------------|---------|---------|
| `WELCOME_EMAIL` | EMAIL | User onboarding |
| `OTP_SMS` | SMS | One-time passwords |
| `ORDER_CONFIRMATION_EMAIL` | EMAIL | Order receipts |
| `PAYMENT_SUCCESS_PUSH` | PUSH | Payment confirmations |
| `PASSWORD_RESET_EMAIL` | EMAIL | Password reset flow |
| `LOW_BALANCE_SMS` | SMS | Balance alerts |
| `PROMO_PUSH` | PUSH | Promotional notifications |

---

### 6. This Project vs. A Basic Notification System

| Feature | Basic Project | This Project |
|---------|--------------|--------------|
| Kafka usage | Single topic, single consumer | Priority-tiered topics with separate thread pools |
| Deduplication | None | Redis-backed idempotency keys |
| Retry | Basic retry or none | Exponential backoff + Dead Letter Queue |
| Status tracking | Boolean flag | Full state machine with audit trail |
| Read latency | Direct DB query | Redis cache layer (< 50ms) |
| Content | Hardcoded strings | Template engine with `{{placeholder}}` syntax |
| Channels | Single channel | Multi-channel fanout (Email / SMS / Push) |
| Metrics | None | Prometheus + Micrometer |

---

## Project Structure

```
notification-engine/
├── docker-compose.yml          # Starts all services: Kafka, MySQL, Redis, App
├── Dockerfile                  # Multi-stage build for the Java app
├── pom.xml                     # Maven dependencies
├── load-test.sh                # Script to send bulk test notifications
└── src/
    ├── main/
    │   ├── java/com/notificationengine/
    │   │   ├── NotificationEngineApplication.java   # App entry point
    │   │   ├── config/
    │   │   │   ├── KafkaConfig.java                 # Priority-tiered consumer setup
    │   │   │   ├── RedisConfig.java                 # Cache configuration
    │   │   │   └── AsyncConfig.java                 # Thread pool setup
    │   │   ├── controller/
    │   │   │   ├── NotificationController.java      # REST API endpoints
    │   │   │   └── AdminController.java             # Health check + audit endpoints
    │   │   ├── model/
    │   │   │   ├── entity/
    │   │   │   │   ├── Notification.java            # DB entity for a notification
    │   │   │   │   ├── NotificationTemplate.java    # DB entity for templates
    │   │   │   │   └── DeliveryAuditLog.java        # Records every status change
    │   │   │   ├── enums/
    │   │   │   │   ├── NotificationStatus.java      # QUEUED, DISPATCHED, etc.
    │   │   │   │   ├── NotificationChannel.java     # EMAIL, SMS, PUSH
    │   │   │   │   └── Priority.java                # HIGH, LOW
    │   │   │   └── dto/
    │   │   │       ├── NotificationRequest.java     # Incoming API payload
    │   │   │       ├── NotificationResponse.java    # Outgoing API response
    │   │   │       ├── NotificationEvent.java       # Kafka message payload
    │   │   │       └── BatchNotificationRequest.java
    │   │   ├── service/
    │   │   │   ├── NotificationService.java         # Main orchestration logic
    │   │   │   ├── DeliveryStatusService.java       # State machine + Redis cache
    │   │   │   ├── IdempotencyService.java          # Redis deduplication
    │   │   │   ├── TemplateEngine.java              # Resolves {{placeholders}}
    │   │   │   └── channel/
    │   │   │       ├── ChannelDispatcher.java       # Interface all channels implement
    │   │   │       ├── ChannelRouter.java           # Picks the right dispatcher
    │   │   │       ├── EmailDispatcher.java
    │   │   │       ├── SmsDispatcher.java
    │   │   │       └── PushDispatcher.java
    │   │   ├── kafka/
    │   │   │   ├── producer/
    │   │   │   │   └── NotificationProducer.java    # Publishes events to Kafka
    │   │   │   └── consumer/
    │   │   │       └── NotificationConsumer.java    # Reads from Kafka and processes
    │   │   ├── retry/
    │   │   │   ├── RetryService.java                # Exponential backoff + DLQ logic
    │   │   │   └── RetryScheduler.java              # Polls DB for pending retries
    │   │   └── exception/
    │   │       ├── GlobalExceptionHandler.java
    │   │       ├── DuplicateNotificationException.java
    │   │       ├── InvalidStateTransitionException.java
    │   │       └── DispatchFailedException.java
    │   └── resources/
    │       ├── application.yml                      # All configuration
    │       └── schema.sql                           # DB table definitions
    └── test/
        └── java/com/notificationengine/
            └── NotificationEngineUnitTests.java
```

---

## Quick Start

### Prerequisites

Make sure you have the following installed:

- **Docker Desktop** — [download here](https://www.docker.com/products/docker-desktop) — runs Kafka, MySQL, and Redis in containers
- **Java 17+** — only needed if you want to run or modify the code locally without Docker
- **Maven 3.8+** — only needed for local development

> **New to Docker?** Docker lets you run services (like databases) in isolated containers without installing them directly on your machine. `docker-compose` orchestrates multiple containers together.

---

### Step 1 — Clone the repository

```bash
git clone https://github.com/the-evolver/notification-engine.git
cd notification-engine
```

---

### Step 2 — Start all services

This single command builds the Java app and starts everything (Kafka, MySQL, Redis, and the app itself):

```bash
docker-compose up --build -d
```

- `--build` — builds the Java app Docker image
- `-d` — runs everything in the background (detached mode)

Verify all containers are running:

```bash
docker-compose ps
```

You should see all services with status `Up`.

---

### Step 3 — Wait ~30 seconds, then check health

Kafka and MySQL take a moment to initialize. Once ready:

```bash
curl http://localhost:8080/api/v1/admin/health
```

You should get a `200 OK` response.

---

### Step 4 — Send your first notification

```bash
curl -X POST http://localhost:8080/api/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "welcome-user-42-001",
    "userId": "user-42",
    "channel": "EMAIL",
    "priority": "HIGH",
    "templateCode": "WELCOME_EMAIL",
    "templateParams": {
      "userName": "Rahul",
      "appName": "FinanceApp"
    },
    "recipient": "rahul@example.com"
  }'
```

**What each field means:**

| Field | Description |
|-------|-------------|
| `idempotencyKey` | A unique ID you generate to prevent duplicate sends |
| `userId` | The user this notification belongs to |
| `channel` | Delivery method: `EMAIL`, `SMS`, or `PUSH` |
| `priority` | `HIGH` (urgent, like OTPs) or `LOW` (promotions) |
| `templateCode` | Which pre-defined template to use |
| `templateParams` | Values to fill in the template placeholders |
| `recipient` | Email address, phone number, or device token |

---

### Step 5 — Check delivery status

```bash
# By notification ID
curl http://localhost:8080/api/v1/notifications/1

# By the idempotency key you used
curl http://localhost:8080/api/v1/notifications/key/welcome-user-42-001

# Full audit trail — every status change with timestamps
curl http://localhost:8080/api/v1/admin/audit/1
```

---

### Step 6 — Run a load test

```bash
chmod +x load-test.sh
./load-test.sh 500   # Fires 500 notifications and measures throughput
```

---

## API Reference

### Send a Single Notification

```
POST /api/v1/notifications
```

**With a template:**
```json
{
  "idempotencyKey": "unique-key-123",
  "userId": "user-42",
  "channel": "EMAIL",
  "priority": "HIGH",
  "templateCode": "WELCOME_EMAIL",
  "templateParams": { "userName": "Rahul", "appName": "MyApp" },
  "recipient": "rahul@example.com",
  "metadata": { "source": "signup-flow" }
}
```

**With a raw message body (no template):**
```json
{
  "idempotencyKey": "raw-notif-001",
  "userId": "user-42",
  "channel": "SMS",
  "priority": "HIGH",
  "body": "Your OTP is 482913",
  "recipient": "+919876543210"
}
```

---

### Send a Batch

```
POST /api/v1/notifications/batch
```

```json
{
  "notifications": [
    { "idempotencyKey": "key-1", "userId": "user-1", "channel": "EMAIL", "..." },
    { "idempotencyKey": "key-2", "userId": "user-2", "channel": "SMS",   "..." }
  ]
}
```

---

### Get Notification Status

```
GET /api/v1/notifications/{id}
GET /api/v1/notifications/key/{idempotencyKey}
```

---

### List Notifications for a User

```
GET /api/v1/notifications/user/{userId}?page=0&size=20
```

---

### System Stats

```
GET /api/v1/notifications/stats
```

---

### Full Audit Trail

```
GET /api/v1/admin/audit/{notificationId}
```

Returns every status transition with timestamps — useful for debugging delivery issues.

---

### Prometheus Metrics

```
GET /actuator/prometheus
```

---

## Monitoring

The following metrics are exposed at `GET /actuator/prometheus` and can be visualized with a Grafana dashboard:

| Metric | What It Measures |
|--------|-----------------|
| `notification.received` | Total notifications accepted by the API |
| `notification.kafka.published` | Events successfully published to Kafka |
| `notification.consumer.processed` | Notifications successfully processed |
| `notification.consumer.skipped` | Duplicates blocked by idempotency check |
| `notification.dispatch.success` | Successful deliveries, per channel |
| `notification.dispatch.failure` | Failed deliveries, per channel |
| `notification.dispatch.duration` | How long delivery takes, per channel |
| `notification.retry.count` | Number of retry attempts made |
| `notification.dlq.count` | Notifications sent to the Dead Letter Queue |

---

## Resume-Ready Bullets

Use these when describing this project on your resume or in interviews:

> **Architected a high-throughput notification engine** in Java + Spring Boot, processing 50,000+ notifications/hour across Email, SMS, and Push channels via Apache Kafka with separate priority-tiered topics and consumer thread pools.

> **Designed idempotent message processing** using Redis-backed deduplication (SETNX), eliminating duplicate deliveries under Kafka consumer retries; implemented exponential backoff with Dead Letter Queue routing for failed dispatches.

> **Built a delivery status state machine** (QUEUED → DISPATCHED → DELIVERED/FAILED) with MySQL persistence, full audit logging, and a Redis cache layer enabling real-time delivery tracking with < 50ms read latency.

---

## License

MIT
