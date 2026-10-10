# Redis Cache Demo (Spring Boot + Redis + PostgreSQL)

A small project built to **explain Redis caching in an interview**. One product API, but it demonstrates
the five things interviewers actually ask about:

| Concept | Where in the code |
|---|---|
| Cache-aside pattern (read path) | `ProductService.getById` |
| Cache invalidation on write | `ProductService.update / delete` |
| TTL + jitter (stops mass expiry / "avalanche") | `ProductService.getById` (step 3) |
| Null caching (stops "penetration") | `NULL_MARKER` in `ProductService` |
| Atomic counters + rate limiting (INCR, EXPIRE) | `RateLimiter`, `stats:hits / stats:misses` |

---

## 1. What Redis is (30-second answer)

Redis is an **in-memory key-value data store**. Data lives in RAM, so reads take well under a millisecond,
versus several milliseconds (or more) for a disk-backed database like PostgreSQL. You put a Redis layer in
front of the database so repeated reads don't hit the DB.

Interview one-liner: *"A cache trades a little memory and some staleness risk for a big drop in latency and
database load."*

Beyond plain strings, Redis has lists, sets, sorted sets, hashes, streams, and atomic operations like `INCR`.
This demo uses strings (cached JSON) and counters.

## 2. Architecture

```
                 GET /api/products/1
   Client ───────────────────────────────▶ Spring Boot app
                                              │
                          1. GET product:1    │
                       ┌──────────────────────┤
                       ▼                      │
                    Redis ── HIT ─────────────┤──▶ return JSON  (source: CACHE, ~1-5 ms)
                       │                      │
                      MISS                    │
                       │  2. SELECT ...       │
                       ▼                      │
                  PostgreSQL ─────────────────┤
                       │  3. SET product:1 EX │
                       └──▶ Redis ────────────┘──▶ return JSON  (source: DATABASE, slow)
```

**Write path:** `PUT /api/products/1` → update PostgreSQL → `DEL product:1`. The next read is a miss and
refills the cache with fresh data.

## 3. Cache-aside, step by step

**Read**
1. App asks Redis for `product:{id}`.
2. Hit → return it. Increment `stats:hits`.
3. Miss → increment `stats:misses`, query the database.
4. Store the result in Redis with a TTL (default 120 s plus 0-30 s random jitter).
5. Return it.

**Write**
1. Update the database (the source of truth).
2. Delete the cache key. We *invalidate* instead of *updating* the cache because it is simpler and avoids
   races where two writers leave an old value in Redis.

**Why TTL?** It is a safety net. Even if an invalidation is missed, stale data disappears on its own.

**Why jitter?** If 10,000 keys were cached at the same moment with the same TTL, they would all expire together
and the database would take the full load at once (a **cache avalanche**). Random jitter spreads expiry out.

**Why cache "not found"?** Requests for ids that don't exist always miss, so every one would reach the DB
(**cache penetration**). Storing a `__NULL__` marker for 30 s absorbs that traffic.

## 3b. The same thing with Spring's cache annotations (v2)

Most Spring Boot projects don't write the Redis calls by hand. They use Spring's **cache abstraction**.
This project has both, side by side, so you can show the manual version (how it works underneath) and the
annotation version (how it is done in practice):

| | Manual (v1) | Annotations (v2) |
|---|---|---|
| Endpoint | `/api/products/{id}` | `/api/v2/products/{id}` |
| Code | `ProductService` | `CachedProductService` + `CacheConfig` |
| Redis key | `product:1` | `products::1` |

The pieces:

| Piece | What it does |
|---|---|
| `@EnableCaching` (on the main class) | Turns on annotation processing. Without it, the annotations are silently ignored. |
| `CacheConfig` (RedisCacheManager bean) | Sets the TTL and stores values as JSON instead of Java-serialized binary. |
| `@CacheConfig(cacheNames = "products")` | Class-level default cache name, so you don't repeat it on every method. |
| `@Cacheable(key = "#id")` | Hit: return the cached value and skip the method. Miss: run the method, cache the result. |
| `@CachePut(key = "#id")` | Always run the method, then overwrite the cache entry. Used on updates. |
| `@CacheEvict(key = "#id")` | Remove one entry. `allEntries = true` clears the whole cache. |
| `unless = "#result.stock == 0"` | Don't cache a result when this is true (evaluated after the method runs). |
| `condition = "..."` | (not used here) Only use the cache when this expression on the *inputs* is true. |
| `@Caching(...)` | (not used here) Combine several cache annotations on one method. |
| `Serializable` on `Product` | Needed if you use the default Java serialization. We use JSON, but it is kept as good practice. |

How it works: Spring wraps the service in a **proxy**. The proxy checks Redis before your method runs, so the
body of a `@Cacheable` method only executes on a miss. That is why `getById` logs `>>> CACHE MISS`.

