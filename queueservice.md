# Dokumentasi Queue Service - Kafka Consumer dengan Dynamic Routing

## Overview

Queue Service ini adalah sistem yang dirancang untuk mempermudah penggunaan Kafka Consumer di lingkungan microservices. Dengan menggunakan annotation `@QueueRoute`, developer dapat mendaftarkan consumer handler dengan mudah tanpa perlu konfigurasi manual yang kompleks.

## Arsitektur

### Flow Diagram

```
Service Lain → HTTP Request → Producer Service → Kafka Topic → Consumer Handler
                    ↓
              /general/queue/{topicName}
                    ↓
              Kafka Producer mengirim message
                    ↓
              DynamicConsumer menerima & memetakan
                    ↓
              Method dengan @QueueRoute dipanggil
```

### Komponen Utama

1. **@QueueRoute Annotation**: Annotation untuk mendaftarkan consumer handler
2. **DynamicConsumerRegistry**: Class yang melakukan scanning dan registrasi handler saat aplikasi startup
3. **DynamicConsumer**: Class yang menerima message dari Kafka dan memetakan ke handler yang sesuai
4. **Producer API**: Endpoint HTTP untuk menerima request queue dari service lain

## Cara Penggunaan

### 1. Mendaftarkan Consumer Handler

Untuk membuat consumer handler baru, cukup tambahkan annotation `@QueueRoute` pada method Anda:

```java
@ApplicationScoped
public class MyQueueHandlers {
    
    @QueueRoute(
        value = "user-registration-process",
        description = "Process user registration with email verification",
        concurrency = 3,
        maxPollRecords = 10,
        timeout = 30000,
        maxRetries = 3,
        partitions = 3,
        priority = 1
    )
    public Uni<Void> handleUserRegistration(QueueMessage message) {
        // Your business logic here
        String userId = message.getBody().getString("userId");
        String email = message.getBody().getString("email");
        
        return processRegistration(userId, email)
            .onFailure().retry().atMost(3);
    }
}
```

### 2. Mengirim Message dari Service Lain

Service lain dapat mengirim message ke queue service melalui HTTP request:

**Endpoint**: `POST /general/queue/{topicName}`

**Request Body**:
```json
{
  "key": "unique-transaction-id-12345",
  "body": {
    "userId": "user-123",
    "email": "user@example.com",
    "timestamp": "2025-12-12T10:30:00Z"
  }
}
```

**Contoh dengan Kubernetes App Reference**:
```java
@RestClient
@RegisterRestClient(configKey = "queue-service")
public interface QueueServiceClient {
    
    @POST
    @Path("/general/queue/{topicName}")
    Uni<Response> sendToQueue(
        @PathParam("topicName") String topicName,
        QueueRequest request
    );
}
```

**application.properties**:
```properties
quarkus.rest-client.queue-service.url=http://queue-service:8080
```

## Parameter @QueueRoute

| Parameter | Tipe | Default | Deskripsi |
|-----------|------|---------|-----------|
| `value` | String | **(Required)** | Nama topic Kafka yang akan di-consume |
| `description` | String | `""` | Deskripsi handler untuk keperluan dokumentasi |
| `concurrency` | int | `1` | Jumlah concurrent consumers yang akan berjalan |
| `maxPollRecords` | int | `500` | Maksimal records yang di-poll dalam satu waktu |
| `maxPollIntervalMs` | int | `300000` | Interval maksimal polling dalam millisecond (default: 5 menit) |
| `sessionTimeoutMs` | int | `10000` | Session timeout untuk Kafka consumer (default: 10 detik) |
| `timeout` | long | `30000` | Timeout untuk eksekusi fungsi handler dalam millisecond (default: 30 detik) |
| `maxRetries` | int | `3` | Jumlah maksimal retry ketika processing gagal |
| `partitions` | int | `1` | Jumlah partition untuk topic Kafka |
| `enableDlq` | boolean | `true` | Enable/disable Dead Letter Queue untuk message yang gagal |
| `priority` | int | `0` | Priority level topic (semakin tinggi semakin prioritas) |

## Cara Kerja Internal

### 1. Application Startup

Saat aplikasi Quarkus dijalankan:

