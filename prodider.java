# Quarkus Middleware / Guard dengan `ContainerRequestFilter`

## Konsep Arsitekturnya

```
Request → ContainerRequestFilter (ambil query param, query DB) 
        → simpan hasil ke @RequestScoped bean 
        → Resource/Controller tinggal inject bean tsb
```

Kunci utamanya: gunakan **`@RequestScoped` bean sebagai "carrier"** untuk passing data dari filter ke business logic. Karena scope-nya per request, aman dan tidak bocor antar request.

---

## 1. Buat Request Scoped Carrier Bean

```java
import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class TenantContext {

    private Tenant tenant; // atau entity apapun hasil query DB

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public boolean isLoaded() {
        return tenant != null;
    }
}
```

---

## 2. Buat Filter / Guard

```java
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.core.Response;
import java.io.IOException;

@Provider
@PreMatching // hapus ini kalau mau filter SETELAH routing (biasanya tanpa @PreMatching)
public class TenantGuard implements ContainerRequestFilter {

    @Inject
    TenantContext tenantContext;

    @Inject
    TenantRepository tenantRepository;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        
        String tenantId = requestContext.getUriInfo()
                                        .getQueryParameters()
                                        .getFirst("tenantId");

        // Validasi param ada
        if (tenantId == null || tenantId.isBlank()) {
            requestContext.abortWith(
                Response.status(Response.Status.BAD_REQUEST)
                        .entity("Missing tenantId")
                        .build()
            );
            return;
        }

        // Query ke DB
        Tenant tenant = tenantRepository.findById(Long.parseLong(tenantId));

        if (tenant == null) {
            requestContext.abortWith(
                Response.status(Response.Status.NOT_FOUND)
                        .entity("Tenant not found")
                        .build()
            );
            return;
        }

        // Simpan ke RequestScoped bean — bisa diakses di mana saja dalam request ini
        tenantContext.setTenant(tenant);
    }
}
```

---

## 3. Gunakan di Resource / Service

### Di Resource (Controller)
```java
@Path("/orders")
@ApplicationScoped
public class OrderResource {

    @Inject
    TenantContext tenantContext; // data sudah ada, tinggal pakai

    @Inject
    OrderService orderService;

    @GET
    public Response getOrders() {
        Tenant tenant = tenantContext.getTenant(); // langsung ambil
        return Response.ok(orderService.getOrdersByTenant(tenant)).build();
    }
}
```

### Di Service (Business Logic)
```java
@ApplicationScoped
public class OrderService {

    @Inject
    TenantContext tenantContext; // bisa inject langsung di service juga

    @Inject
    OrderRepository orderRepository;

    public List<Order> getOrdersByTenant(Tenant tenant) {
        return orderRepository.findByTenantId(tenant.getId());
    }
}
```

---

## 4. Kalau Mau Filter Hanya untuk Endpoint Tertentu — Pakai Custom Annotation

```java
// Buat annotation
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresTenant {}
```

```java
// Pasang annotation di filter
@Provider
@RequiresTenant // ← filter hanya aktif kalau endpoint ada annotation ini
public class TenantGuard implements ContainerRequestFilter {
    // ... sama seperti di atas
}
```

```java
// Pasang di endpoint yang butuh guard
@Path("/orders")
@RequiresTenant // ← guard aktif untuk semua method di class ini
public class OrderResource {
    ...
    
    @GET
    @RequiresTenant // ← atau per method
    public Response getOrders() { ... }
}
```

---

## Flow Lengkap

```
GET /orders?tenantId=123
        │
        ▼
  TenantGuard.filter()
        │
        ├─ Ambil query param "tenantId"
        ├─ Query DB → Tenant{id=123, name="Acme"}
        ├─ tenantContext.setTenant(tenant)
        │
        ▼
  OrderResource.getOrders()
        │
        ├─ tenantContext.getTenant() → langsung dapat Tenant{id=123}
        ├─ Tidak perlu query DB lagi
        │
        ▼
  OrderService → business logic pakai data tenant
```

---

## Reactive Version (Jika Pakai Quarkus Reactive)

Kalau DB query-nya pakai Panache Reactive / Hibernate Reactive:

```java
@Provider
public class TenantGuard implements ContainerRequestFilter {

    // Untuk reactive, lebih baik pakai @ServerRequestFilter dari RESTEasy Reactive

}
```

Ganti ke **RESTEasy Reactive filter**:

```java
import io.quarkus.vertx.http.runtime.filters.RouteFilter;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

public class TenantGuard {

    @Inject
    TenantContext tenantContext;

    @Inject
    TenantRepository tenantRepository; // Panache Reactive

    @ServerRequestFilter
    public Uni<Void> filter(ContainerRequestContext ctx) {
        String tenantId = ctx.getUriInfo().getQueryParameters().getFirst("tenantId");

        return Tenant.findById(Long.parseLong(tenantId)) // Panache active record
                .onItem().invoke(tenant -> tenantContext.setTenant((Tenant) tenant))
                .replaceWithVoid();
    }
}
```

Pendekatan ini clean, non-invasive ke business logic, dan reusable di semua endpoint yang butuh preprocessing yang sama.