Demo:
```bash
curl http://localhost:8080/api/v2/products/1   # slow (miss), look for ">>> CACHE MISS" in the app log
curl http://localhost:8080/api/v2/products/1   # fast (hit), no log line, body skipped
curl -X PUT http://localhost:8080/api/v2/products/1 -H "Content-Type: application/json" \
  -d '{"name":"Keyboard Pro","price":5999,"stock":20}'   # @CachePut refreshes the entry
curl http://localhost:8080/api/v2/products/1   # fast, and already shows the NEW data
curl -X DELETE http://localhost:8080/api/v2/products/cache   # @CacheEvict(allEntries = true)
```
In `redis-cli`: `KEYS products::*` then `GET products::1` shows the JSON.

**Annotation pitfalls (good interview answers):**
- *Self-invocation:* calling a `@Cacheable` method from another method **in the same class** bypasses the proxy,
  so no caching happens. Call it from another bean.
- *Only public methods* on Spring-managed beans are intercepted.
- *Return types must match:* if `@Cacheable` stores a `Product` and `@CachePut` stores something else under the same
  key, later reads break.
- *No TTL by default:* without `entryTtl` (or `spring.cache.redis.time-to-live`) entries never expire.
- *`#id` needs parameter names:* Spring Boot's Maven parent compiles with `-parameters`, so it works.
- *Put annotations on the service layer*, not on controllers.

## 4. Run it locally

Prerequisites: Java 17+, Maven, and Redis (Docker is easiest).

```bash
# 1. start Redis (and optional RedisInsight GUI on http://localhost:5540)
docker compose up -d

# 2. run the app (uses in-memory H2 as the database, no setup needed)
mvn spring-boot:run
```

No Docker? Install Redis natively (WSL on Windows, `brew install redis` on macOS, `apt install redis-server` on Linux).

### Demo script for the interview

```bash
# First call: cache MISS, slow (~300 ms because of the simulated DB latency)
curl http://localhost:8080/api/products/1
# -> {"product":{...},"source":"DATABASE","timeMs":312}

# Second call: cache HIT, fast
curl http://localhost:8080/api/products/1
# -> {"product":{...},"source":"CACHE","timeMs":2}

# See the hit ratio
curl http://localhost:8080/api/stats

# Update the product: the cache entry is invalidated
curl -X PUT http://localhost:8080/api/products/1 \
  -H "Content-Type: application/json" \
  -d '{"name":"Mechanical Keyboard Pro","price":5999,"stock":20}'

# Next read is a MISS again (fresh data), then HIT after that
curl http://localhost:8080/api/products/1

# Non-existent id: first MISS then null-cached
curl -i http://localhost:8080/api/products/999
```

Peek inside Redis while you demo:

```bash
docker compose exec redis redis-cli
> KEYS product:*          # fine on a laptop; use SCAN in production
> GET product:1
> TTL product:1           # seconds left before it expires
> MGET stats:hits stats:misses
```

### Endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/api/products` | list, not cached |
| GET | `/api/products/{id}` | cache-aside + rate limited (30/min per IP) |
| POST | `/api/products` | create |
| PUT | `/api/products/{id}` | update + invalidate |
| DELETE | `/api/products/{id}` | delete + invalidate |
| GET | `/api/stats` | hits, misses, hit ratio |
| GET | `/api/health` | health check |

## 5. Deploy on a free cloud (all free tiers, no credit card for the first two)

Three pieces, each on a different free service:

| Piece | Service | Free tier |
|---|---|---|
| Redis | **Upstash** | free database, TLS, REST + Redis protocol |
| PostgreSQL | **Neon** (or Supabase) | free Postgres |
| Spring Boot app | **Render** | free web service (spins down after ~15 min idle) |

> Free-tier limits and sign-up rules change. Check each site's pricing page before relying on them.

### Step 1: Push the project to GitHub
```bash
git init && git add . && git commit -m "Redis cache demo"
# create an empty repo on GitHub, then:
git remote add origin https://github.com/<you>/redis-cache-demo.git
git push -u origin main
```

### Step 2: Create Redis on Upstash
1. Sign up at upstash.com → **Create Database** → choose Redis, pick the region nearest your app (e.g. Mumbai if offered).
2. Open the database page and copy the **Endpoint** (host), **Port**, and **Password**.
3. TLS is on by default, so you will set `REDIS_SSL=true`.

### Step 3: Create PostgreSQL on Neon
1. Sign up at neon.tech → create a project.
2. From the connection details copy host, database, user and password.
3. Your JDBC URL is:
   `jdbc:postgresql://<host>/<database>?sslmode=require`

### Step 4: Deploy the app on Render
1. Sign up at render.com → **New → Web Service** → connect your GitHub repo.
2. Runtime: **Docker** (Render finds the `Dockerfile` automatically).
3. Instance type: **Free**.
4. Add these **Environment Variables**:

| Key | Value |
|---|---|
| `REDIS_HOST` | your Upstash endpoint |
| `REDIS_PORT` | your Upstash port (usually `6379`) |
| `REDIS_PASSWORD` | your Upstash password |
| `REDIS_SSL` | `true` |
| `DB_URL` | `jdbc:postgresql://<neon-host>/<db>?sslmode=require` |
| `DB_USER` | your Neon user |
| `DB_PASSWORD` | your Neon password |