```java
@ApplicationScoped
public class DynamicConsumerRegistry {
    
    @Observes
    void onStart(@Observes StartupEvent event) {
        // Scanning semua class dan method yang memiliki @QueueRoute
        scanHandlers();
    }
    
    private void scanHandlers() {
        // 1. Scan classpath untuk mencari @QueueRoute annotation
        // 2. Ekstrak metadata (topic, concurrency, dll)
        // 3. Registrasi handler ke internal registry
        // 4. Buat Kafka consumer configuration untuk setiap topic
        // 5. Inisialisasi DynamicConsumer dengan mapping topic -> handler
    }
}
```

### 2. Message Flow

```
1. Service A → POST /general/queue/user-registration-process
              Body: { key: "txn-123", body: {...} }
   
2. Producer Service → Kafka Producer send message
              Topic: user-registration-process
              Key: txn-123
              Value: { body: {...} }

3. DynamicConsumer → Poll message dari Kafka
              - Identifikasi topic
              - Cari handler yang terdaftar untuk topic tersebut
              - Invoke method dengan @QueueRoute

4. Handler Method → Eksekusi business logic
              - Success: commit offset
              - Failure: retry sesuai maxRetries
              - Final failure: kirim ke DLQ (jika enableDlq=true)
```

### 3. DynamicConsumer Processing

```java
@ApplicationScoped
public class DynamicConsumer {
    
    // Map: topic → handler method
    private Map<String, HandlerMetadata> handlers = new ConcurrentHashMap<>();
    
    @Incoming("dynamic-kafka-consumer")
    public Uni<Void> consume(ConsumerRecord<String, JsonObject> record) {
        String topic = record.topic();
        HandlerMetadata handler = handlers.get(topic);
        
        if (handler == null) {
            Log.error("No handler found for topic: " + topic);
            return Uni.createFrom().voidItem();
        }
        
        QueueMessage message = new QueueMessage(
            record.key(),
            record.value()
        );
        
        return invokeHandler(handler, message)
            .onFailure().retry()
                .atMost(handler.getMaxRetries())
            .onFailure().invoke(t -> 
                sendToDlq(topic, message, t)
            );
    }
}
```

## Contoh Penggunaan Lengkap

### Scenario: Order Processing Service

```java
@ApplicationScoped
public class OrderQueueHandlers {
    
    @Inject
    OrderService orderService;
    
    @Inject
    NotificationService notificationService;
    
    @Inject
    PaymentService paymentService;
    
    /**
     * Process order creation
     * - High priority
     * - Multiple concurrent consumers
     * - Short timeout for quick processing
     */
    @QueueRoute(
        value = "order-creation",
        description = "Handle new order creation with inventory check",
        concurrency = 5,
        maxPollRecords = 20,
        timeout = 15000,
        maxRetries = 2,
        partitions = 5,
        priority = 2
    )
    public Uni<Void> handleOrderCreation(QueueMessage message) {
        String orderId = message.getBody().getString("orderId");
        
        return orderService.createOrder(orderId)
            .chain(order -> notificationService.sendOrderConfirmation(order))
            .replaceWithVoid();
    }
    
    /**
     * Process payment
     * - Lower concurrency (payment gateway limitation)
     * - Longer timeout
     * - More retries (payment might be temporarily unavailable)
     */
    @QueueRoute(
        value = "payment-processing",
        description = "Process payment for completed orders",
        concurrency = 2,
        maxPollRecords = 5,
        timeout = 60000,
        maxRetries = 5,
        partitions = 3,
        priority = 3
    )
    public Uni<Void> handlePaymentProcessing(QueueMessage message) {
        String orderId = message.getBody().getString("orderId");
        String paymentMethod = message.getBody().getString("paymentMethod");
        
        return paymentService.processPayment(orderId, paymentMethod)
            .chain(result -> orderService.updatePaymentStatus(orderId, result))
            .replaceWithVoid();
    }
    
    /**
     * Generate invoice (low priority, batch processing)
     */
    @QueueRoute(
        value = "invoice-generation",
        description = "Generate invoice PDF for paid orders",
        concurrency = 1,
        maxPollRecords = 50,
        timeout = 120000,
        maxRetries = 3,
        partitions = 1,
        priority = 0
    )
    public Uni<Void> handleInvoiceGeneration(QueueMessage message) {
        String orderId = message.getBody().getString("orderId");
        
        return orderService.generateInvoice(orderId)
            .chain(pdf -> orderService.saveInvoice(orderId, pdf))
            .replaceWithVoid();
    }
}
```