5. Set the **Health Check Path** to `/api/health`, then **Create Web Service**.
6. Wait for the build (a few minutes). Render gives you a URL like `https://redis-cache-demo.onrender.com`.

### Step 5: Test the deployed app
```bash
curl https://<your-app>.onrender.com/api/products/1   # DATABASE (first call)
curl https://<your-app>.onrender.com/api/products/1   # CACHE
curl https://<your-app>.onrender.com/api/stats
```
You can also open Upstash's **Data Browser** and watch `product:1` appear and expire.

**Gotchas on free tiers**
- Render free services sleep after inactivity, so the first request can take 30-60 seconds. Hit it once before your interview or demo.
- Free instances have limited RAM; the Dockerfile caps the heap at 300 MB.
- Keep secrets in Render's env vars, never in the repo.
- Alternatives: Railway or Fly.io for the app; Redis Cloud (30 MB free) instead of Upstash; Supabase instead of Neon.

## 6. Interview questions you should be ready for

**Why is Redis fast?** Data is in memory, it uses efficient data structures, and command execution is single-threaded
(no lock contention), with I/O multiplexing for many connections.

**Cache-aside vs write-through vs write-behind?**
- *Cache-aside* (this project): app manages the cache; simple and common; first read after a miss is slow.
- *Write-through*: every write goes to cache and DB together; cache is always fresh, but writes are slower.
- *Write-behind*: write to cache, flush to DB asynchronously; fastest writes, but risk of data loss.

**How do you keep cache and DB consistent?** Short TTLs, invalidate on write (delete the key), and accept that
the cache is *eventually* consistent. For stronger guarantees use versioned keys or event-driven invalidation.

**Cache stampede (thundering herd)?** A hot key expires and many requests rebuild it at once. Mitigate with a
per-key lock or "single-flight", jittered TTLs, or refreshing popular keys before they expire.

**Avalanche vs penetration vs breakdown?**
- *Avalanche*: many keys expire together → add TTL jitter.
- *Penetration*: requests for non-existent data bypass the cache → cache nulls, or use a Bloom filter.
- *Breakdown/stampede*: one hot key expires → lock or refresh early.

**What happens when Redis is full?** Controlled by `maxmemory` and an eviction policy such as `allkeys-lru`
(evict least recently used) or `volatile-ttl`. Caches usually use `allkeys-lru`.

**Persistence?** RDB (periodic snapshots) and AOF (append-only log of writes). As a pure cache you can run with
persistence off.

**Redis vs Memcached?** Redis has rich data types, persistence, replication, clustering, pub/sub and Lua scripts;
Memcached is a simpler multi-threaded string cache.

**How does your rate limiter work?** `INCR rate:{ip}:{minute}`; if the result is 1, set `EXPIRE`; reject if the
count exceeds the limit. `INCR` is atomic, so it is correct across many app instances. It is a *fixed window*
limiter; it allows bursts at window edges. Sliding window or token bucket (often with a Lua script) is stricter.

**Manual Redis calls vs `@Cacheable`?** This project has both (section 3b). Annotations are shorter and keep caching
out of business logic. Manual `RedisTemplate` code gives full control: custom TTL per key, jitter, negative caching,
hit/miss counters, atomic operations like `INCR`. In practice, use annotations for simple read-through caching and
`RedisTemplate` for anything else (rate limiting, counters, locks, leaderboards).

**Why can a `@Cacheable` method fail to cache?** Self-invocation (the proxy is bypassed), a non-public method,
a missing `@EnableCaching`, or an exception being thrown (exceptions are not cached).

**Java serialization vs JSON in the cache?** Java serialization needs `Serializable`, produces unreadable binary,
and breaks when the class changes. JSON (`GenericJackson2JsonRedisSerializer`) is readable in `redis-cli` and
more tolerant of change.

**What would you do next to productionise this?** Redis failure handling (fall back to the DB if Redis is down),
metrics (hit ratio in Prometheus/Micrometer), circuit breaker, Redis Sentinel/Cluster for HA, and a distributed
lock for stampede protection.

## 7. Project layout

```
redis-cache-demo/
├── pom.xml
├── Dockerfile                  # multi-stage build for Render/Railway/Fly
├── docker-compose.yml          # local Redis + RedisInsight
└── src/main
    ├── resources/application.yml      # all config via env vars
    └── java/com/avi/rediscache
        ├── RedisCacheApplication.java
        ├── DataSeeder.java            # 5 demo products on first start
        ├── model/Product.java
        ├── repo/ProductRepository.java
        ├── config/CacheConfig.java       # RedisCacheManager: JSON + TTL (for the annotations)
        ├── service/ProductService.java   # <- the heart: manual cache-aside
        ├── service/CachedProductService.java  # same thing with @Cacheable/@CachePut/@CacheEvict
        ├── web/CachedProductController.java   # /api/v2/products
        ├── service/RateLimiter.java      # INCR + EXPIRE
        ├── service/ProductResult.java
        └── web/ProductController.java
```