### Mengirim dari Service Lain

```java
@ApplicationScoped
public class CheckoutService {
    
    @RestClient
    @Inject
    QueueServiceClient queueService;
    
    public Uni<Order> checkout(CheckoutRequest request) {
        return createOrder(request)
            .chain(order -> {
                // Send to order creation queue
                QueueRequest queueReq = new QueueRequest(
                    order.getId(),
                    JsonObject.mapFrom(order)
                );
                
                return queueService.sendToQueue("order-creation", queueReq)
                    .replaceWith(order);
            });
    }
}
```

## Best Practices

### 1. Naming Convention
```java
// Topic name: kebab-case
@QueueRoute(value = "user-profile-update")

// Handler method: descriptive action
public Uni<Void> handleUserProfileUpdate(QueueMessage message)
```

### 2. Error Handling
```java
@QueueRoute(value = "critical-process", maxRetries = 5)
public Uni<Void> handleCriticalProcess(QueueMessage message) {
    return processData(message)
        .onFailure().invoke(throwable -> {
            // Log error dengan context
            Log.error("Failed to process: " + message.getKey(), throwable);
            // Send alert jika perlu
            alertService.sendCriticalAlert(throwable);
        })
        .replaceWithVoid();
}
```

### 3. Timeout Configuration
```java
// Quick operations (< 10s)
@QueueRoute(value = "cache-invalidation", timeout = 5000)

// Database operations (10-30s)
@QueueRoute(value = "data-migration", timeout = 30000)

// Heavy processing (> 30s)
@QueueRoute(value = "video-encoding", timeout = 300000)
```

### 4. Concurrency Tuning
```java
// CPU intensive → Low concurrency
@QueueRoute(value = "image-processing", concurrency = 2)

// I/O intensive → High concurrency
@QueueRoute(value = "api-webhook-call", concurrency = 10)

// Mixed workload → Medium concurrency
@QueueRoute(value = "order-processing", concurrency = 5)
```

## Monitoring & Troubleshooting

### Dead Letter Queue (DLQ)

Ketika message gagal setelah mencapai `maxRetries`, message akan dikirim ke DLQ (jika `enableDlq=true`):

- **DLQ Topic Pattern**: `{original-topic}.dlq`
- **Example**: `order-creation` → `order-creation.dlq`

### Logs

Monitor logs untuk tracking:
```
[DynamicConsumerRegistry] Registered handler: order-creation (concurrency=5, partitions=5)
[DynamicConsumer] Processing message: txn-12345 from topic: order-creation
[DynamicConsumer] Successfully processed: txn-12345 in 1234ms
[DynamicConsumer] Failed to process: txn-12345, retry 1/3
[DLQHandler] Sent to DLQ: txn-12345 from order-creation
```

## Troubleshooting

### Message tidak terproses

**Cek**:
1. Apakah handler sudah terdaftar? Lihat log startup
2. Apakah topic name di producer sama dengan `@QueueRoute.value`?
3. Apakah method signature benar? Harus terima `QueueMessage` dan return `Uni<Void>`

### Performance Issues

**Solusi**:
1. Increase `concurrency` untuk parallel processing
2. Tune `maxPollRecords` sesuai kebutuhan
3. Adjust `timeout` sesuai kompleksitas business logic
4. Scale service secara horizontal di Kubernetes

### Message masuk DLQ

**Investigasi**:
1. Check DLQ topic untuk melihat failed messages
2. Review error logs untuk root cause
3. Fix issue dan replay message dari DLQ

## Notes

- Pastikan class `DynamicConsumerRegistry` dan `DynamicConsumer` sudah terimplementasi dengan benar
- Message format harus sesuai: `{ key: string, body: JsonObject }`
- Topic akan dibuat otomatis saat aplikasi startup (jika auto-create enabled)
- Partition count tidak bisa diubah setelah topic dibuat

## Referensi Class

Untuk detail implementasi, silakan cek:
- `DynamicConsumerRegistry` - Scanning dan registrasi handlers
- `DynamicConsumer` - Consumer processing logic
- `@QueueRoute` - Annotation definition dan processor

---

**Version**: 1.0  
**Last Updated**: December 2025
