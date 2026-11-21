# Vehicle Savvy: Working Plan

> **Status:** Planning · **Started:** 2026-11-21 · **Last updated:** 2026-11-21 (renamed to Vehicle Savvy)
> **Current phase:** Phase 0 (Setup)

A small **full-stack app** for looking up a vehicle by VIN and seeing its open safety recalls.
- **Frontend:** React + **TypeScript** (Vite), hosted on **Vercel**
- **Backend:** Java **Spring Boot** REST API that calls free public NHTSA vehicle APIs and combines the results, deployed to **Red Hat OpenShift**
- **No database.** Monorepo with `frontend/` and `backend/`.

---

## 1. Goals
- **Learn Java and Spring Boot hands-on**, building on my JVM backend experience with Kotlin + Ktor.
- **Build a typed React frontend** in TypeScript that handles real-world API states well: loading, errors, partial data.
- **Practice integrating with other services:** calling external APIs, mapping responses, and orchestrating dependent calls.
- **Build for reliability:** validation on both sides, clear error handling, timeouts, graceful degradation and caching.
- **Ship it properly:** automated tests on both sides, a container image, CI pipelines, and deployments to Vercel and OpenShift.

**Definition of done:** a working, tested, deployed app. Not perfect or feature-rich.

---

## 2. Architecture

```
┌──────────────┐  HTTPS   ┌──────────────────────┐  HTTPS   ┌─────────────────────────┐  HTTPS  ┌──────────────────┐
│   Browser    │ ───────> │  Vercel (CDN)        │          │  OpenShift Route        │ ──────> │  NHTSA APIs      │
│              │ <─────── │  React + TS static   │          │  → Spring Boot (Java 21)│ <────── │  vPIC + Recalls  │
│              │          │  build               │          │    in-memory cache      │         │                  │
│              │ ──────────── fetch /api/... (CORS) ──────>  │                         │         │                  │
└──────────────┘                                            └─────────────────────────┘         └──────────────────┘
```
- The browser loads the React app from **Vercel**, then calls the backend **directly** at its OpenShift Route URL.
- Frontend and backend are on **different domains**, so the backend allows the Vercel origin via **CORS**.
- Locally, Vite's **dev proxy** forwards `/api` to `localhost:8080`, so there's no CORS friction during development.

---

## 3. Features

### User-facing (frontend)
1. Enter a VIN → see **vehicle details** (year, make, model, body, fuel).
2. See **open recalls** for that vehicle (campaign number, component, summary, remedy).
3. Clear feedback for every state: **invalid VIN, loading, not found, service down, recalls unavailable (partial result), no recalls**.
4. (Nice to have) Recent searches kept in the browser (`localStorage`), and a shareable URL (`?vin=...`).

### API (backend)
| Endpoint | What it does | External call(s) |
|---|---|---|
| `GET /api/vehicles/{vin}` | Decode a VIN → make, model, year, body, fuel | vPIC DecodeVinValues |
| `GET /api/vehicles/recalls?make=&model=&year=` | Recalls for a vehicle | NHTSA recallsByVehicle |
| `GET /api/vehicles/{vin}/summary` | **Decode + recalls, combined.** The frontend's main call. | Both, **one after the other** |
| `GET /actuator/health` (+ `/liveness`, `/readiness`) | Health checks for deploys and OpenShift probes | none |

### The summary flow
```
GET /api/vehicles/{vin}/summary
   ├─ 1. Validate VIN (17 chars, no I/O/Q) ── bad → 400
   ├─ 2. Call vPIC to decode VIN ───────────── down → 502/503
   │        └─ gives make, model, year
   ├─ 3. Call recalls API (needs step 2's result)
   │        └─ down → still return vehicle info, "recalls": UNAVAILABLE (partial result)
   └─ 4. Combine → clean JSON response
```
**Key design point:** the recalls API is queried by **make/model/year, not VIN**, so the calls are **chained**: call 2 depends on call 1.

---

## 4. API contract (shared by both sides)

**Success: `200 GET /api/vehicles/{vin}/summary`**
```json
{
  "vin": "1HGCM82633A004352",
  "vehicle": { "year": 2003, "make": "HONDA", "model": "Accord", "bodyClass": "Coupe", "fuelType": "Gasoline" },
  "recalls": {
    "status": "OK",
    "count": 1,
    "items": [ { "campaignNumber": "...", "component": "...", "summary": "...", "remedy": "...", "reportDate": "..." } ]
  }
}
```
**Partial result:** same shape, with `"recalls": { "status": "UNAVAILABLE", "count": 0, "items": [] }`

**Error (all non-2xx responses):**
```json
{ "timestamp": "2026-11-21T20:00:00Z", "status": 400, "error": "Bad Request", "code": "INVALID_VIN", "message": "VIN must be 17 characters (no I, O or Q).", "path": "/api/vehicles/ABC/summary" }
```
| Status | `code` | Meaning | Frontend shows |
|---|---|---|---|
| 400 | `INVALID_VIN` | Failed validation | Inline form error |
| 404 / 422 | `VEHICLE_NOT_FOUND` | NHTSA couldn't decode it | "We couldn't find that vehicle" |
| 502 / 503 | `UPSTREAM_UNAVAILABLE` | NHTSA down or timed out | "Vehicle service is unavailable, try again" + retry button |
| 500 | `INTERNAL_ERROR` | Unexpected | Generic error + retry |

**TypeScript types (frontend mirror of the backend DTOs):**
```ts
export interface Vehicle { year: number; make: string; model: string; bodyClass: string | null; fuelType: string | null; }
export interface Recall { campaignNumber: string; component: string; summary: string; remedy: string | null; reportDate: string | null; }
export interface Recalls { status: "OK" | "UNAVAILABLE"; count: number; items: Recall[]; }
export interface VehicleSummary { vin: string; vehicle: Vehicle; recalls: Recalls; }
export interface ApiError { timestamp: string; status: number; error: string; code: string; message: string; path: string; }
```
**Rule:** if a backend DTO changes, update these types in the **same commit** (monorepo benefit). Stretch: generate them from OpenAPI.

---

## 5. External APIs (free, no key)
| API | Endpoint | Notes |
|---|---|---|
| **vPIC VIN decoder** | `https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/{vin}?format=json` | Flat JSON, ~140 fields. Missing fields = NHTSA has no data. Errors come back in the `ErrorCode`/`ErrorText` fields (not always an HTTP error). Rate-limited. |
| **NHTSA recalls** | `https://api.nhtsa.gov/recalls/recallsByVehicle?make={make}&model={model}&modelYear={year}` | Looked up by make/model/year. |

**Test VIN:** `1HGCM82633A004352` (a common example VIN). Add more as I find them.

**Gotchas to handle:**
- vPIC can return **HTTP 200 with an error inside** (e.g. an invalid VIN). Read `ErrorCode`, don't trust the status code alone.
- Rate limiting → caching helps.
- Model names may not match exactly between the two APIs → note any mismatches here.

---

## 6. Tech stack

### 6a. Backend
| Piece | Choice | Ktor equivalent |
|---|---|---|
| Language | Java 21 (Temurin) | Kotlin |
| Framework | Spring Boot (latest stable) | Ktor |
| Build | Gradle (Kotlin DSL) | Gradle |
| HTTP client | Spring `RestClient` | Ktor HttpClient |
| JSON | Jackson (auto-configured) | kotlinx.serialization + ContentNegotiation |
| Validation | Bean Validation (`@Valid`, `@Pattern`) | RequestValidation plugin / manual |
| Error handling | `@RestControllerAdvice` | StatusPages |
| Dependency injection | Spring's built-in DI | Manual / Koin |
| CORS | `WebMvcConfigurer` with allowed origins from config | CORS plugin |
| Caching | Spring Cache `@Cacheable` (in-memory) | Manual |
| Resiliency | Timeouts on RestClient (+ optional Resilience4j) | Manual |
| Tests | JUnit 5, `@WebMvcTest`, `MockRestServiceServer` | `testApplication {}` |
| Health | Spring Boot Actuator | Custom route |
| Container | Multi-stage Dockerfile (JDK build → JRE run) | Same |

### 6b. Frontend
| Piece | Choice | Why |
|---|---|---|
| Language | **TypeScript** (`strict: true`) | Typed API contract; catch mistakes at compile time |
| UI library | **React** | Component model I know well |
| Build tool / dev server | **Vite** (`react-ts` template) | Fast dev server, simple config, dev proxy, static build for Vercel |
| Runtime | Node.js LTS + npm | Standard |
| Data fetching | **TanStack Query** (D10) | Handles loading/error/retry/caching state cleanly |
| Styling | **Tailwind CSS** (D11) | Fast to build a clean, responsive UI |
| Forms / validation | Controlled input + a shared VIN regex (`lib/vin.ts`) | Same rule as the backend's `@Pattern`; instant feedback |
| Runtime response checking | (Optional) **Zod** | Validate API responses at runtime, not just compile time |
| Tests | **Vitest** + **React Testing Library** + **MSW** (Mock Service Worker) | Fast unit/component tests; MSW fakes the API so tests never call the backend |
| Linting / formatting | ESLint + Prettier | Consistent code; runs in CI |
| Hosting | **Vercel** (root dir `frontend/`) | Auto deploys, preview URLs per PR |

### 6c. Configuration
All settings that change between environments, where each one is **defined**, and how it's **set locally and in the cloud**.

**Principles**
- **Same code and same image everywhere; the environment supplies the differences** (12-factor). No environment-specific values are hard-coded, and there are no "if local / if prod" checks in the code.
- **Defaults live with the code** (`application.yml`, `.env.example`) so the app runs locally with zero setup; **the cloud overrides only what differs**.
- **Environment variables are the delivery mechanism** on every platform.
- **Build time vs run time:** frontend `VITE_*` values are baked into the JS bundle at **build time** and are **public**. Backend values are read at **run time** on the server.
- **Secrets: none for now** (see "Secrets" below). Every value in this section is safe to commit or view.

**Environments**
| Environment | Frontend | Backend |
|---|---|---|
| **Local** | Vite dev server `http://localhost:5173` (proxies `/api` → `:8080`) | `./gradlew bootRun` or Docker on `http://localhost:8080` |
| **Preview** | Vercel preview deploy per PR | *(none; uses the production backend)* |
| **Production** | Vercel production (`main`) | OpenShift Developer Sandbox (Route URL) |

#### Backend configuration (Spring Boot)
| Env var | Spring property | Purpose | Default (in `application.yml`) | **Local** | **Cloud** (where it's set) | Read at |
|---|---|---|---|---|---|---|
| `NHTSA_VPIC_BASE_URL` | `app.nhtsa.vpic-base-url` | vPIC API base URL | `https://vpic.nhtsa.dot.gov/api` | default | default (override in **ConfigMap** only if needed) | Run time |
| `NHTSA_RECALLS_BASE_URL` | `app.nhtsa.recalls-base-url` | Recalls API base URL | `https://api.nhtsa.gov` | default | default (override in **ConfigMap** only if needed) | Run time |
| `NHTSA_CONNECT_TIMEOUT` | `app.nhtsa.connect-timeout` | Connect timeout | `2s` | default | **ConfigMap** | Run time |
| `NHTSA_READ_TIMEOUT` | `app.nhtsa.read-timeout` | Read timeout | `5s` | default | **ConfigMap** | Run time |
| `APP_CORS_ALLOWED_ORIGINS` | `app.cors.allowed-origins` | Comma-separated frontend origins allowed to call the API | `http://localhost:5173` | default | **ConfigMap** → Vercel production domain | Run time |
| `APP_CACHE_TTL` | `app.cache.ttl` | How long cached NHTSA results live | `10m` | default | **ConfigMap** | Run time |
| `APP_CACHE_MAX_SIZE` | `app.cache.max-size` | Max cached entries | `500` | default | **ConfigMap** | Run time |
| `SPRING_PROFILES_ACTIVE` | `spring.profiles.active` | Active profile | *(none)* | `local` *(optional, for `application-local.yml`)* | *(not set)* | Startup |
| `SERVER_PORT` | `server.port` | HTTP port | `8080` | default | default (matches `containerPort` / Service) | Startup |
| `LOGGING_LEVEL_COM_JEREMIAH` | `logging.level.com.jeremiah` | App log level | `INFO` | `DEBUG` *(optional)* | default, or **ConfigMap** when debugging | Startup |
| `JAVA_TOOL_OPTIONS` | *(JVM flag, not Spring)* | JVM memory sizing for containers | *(none)* | *(not set)* | **`deployment.yaml` `env`** → `-XX:MaxRAMPercentage=75` | JVM start |

**Actuator settings** (not environment-specific; fixed in `application.yml`): expose `health` and `info`, enable `management.endpoint.health.probes.enabled=true` for `/actuator/health/liveness` and `/readiness`.

#### Frontend configuration (Vite + React)
| Env var | Purpose | Default (`.env.example`) | **Local** (`frontend/.env.local`, gitignored) | **Cloud** (where it's set) | Read at |
|---|---|---|---|---|---|
| `VITE_API_BASE_URL` | Backend base URL the browser calls | *(empty)* | *(empty)* → relative `/api` goes through the Vite proxy | **Vercel → Project → Settings → Environment Variables** (Production; optionally Preview) = backend **Route URL** | **Build time** |
| `VITE_REQUEST_TIMEOUT_MS` | Client-side request timeout | `10000` | default | Vercel (only if changing) | Build time |

**Dev-only (not an env var):** the Vite proxy target `http://localhost:8080` lives in `vite.config.ts` and only affects `npm run dev`.

#### CI configuration (GitHub Actions)
| Setting | Value | Where it's set |
|---|---|---|
| Java version / distribution | `21` / `temurin` | `backend-ci.yml` (`actions/setup-java`) |
| Node version | LTS | `frontend-ci.yml` (`actions/setup-node`) |
| Image name | `ghcr.io/<github-user>/vehicle-savvy-backend` | `backend-ci.yml` (`${{ github.repository_owner }}`) |
| Image tag | commit SHA (`${{ github.sha }}`) | `backend-ci.yml` |
| Registry auth | built-in `GITHUB_TOKEN` (`permissions: packages: write`) | Automatic, no stored secret |
| Path filters | `backend/**`, `frontend/**` | Each workflow's `on:` block |

#### Where each layer lives
```
LOCAL                                         CLOUD
backend/src/main/resources/application.yml    (same file, baked into the image) → defaults everywhere
backend/src/main/resources/application-local.yml   ✗ not used
   (optional, gitignored)
VS Code launch.json env / shell env vars      backend/openshift/configmap.yaml   → overrides via envFrom
                                              backend/openshift/deployment.yaml  → JAVA_TOOL_OPTIONS
frontend/.env.example (committed)             (documentation only)
frontend/.env.local (gitignored)              Vercel project env vars (Production / Preview)
frontend/vite.config.ts (dev proxy)           ✗ not used in production builds
```

#### How the pieces look
**`application.yml`** (committed; defaults with env-var overrides):
```yaml
server:
  port: ${SERVER_PORT:8080}
app:
  nhtsa:
    vpic-base-url: ${NHTSA_VPIC_BASE_URL:https://vpic.nhtsa.dot.gov/api}
    recalls-base-url: ${NHTSA_RECALLS_BASE_URL:https://api.nhtsa.gov}
    connect-timeout: ${NHTSA_CONNECT_TIMEOUT:2s}
    read-timeout: ${NHTSA_READ_TIMEOUT:5s}
  cors:
    allowed-origins: ${APP_CORS_ALLOWED_ORIGINS:http://localhost:5173}
  cache:
    ttl: ${APP_CACHE_TTL:10m}
    max-size: ${APP_CACHE_MAX_SIZE:500}
management:
  endpoints.web.exposure.include: health,info
  endpoint.health.probes.enabled: true
```
Bound to a typed, validated `@ConfigurationProperties` record (`AppProperties`), so bad or missing values fail at startup.

**`backend/openshift/configmap.yaml`** (committed; production overrides only):
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: vehicle-savvy-config
data:
  APP_CORS_ALLOWED_ORIGINS: "https://<vercel-production-domain>"
  NHTSA_CONNECT_TIMEOUT: "2s"
  NHTSA_READ_TIMEOUT: "5s"
  APP_CACHE_TTL: "10m"
```
Loaded in `deployment.yaml` with `envFrom: - configMapRef: { name: vehicle-savvy-config }`.

**`frontend/src/config.ts`** (the only file that reads `import.meta.env`):
```ts
export const config = {
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? "",
  requestTimeoutMs: Number(import.meta.env.VITE_REQUEST_TIMEOUT_MS ?? 10000),
};
```

**Local env vars in VS Code:** set them in `.vscode/launch.json` (`"env": { ... }`) for the Spring Boot run/debug configuration. Commit `launch.json` only with non-secret values; anything sensitive goes in a gitignored file instead.

#### Precedence (backend)
`command-line args` > **`environment variables` (ConfigMap / VS Code launch config / shell)** > `application-<profile>.yml` > `application.yml` defaults.

#### Changing a setting
| Where | How | Takes effect |
|---|---|---|
| Local backend | Edit `env` in `.vscode/launch.json`, shell env vars, or `application-local.yml` | Restart the app |
| Local frontend | Edit `.env.local` | Restart `npm run dev` |
| OpenShift | Edit `configmap.yaml` → `oc apply -f backend/openshift/configmap.yaml` → `oc rollout restart deployment/vehicle-savvy-backend` | After pods restart (no image rebuild) |
| Vercel | Change the env var in project settings → **redeploy** | After the new build (build-time value) |
| CI | Edit the workflow YAML → commit | Next workflow run |

#### Secrets
- **None for now.** NHTSA needs no API key, GHCR uses the automatic short-lived `GITHUB_TOKEN`, and OpenShift deploys use a short-lived `oc login` token that is never stored. A secrets feature was considered and **deferred** (see D13).
- **If a secret is added later:** read it the same way as other config (an env var bound to a property). Locally from shell env vars or a gitignored file; in the cloud from an **OpenShift Secret** created with `oc create secret` (never committed with values) and injected with `secretKeyRef`/`envFrom: secretRef`; in CI from GitHub environment secrets. **Never** in a `VITE_*` variable.

---

## 7. Decisions
| # | Decision | Choice | Status | Why |
|---|---|---|---|---|
| D1 | Database | **None** (stateless) | ✅ Decided — **ADR-001** | Simpler, free to host, focus on integrations |
| D2 | Java version / JDK | **Java 21, Temurin** | ✅ Decided | LTS, free, widely used |
| D3 | Backend build tool | **Gradle (Kotlin DSL)** | ✅ Decided | Familiar from Kotlin |
| D4 | CI platform | **GitHub Actions** | ✅ Decided — **ADR-004** | Backend image → GHCR with the built-in `GITHUB_TOKEN`; frontend checks |
| D5 | Backend hosting | **Red Hat OpenShift (Developer Sandbox)**, image from **GHCR**, **manual** `oc` deploys | ✅ Decided — **ADR-004** | Enterprise Kubernetes platform experience; free |
| D6 | Repo visibility | **Public** GitHub repo | ✅ Decided (2026-11-21) | Free CI minutes, GitHub secret scanning + push protection, open portfolio project |
| D7 | Frontend hosting | **Vercel** | ✅ Decided — **ADR-003** | Easiest frontend deploys, preview URLs, free for personal projects |
| D8 | Repo layout | **Monorepo** (`frontend/` + `backend/`) | ✅ Decided — **ADR-002** | One repo, changes stay together |
| D9 | Frontend stack | **React + TypeScript + Vite** | ✅ Decided — **ADR-005** | Typed, fast, static output for Vercel |
| D10 | Data fetching | **TanStack Query** | ✅ Decided (2026-11-21) | Built-in caching, retries, cancellation and loading/error state; `refetch()` powers the Retry buttons |
| D11 | Styling | **Tailwind CSS** | ✅ Decided (2026-11-21) | Fast to build, responsive utilities, styles live next to the markup |
| D12 | Package manager | npm | ✅ Decided | Comes with Node; simplest |
| D13 | Secrets feature (e.g. keyed third-party API, admin token) | **None for now** | ⏸️ Deferred (2026-11-21) | Keeps setup simple; app has zero stored secrets. Free keyed vehicle APIs were too limited. Revisit later |

---

## 7a. Architecture Decision Records (ADRs)
Short records of *why* key decisions were made. Format: Context → Options → Decision → Tradeoffs → Consequences.
**Status values:** 📝 Proposed · ✅ Accepted · ❌ Rejected · 🔁 Superseded

| ADR | Title | Status | Date |
|---|---|---|---|
| ADR-001 | No database (stateless API) | ✅ Accepted | 2026-11-21 |
| ADR-002 | Monorepo with `frontend/` and `backend/` | ✅ Accepted | 2026-11-21 |
| ADR-003 | Host the frontend on Vercel | ✅ Accepted | 2026-11-21 |
| ADR-004 | Host the backend on Red Hat OpenShift (Developer Sandbox); GHCR images; manual deploys | ✅ Accepted (with changes) | 2026-11-21 |
| ADR-005 | Frontend built with React + TypeScript + Vite | ✅ Accepted | 2026-11-21 |

### ADR-001 · No database (stateless API) ✅
- **Context:** The goal is to learn Spring Boot and practice integrations, not data modeling. A DB adds setup, cost and hosting complexity.
- **Decision:** No database. All data comes from NHTSA APIs; in-memory caching only.
- **Consequences:** Cheap and easy to host, and scales horizontally. Cache resets on restart (acceptable). No persistence features (saved searches live in the browser instead).

### ADR-002 · Monorepo ✅
- **Context:** The project has a React frontend and a Spring Boot backend.
- **Decision:** One repo, with `frontend/` and `backend/` folders and separate CI workflows using path filters.
- **Consequences:** One place to clone and review; API and UI changes (DTOs + TS types) land in one commit. CI must use path filters so one side's change doesn't rebuild the other.

### ADR-003 · Frontend on Vercel ✅
- **Context:** The frontend is a static React (Vite) build and needs simple, free hosting.
- **Decision:** Vercel, with the project root set to `frontend/`.
- **Consequences:** Automatic deploys and a preview URL per PR. The frontend and backend live on **different domains**, so the backend must configure **CORS** for the Vercel domain. The backend URL is set as a Vercel environment variable (`VITE_API_BASE_URL`).

### ADR-004 · Backend on Red Hat OpenShift (Developer Sandbox) ✅ ACCEPTED (with changes)
**Context**
- The backend is a containerized Spring Boot app (stateless, small).
- Main goal: hands-on experience deploying a Spring Boot service to **Red Hat OpenShift**, a widely used enterprise Kubernetes platform (also offered as a managed service, ROSA, on AWS). I've worked with OpenShift environments before and want to go deeper.
- Secondary goals: low or no cost, and a working demo.

**Options considered**
| Option | Cost | Notes |
|---|---|---|
| **A. OpenShift Developer Sandbox** | Free, no credit card | Real OpenShift on a shared cluster; **30-day trial, renewable**; limited resource quota |
| B. ROSA (managed OpenShift on AWS) | **Several hundred $/month minimum** ($0.25/hr cluster fee ≈ $182.50/mo + worker fees + EC2) | Production-grade managed OpenShift, but far too expensive for a personal project |
| C. Self-managed single-node OpenShift on a cloud server | ~$100+/month (needs about 8 vCPU / 16 GB RAM) + heavy setup | Not worth it |
| D. OpenShift Local (on my Mac) | Free | Great for learning; **not reachable by others** |
| E. Google Cloud Run (non-OpenShift) | Effectively free (free tier; card required) | Permanent link, scales to zero; not OpenShift |

**Decision**
Deploy the backend to the **OpenShift Developer Sandbox (A)**. Optionally use **OpenShift Local (D)** to practice offline. Keep the container portable so it can also run on Cloud Run (E) if a permanent link is needed later.

**Tradeoffs**

✅ *Pros*
- **Real enterprise platform experience:** deploying a Spring Boot service to OpenShift end to end.
- Builds on my existing OpenShift exposure (environments, mounted configuration and certificates).
- Teaches the core platform ideas: **projects/namespaces, Deployments, Services, Routes (HTTPS), ConfigMaps, Secrets, resource limits, and liveness/readiness probes** (backed by Spring Boot Actuator).
- Free, no credit card, so no surprise bills.
- Hands-on practice with CI/CD, health checks, configuration and reliability on a real cluster.

⚠️ *Cons and risks*
- **Not permanent:** the sandbox expires every **30 days**. I'd need to renew it and redeploy, and the **backend URL may change**, which breaks the Vercel frontend until its environment variable and the CORS allowed origin are updated.
- **Not a reliable always-on demo link** for visitors.
- **Shared, limited resources:** a small quota. A Spring Boot JVM needs memory limits tuned (e.g. `-XX:MaxRAMPercentage`), and startup may be slower.
- Workloads in the sandbox may be stopped or idled. I need to check the sandbox's current rules on how long workloads run.
- **More to learn at once:** Spring Boot *and* OpenShift/Kubernetes concepts.
- **CI/CD auth friction:** automated deploys need an OpenShift login token that may expire, hence manual `oc` deploys.
- Less control than a paid platform (quotas, cluster version, features).

**Consequences and mitigations**
- Keep **all deployment config as code** in the repo (`backend/openshift/`: Deployment, Service, Route, ConfigMap) so redeploying after a renewal is one command (`oc apply -f`).
- Make the **backend URL** (Vercel env var) and the **CORS allowed origins** (Spring config / env var) configurable. Never hard-code them.
- Use Actuator **liveness/readiness probes** in the Deployment.
- Build the image in GitHub Actions and push it to **GHCR** (public package, tagged by commit SHA), then deploy that exact image to OpenShift **manually** with `oc`. Rollback = redeploy the previous SHA.
- **Fallback:** if a permanent public demo becomes important, add **Cloud Run as a second deploy target** in a future ADR. The same container runs there unchanged.

**Questions answered at sign-off**
1. Permanent link vs OpenShift only → **OpenShift only for now.** A dual deploy (build once, deploy to Cloud Run + OpenShift via separate workflows) was considered and deferred.
2. Manual vs automated deploys → **Manual** with the `oc` CLI.
3. Image build → **GitHub Actions builds and pushes to GHCR** (public package, tagged by commit SHA); OpenShift pulls it. Images survive sandbox renewals, builds don't use the sandbox quota, and the image stays portable. (S2I may be tried once as a learning exercise.)

**Sign-off:** ☑ **Accepted with changes**: OpenShift only (no Cloud Run for now); manual `oc` deploys; image built in GitHub Actions and stored in GHCR · Date: 2026-11-21

### ADR-005 · Frontend: React + TypeScript + Vite ✅
- **Context:** The frontend is a small single-page UI that calls one backend. It should be typed, fast to build, and deploy as static files to Vercel.
- **Options:** (a) React + TypeScript + Vite · (b) Next.js (React framework with SSR) · (c) React + JavaScript.
- **Decision:** **React + TypeScript + Vite**, as a client-side rendered (CSR) SPA.
- **Why:** TypeScript types mirror the API contract and catch mismatches at compile time. Vite gives a fast dev server, a simple dev proxy and a static build. SSR (Next.js) isn't needed: there's one interactive page, no SEO requirement, and the data comes from a separate API.
- **Consequences:** TS types must be kept in sync with backend DTOs (same-commit rule; OpenAPI generation as a stretch). Pure static hosting, so no server-side rendering or server functions; all data is fetched from the browser, which requires CORS on the backend.

---

## 7b. Prerequisites
Everything to install, sign up for, configure and write down before (or while) building. Phase 0 is done when sections **A–C** are complete; values in **D** get filled in as the phases produce them.

### A. Tools to install (macOS, Apple Silicon)
| Tool | Purpose | Install | Verify | Done |
|---|---|---|---|---|
| **Homebrew** | Package manager for everything below | see brew.sh | `brew --version` | ☐ |
| **Git** | Version control | `brew install git` | `git --version` | ☐ |
| **GitHub CLI** *(optional)* | Repo creation, auth, PRs from the terminal | `brew install gh` | `gh --version` | ☐ |
| **JDK 21 (Eclipse Temurin)** | Build/run the backend | `brew install --cask temurin@21` | `java -version` → 21 | ☐ |
| **`JAVA_HOME`** | Lets tools find the JDK | add `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` to `~/.zshrc` | `echo $JAVA_HOME` | ☐ |
| **Node.js LTS + npm** | Build/run the frontend | `brew install node` (or a version manager such as `fnm`) | `node -v`, `npm -v` | ☐ |
| **VS Code** | **Main editor** for backend + frontend (one editor for the monorepo) | `brew install --cask visual-studio-code` | `code --version` | ☐ |
| **IntelliJ IDEA** (free edition) *(optional)* | Backup Java IDE if deeper refactoring / Gradle Kotlin DSL support is needed | `brew install --cask intellij-idea-ce` (or jetbrains.com) | opens | ☐ |
| **Docker Desktop** (or **Colima** + Docker CLI) | Build/run container images locally | `brew install --cask docker` | `docker run hello-world` | ☐ |
| **OpenShift CLI (`oc`)** | Deploy to OpenShift | `brew install openshift-cli` | `oc version --client` | ☐ |
| **Postman** *(or curl)* | Manual API testing | `brew install --cask postman` | opens | ☐ |
| **Vercel CLI** *(optional)* | Inspect/trigger deploys from the terminal | `npm i -g vercel` | `vercel --version` | ☐ |

Not needed: Gradle, Maven or Tomcat (the project includes the Gradle wrapper `./gradlew` and Spring Boot's embedded server).

**VS Code extensions**
| Extension | Publisher | Used for | Done |
|---|---|---|---|
| **Extension Pack for Java** | Microsoft | Java language support, debugger, test runner, Gradle | ☐ |
| **Spring Boot Extension Pack** | VMware (Spring) | `application.yml` autocomplete, Spring Boot Dashboard (run/debug), Spring Initializr | ☐ |
| **ESLint** | Microsoft | Frontend linting | ☐ |
| **Prettier** | Prettier | Code formatting | ☐ |
| **Tailwind CSS IntelliSense** | Tailwind Labs | Tailwind class autocomplete | ☐ |
| **YAML** | Red Hat | Validation for `application.yml`, OpenShift manifests, workflows | ☐ |
| **GitHub Actions** | GitHub | Workflow syntax + run status | ☐ |
| **Container Tools / Docker** | Microsoft | Dockerfile support, image/container management | ☐ |
| *(Optional)* **Vitest** | Vitest | Run frontend tests from the editor | ☐ |

Tip: commit a `.vscode/extensions.json` with these as recommendations so the repo suggests them automatically.


### B. Accounts
| Account | Used for | Plan | Notes | Done |
|---|---|---|---|---|
| **GitHub** | Repo, GitHub Actions (CI), GHCR (image registry) | Free | Enable **two-factor authentication** | ☐ |
| **Red Hat account + Developer Sandbox** | OpenShift hosting | Free, **30-day sandbox, renewable** | Sign up at developers.redhat.com → start the Developer Sandbox (may require verification) | ☐ |
| **Vercel** | Frontend hosting | Free (Hobby) | Sign up **with GitHub** so it can access the repo | ☐ |
| *No account needed* | NHTSA APIs, start.spring.io, GHCR (uses the GitHub account) | — | — | — |

### C. One-time setup tasks
**Git & GitHub**
- [ ] Set your Git identity: `git config --global user.name "..."` and `git config --global user.email "..."`
- [ ] Authenticate Git with GitHub: `gh auth login` **or** an SSH key added to GitHub
- [ ] Create the **public** repo `vehicle-savvy` (Phase 0)
- [ ] Repo **Settings → Code security**: confirm **secret scanning** and **push protection** are enabled
- [ ] Repo **Settings → Actions → General**: allow GitHub Actions; workflow permissions set per workflow (`packages: write` only where needed)
- [ ] *(Optional)* Branch protection on `main`: require CI to pass before merging PRs

**Red Hat OpenShift (before D2)**
- [ ] Start the Developer Sandbox; note the **expiry date** in table D and set a calendar reminder a few days before
- [ ] Open the web console → find your **project (namespace)** name and the **API server URL**
- [ ] Use **"Copy login command"** in the console to log in with `oc` (token is short-lived; never save it)
- [ ] Confirm the cluster can pull public images from GHCR (first deploy in D2)

**GHCR (after the first CI run in D1)**
- [ ] Find the package under your GitHub profile → **Packages** → `vehicle-savvy-backend`
- [ ] **Package settings → Change visibility → Public** (so OpenShift can pull without credentials)
- [ ] Link the package to the `vehicle-savvy` repo (if not linked automatically)

**Vercel (D4)**
- [ ] Import the GitHub repo → **Root Directory:** `frontend/` · **Framework preset:** Vite
- [ ] Set **`VITE_API_BASE_URL`** (Production; optionally Preview) to the OpenShift Route URL
- [ ] Note the **production domain** → add it to the backend CORS origins (ConfigMap)

**Local project config**
- [ ] `frontend/.env.local` created from `.env.example` (gitignored)
- [ ] `.gitignore` covers: `.env`, `.env.*` (except `.env.example`), `application-local.yml`, `.idea/`, `build/`, `.gradle/`, `node_modules/`, `dist/`, `.DS_Store`

### D. Values to acquire (fill in as you go)
Public, non-secret values only. This file lives in a public repo.

| Value | Example / format | Where to find it | Needed in | Value |
|---|---|---|---|---|
| GitHub username | `octocat` | GitHub profile | Image name, repo URL | `________` |
| Repo URL | `https://github.com/<user>/vehicle-savvy` | After creating the repo | README, Vercel import | `________` |
| Backend image name | `ghcr.io/<user>/vehicle-savvy-backend` | After first CI run (D1) | `deployment.yaml`, `oc set image` | `________` |
| Latest deployed image tag | `<git-sha>` | GitHub Actions run / GHCR package page | `oc set image` (D2) | `________` |
| OpenShift web console URL | `https://console-openshift-console.apps.<cluster>...` | Developer Sandbox page | Manual checks | `________` |
| OpenShift API server URL | `https://api.<cluster>...:6443` | "Copy login command" | `oc login --server=` | `________` |
| OpenShift project (namespace) | `<username>-dev` | Web console, top-left project selector | `oc project`, manifests | `________` |
| Sandbox expiry / renewal date | `YYYY-MM-DD` | Developer Sandbox page | Renewal checklist (D2) | `________` |
| **Backend Route URL** | `https://vehicle-savvy-backend-<project>.apps.<cluster>...` | `oc get route` after D2 | `VITE_API_BASE_URL` (Vercel) | `________` |
| Vercel project URL | `https://vercel.com/<user>/vehicle-savvy` | Vercel dashboard | Managing deploys | `________` |
| **Frontend production domain** | `https://vehicle-savvy-<x>.vercel.app` | Vercel → Project → Domains | **CORS origins** (ConfigMap) | `________` |
| Vercel preview URL pattern | `https://vehicle-savvy-git-<branch>-<user>.vercel.app` | Any PR's preview deploy | CORS decision for previews (D4) | `________` |
| `VITE_API_BASE_URL` (prod) | = Backend Route URL | — | Vercel env var | `________` |
| `APP_CORS_ALLOWED_ORIGINS` (prod) | = Frontend production domain | — | ConfigMap | `________` |

**⚠️ Never write these here (or anywhere in the repo):** OpenShift login tokens, GitHub personal access tokens, passwords, or any API key. They stay in your terminal session, password manager, or the platform's secret storage.

### E. When the sandbox renews (every 30 days)
Values that will likely change and must be updated: **OpenShift API server URL, project name, Route URL, sandbox expiry date** → then update **`VITE_API_BASE_URL`** in Vercel (redeploy) and re-check the **CORS origins**. See the renewal checklist in D2.

---

## 8. Phases & checklist
Each phase ends with something working. Check items off as I go.
**Order:** Setup → Backend (B1–B9) → Frontend (F1–F7) → Delivery (D1–D5) → Polish. Frontend work can start once **B4** (summary endpoint) works locally.

### Phase 0: Setup
- [ ] Complete **Prerequisites A–C** (section 7b): tools installed and verified, GitHub / Red Hat / Vercel accounts created, Git configured
  - Red Hat sandbox and Vercel setup can wait until D2 and D4 (the 30-day sandbox clock starts when you activate it)
- [ ] Add `.vscode/extensions.json` (recommended extensions) to the repo
- [ ] Create the **public** GitHub repo **`vehicle-savvy`** with `backend/` and `frontend/` folders, a root `README.md`, `.gitignore` (Java + Node), and this `PLAN.md`
- **Done when:** every tool in 7b-A verifies, `java -version` shows 21, `node -v` shows an LTS version, and the public repo is pushed.

---

### Backend track

#### B1: Scaffold & run
- [ ] Generate the project at start.spring.io (or VS Code: *Spring Initializr: Create a Gradle Project*) **into `backend/`**: Gradle-Kotlin · Java 21 · deps: **Spring Web, Validation, Actuator, DevTools, Cache**
- [ ] `./gradlew bootRun` (or the Spring Boot Dashboard in VS Code) → app starts on `localhost:8080`
- [ ] `GET /actuator/health` returns `{"status":"UP"}`
- **Learn:** project layout, `@SpringBootApplication`, auto-configuration, starters
- **Done when:** health check is UP locally.

#### B2: VIN decode endpoint
- [ ] DTOs as `record`s: `VehicleResponse`, plus an internal `VpicResponse` mapping the NHTSA JSON
- [ ] `NhtsaClient` (`@Component`) using `RestClient` to call DecodeVinValues
- [ ] `VehicleService` (`@Service`) maps the NHTSA response to my clean DTO
- [ ] `VehicleController` (`@RestController`): `GET /api/vehicles/{vin}`
- [ ] Test in Postman with the test VIN
- **Learn:** DI via constructor, `@RestController`/`@GetMapping`/`@PathVariable`, `RestClient`, Jackson mapping, `@JsonProperty`
- **Done when:** a real VIN returns clean JSON.

#### B3: Recalls endpoint
- [ ] Add a recalls call to `NhtsaClient`
- [ ] `RecallResponse` DTO
- [ ] `GET /api/vehicles/recalls?make=&model=&year=` (`@RequestParam`)
- **Done when:** recalls come back for the test vehicle.

#### B4: Combined summary
- [ ] `GET /api/vehicles/{vin}/summary` → decode, then recalls (chained), then combine
- [ ] `VehicleSummaryResponse` DTO matching the **API contract** (section 4)
- **Learn:** orchestrating dependent calls in the service layer
- **Done when:** one request returns vehicle + recalls. ✅ *Frontend can start.*

#### B5: Validation & error handling
- [ ] Validate VIN: `@Pattern(regexp = "^[A-HJ-NPR-Z0-9]{17}$")` → 400 on bad input
- [ ] Handle vPIC "200 with an error inside" → a meaningful 404/422
- [ ] Custom exceptions (`VehicleNotFoundException`, `UpstreamServiceException`)
- [ ] `@RestControllerAdvice` → the error JSON from the contract (`timestamp`, `status`, `error`, `code`, `message`, `path`)
- **Learn:** Bean Validation, exception handling, choosing the right HTTP status codes (400 vs 404 vs 502 vs 503)
- **Done when:** bad VIN → 400, unknown VIN → 404/422, NHTSA down → 502/503, all as clean JSON with a `code`.

#### B6: Resiliency
- [ ] Connect and read **timeouts** on `RestClient` (e.g. 2s / 5s)
- [ ] **Partial result:** if recalls fail, return vehicle info with `"recalls": {"status": "UNAVAILABLE"}`
- [ ] (Optional) **Retry** once on transient failures. Try Resilience4j `@Retry` / `@CircuitBreaker`
- [ ] Log external call failures with useful context (VIN, endpoint, duration)
- **Learn:** timeouts, retries, circuit breakers, graceful degradation
- **Done when:** I can simulate an outage (bad URL in config) and the API degrades gracefully.

#### B7: Caching (no DB)
- [ ] `@EnableCaching` + `@Cacheable("vinDecode")` on the decode call
- [ ] `@Cacheable("recalls")` on recalls
- [ ] Log cache hits and misses to see it work; (optional) add Caffeine for TTL and size limits
- **Learn:** cache-aside, TTL, what's safe to cache
- **Done when:** a second request for the same VIN doesn't call NHTSA.

#### B8: Backend tests
- [ ] **Unit:** `VehicleService` mapping logic (JUnit 5, Mockito)
- [ ] **Controller:** `@WebMvcTest` + `MockMvc` (200, 400, error JSON shape)
- [ ] **Client:** `MockRestServiceServer` (or WireMock) to fake NHTSA: success, error-in-200, timeout/500
- [ ] Tests never call the real NHTSA
- **Learn:** test layers, mocking external dependencies, avoiding flaky tests
- **Done when:** `./gradlew test` passes offline.

#### B9: CORS, config & container
- [ ] **CORS:** `WebMvcConfigurer` allowing `GET` from origins in config (`app.cors.allowed-origins`), e.g. `http://localhost:5173` locally and the Vercel domain in production
- [ ] All settings from **6c** in `application.yml` with `${ENV_VAR:default}` overrides, bound to a validated `@ConfigurationProperties` record (`AppProperties`)
- [ ] Optional `application-local.yml` (gitignored) for local overrides
- [ ] Multi-stage `Dockerfile`: `eclipse-temurin:21-jdk` build → `eclipse-temurin:21-jre` run
- [ ] `docker build` + `docker run -p 8080:8080`, then hit health and an endpoint
- **Learn:** CORS (preflight, allowed origins), fat JAR, JDK vs JRE images, config by environment
- **Done when:** the app runs in a container locally and accepts requests from the Vite dev origin.

---

### Frontend track

#### F1: Scaffold & tooling
- [ ] `npm create vite@latest frontend -- --template react-ts`
- [ ] `tsconfig`: keep `strict: true`
- [ ] ESLint + Prettier configured; `npm run lint` and `npm run format` scripts
- [ ] Tailwind CSS set up (Vite plugin) and a quick test class renders
- [ ] `npm run dev` → app on `localhost:5173`
- **Learn:** Vite project layout, TS config, the `main.tsx` → `App.tsx` entry flow
- **Done when:** the starter page runs and `npm run lint` and `npm run build` pass.

#### F2: API client, types & dev proxy
- [ ] `src/types/vehicle.ts`: the TS types from section 4
- [ ] `src/api/client.ts`: a small `fetch` wrapper that:
  - prefixes `import.meta.env.VITE_API_BASE_URL` (empty locally → relative `/api` goes through the proxy)
  - parses JSON, and throws a typed `ApiRequestError` (with `status` and `code`) on non-2xx
  - sets a request timeout with `AbortController`
- [ ] `src/api/vehicles.ts`: `getVehicleSummary(vin): Promise<VehicleSummary>`
- [ ] `vite.config.ts` **dev proxy**: `/api` → `http://localhost:8080`
- [ ] `.env.example` documenting `VITE_API_BASE_URL` and `VITE_REQUEST_TIMEOUT_MS` (see **6c**)
- [ ] `src/config.ts` as the only place that reads `import.meta.env`
- [ ] (Optional) Zod schemas to validate responses at runtime
- **Learn:** typing API responses, env vars in Vite (`VITE_` prefix, baked in at build time), proxying vs CORS
- **Done when:** calling `getVehicleSummary` from the app returns typed data from the local backend.

#### F3: VIN search form
- [ ] `src/lib/vin.ts`: `normalizeVin()` (trim, uppercase) and `isValidVin()` using the **same regex** as the backend
- [ ] `VinSearchForm.tsx`: controlled input, inline validation message, submit disabled while invalid or loading, character counter (`12/17`)
- [ ] Accessible: `<label>`, `aria-invalid`, `aria-describedby` for the error, Enter submits
- [ ] (Nice to have) sync the VIN to the URL (`?vin=...`) so results are shareable and survive refresh
- **Learn:** controlled components, client-side vs server-side validation (both are needed)
- **Done when:** invalid VINs are caught before any request; valid ones trigger a search.

#### F4: Data fetching & state
- [ ] Install `@tanstack/react-query`; wrap the app in `QueryClientProvider` (`main.tsx`); add React Query Devtools in dev
- [ ] `useVehicleSummary(vin)` hook:
  ```ts
  useQuery({
    queryKey: ["summary", vin],
    queryFn: ({ signal }) => getVehicleSummary(vin, signal),
    enabled: isValidVin(vin),
    retry: 1,                      // don't retry 4xx (e.g. INVALID_VIN, VEHICLE_NOT_FOUND)
    staleTime: 5 * 60 * 1000,
  })
  ```
- [ ] Custom `retry` function: retry only network errors and 5xx, never 4xx
- [ ] Wire the **Retry** buttons (error + partial states) to `refetch()`
- [ ] Map errors by `code` (section 4 table) to user-friendly messages
- **Learn:** query keys, stale time vs cache time, client-side caching, request cancellation, race conditions
- **Done when:** the hook exposes `data`, `isLoading` and a typed `error`, and repeated searches for the same VIN don't refetch unnecessarily.

#### F5: Results UI
- [ ] `VehicleSummaryCard.tsx`: year, make, model, body class, fuel type (handle `null`s gracefully)
- [ ] `RecallList.tsx`: count badge + list of recalls (campaign number, component, summary, remedy, date); expandable summaries
- [ ] **States**, each with its own clear UI:
  | State | UI |
  |---|---|
  | Idle | Short intro + example VIN button |
  | Loading | Skeleton card |
  | Success, recalls found | Summary card + recall list |
  | Success, no recalls | Summary card + "No open recalls found" ✅ |
  | **Partial** (`recalls.status = UNAVAILABLE`) | Summary card + warning: "Recall data is temporarily unavailable" + retry |
  | Not found | "We couldn't find a vehicle for that VIN" |
  | Service down / network error | Error banner + retry button |
- [ ] Responsive layout (mobile first)
- **Learn:** designing for every API state, composing components
- **Done when:** I can trigger every state above (using the backend's outage simulation from B6).

#### F6: UX polish & accessibility
- [ ] Recent searches (last 5 VINs) in `localStorage`, wrapped in try/catch
- [ ] Focus management: move focus to results or the error after a search
- [ ] Keyboard-only and screen-reader check; color contrast check
- [ ] Page title and favicon; basic SEO meta
- **Done when:** the app is pleasant on mobile and usable with only a keyboard.

#### F7: Frontend tests
- [ ] **Vitest + React Testing Library** set up (`npm test`)
- [ ] **Unit:** `isValidVin`, `normalizeVin`, error-code → message mapping
- [ ] **Component:** `VinSearchForm` (validation, submit), `RecallList` (empty vs items)
- [ ] **Integration with MSW:** fake `GET /api/vehicles/:vin/summary` for success, partial, 404, 503; assert the right UI shows
- [ ] Tests never call the real backend
- **Learn:** testing from the user's point of view, mocking the network with MSW
- **Done when:** `npm test` passes offline and covers every state in F5.

---

### Delivery track

#### D1: Backend CI (GitHub Actions → GHCR)
- [ ] `.github/workflows/backend-ci.yml`, triggered on push to `main` and PRs, with path filter **`backend/**`**
- [ ] Steps: checkout → `actions/setup-java` (Temurin 21, Gradle cache) → `./gradlew test` → `./gradlew bootJar` (working directory `backend/`)
- [ ] Build the image and push it to **GHCR** using the built-in `GITHUB_TOKEN` (`permissions: packages: write`)
  - Tags: `ghcr.io/<github-user>/vehicle-savvy-backend:<git-sha>` (+ optional `:main`)
- [ ] Make the GHCR package **public** (OpenShift can pull without a pull secret)
- [ ] PRs: run tests only, don't push images
- [ ] (Optional) Trivy image scan
- **Learn:** pinning the JDK in CI, dependency caching, registries, image tags vs `:latest`, workflow permissions
- **Done when:** a push to `main` that touches `backend/` runs tests and publishes `ghcr.io/...:<sha>`.

#### D2: Deploy backend to OpenShift (manual, `oc` CLI)
- [ ] Sign up for the **Red Hat Developer Sandbox**; note the renewal date: `________`
- [ ] Install the `oc` CLI (`brew install openshift-cli`); log in with the token from the web console ("Copy login command")
- [ ] Deployment config as code in `backend/openshift/`:
  - [ ] `deployment.yaml`: image from GHCR, resource requests/limits, env vars, **liveness/readiness probes** → `/actuator/health/liveness` and `/actuator/health/readiness`
  - [ ] `service.yaml`: exposes port 8080 inside the cluster
  - [ ] `route.yaml`: public **HTTPS** URL (edge TLS)
  - [ ] `configmap.yaml`: production overrides from **6c** (CORS allowed origins = Vercel production domain, timeouts, cache), loaded with `envFrom: configMapRef`
- [ ] JVM memory tuned for the sandbox quota (e.g. `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`)
- [ ] First deploy: `oc apply -f backend/openshift/`
- [ ] Each later deploy (manual):
  ```bash
  oc set image deployment/vehicle-savvy-backend app=ghcr.io/<github-user>/vehicle-savvy-backend:<git-sha>
  oc rollout status deployment/vehicle-savvy-backend
  curl https://<route-url>/actuator/health
  ```
- [ ] Rollback practice: `oc rollout undo deployment/vehicle-savvy-backend` (or set the previous SHA)
- [ ] Note the Route URL here: `________`
- [ ] **Renewal checklist (every 30 days):** renew sandbox → `oc login` → `oc apply -f backend/openshift/` → `oc set image ...:<latest-sha>` → update `VITE_API_BASE_URL` in Vercel (and redeploy the frontend) + the CORS origin if the Route changed
- [ ] (Optional learning) try one **S2I** build in the sandbox
- **Learn:** projects, Deployments, Services, Routes, ConfigMaps, probes, rollouts and rollbacks
- **Done when:** the Route URL returns a vehicle summary and `/actuator/health` is `UP`.

#### D3: Frontend CI (GitHub Actions)
- [ ] `.github/workflows/frontend-ci.yml`, triggered on push to `main` and PRs, with path filter **`frontend/**`**
- [ ] Steps: checkout → `actions/setup-node` (LTS, npm cache) → `npm ci` → `npm run lint` → `npx tsc --noEmit` (type check) → `npm test` → `npm run build` (working directory `frontend/`)
- **Learn:** `npm ci` vs `npm install`, type checking as a CI gate
- **Done when:** a PR touching `frontend/` shows lint, type-check, test and build results.

#### D4: Deploy frontend to Vercel
- [ ] Import the GitHub repo into Vercel; set **Root Directory = `frontend/`**, framework preset **Vite**
- [ ] Environment variable **`VITE_API_BASE_URL`** = the OpenShift Route URL (Production)
- [ ] Production deploys from `main`; preview deploys for each PR
- [ ] Add the Vercel **production domain** to the backend's CORS allowed origins (D2 ConfigMap)
- [ ] Decide how to handle **preview URLs** with CORS: allow only production (previews show the API error state), or allow a specific preview pattern
- [ ] (Optional) SPA rewrite in `vercel.json` if I add client-side routes
- **Learn:** build-time env vars, preview deployments, cross-origin setup in production
- **Done when:** the Vercel URL loads and successfully calls the OpenShift backend.

#### D5: End-to-end check
- [ ] From the live Vercel URL: valid VIN → summary + recalls
- [ ] Invalid VIN → inline error (no request sent)
- [ ] Temporarily break the recalls URL in the ConfigMap → partial-result warning shows; restore it
- [ ] Browser dev tools: no CORS errors; check response times and cache hits in the backend logs
- **Done when:** every state works in production, not just locally.

---

### Polish & documentation
- [ ] Root `README.md`: what it is, architecture diagram, screenshots/GIF, live links, how to run each side locally, how to deploy, design decisions (link the ADRs)
- [ ] `backend/README.md` and `frontend/README.md`: short, side-specific run/test instructions
- [ ] Clean up the lessons-learned section below
- **Done when:** someone new can understand, run and deploy it from the READMEs.

---

## 9. Planned project structure
```
vehicle-savvy/
├── PLAN.md
├── README.md
├── .gitignore
├── .github/workflows/
│   ├── backend-ci.yml                 (path filter: backend/**)
│   └── frontend-ci.yml                (path filter: frontend/**)
├── backend/
│   ├── build.gradle.kts
│   ├── Dockerfile
│   ├── openshift/                     (deployment, service, route, configmap YAML)
│   └── src/
│       ├── main/java/com/jeremiah/vehiclesavvy/
│       │   ├── VehicleSavvyApplication.java
│       │   ├── controller/VehicleController.java
│       │   ├── service/VehicleService.java
│       │   ├── client/NhtsaClient.java
│       │   ├── dto/          (VehicleResponse, RecallResponse, VehicleSummaryResponse, Vpic*/Nhtsa* mapping records)
│       │   ├── exception/    (custom exceptions + GlobalExceptionHandler)
│       │   └── config/       (RestClient bean, timeouts, cache, CORS config)
│       ├── main/resources/application.yml
│       └── test/java/com/jeremiah/vehiclesavvy/  (service, controller, client tests)
└── frontend/
    ├── package.json
    ├── vite.config.ts                 (dev proxy /api → :8080)
    ├── tsconfig.json
    ├── .env.example                   (VITE_API_BASE_URL)
    ├── index.html
    └── src/
        ├── main.tsx
        ├── App.tsx
        ├── api/          (client.ts, vehicles.ts)
        ├── types/        (vehicle.ts)
        ├── hooks/        (useVehicleSummary.ts)
        ├── lib/          (vin.ts, errorMessages.ts, recentSearches.ts)
        ├── components/   (VinSearchForm, VehicleSummaryCard, RecallList, StatusMessage, Skeleton)
        ├── test/         (setup.ts, msw handlers + server)
        └── styles/
```

---

## 10. UI sketch (target)
```
┌─────────────────────────────────────────────────────┐
│  🚗 Vehicle Savvy                                     │
│  Look up a vehicle and its open safety recalls       │
│                                                      │
│  VIN [ 1HGCM82633A004352        ] 17/17  [ Search ]  │
│  Recent: 1HGCM82633A004352 · ...                     │
├─────────────────────────────────────────────────────┤
│  2003 HONDA Accord                                   │
│  Coupe · Gasoline · VIN 1HGCM82633A004352            │
├─────────────────────────────────────────────────────┤
│  Open recalls (1)                                    │
│  ▸ 03V123000 · AIR BAGS                              │
│    Summary... (expand)   Remedy...                   │
│                                                      │
│  ⚠ Recall data is temporarily unavailable [Retry]    │  ← partial state
└─────────────────────────────────────────────────────┘
```

---

## 11. Lessons learned (fill in as I build)
**Spring Boot vs Ktor:**
- 

**TypeScript/React:**
- 

**Surprises and gotchas** (e.g. vPIC returning 200 with an error inside, CORS in production):
- 

**What I'd do differently:**
- 

---

## 12. Learning notes (aha moments, references)
- 

---

## 13. Session log
| Date | Phase | What I did | Next step |
|---|---|---|---|
| 2026-11-21 | Planning | Created the plan; added ADRs; accepted ADR-004 (OpenShift + GHCR + manual deploys) | Phase 0: install Temurin 21 + IntelliJ |
| 2026-11-21 | Planning | Expanded to full stack: React + TypeScript frontend (ADR-005), API contract, frontend + delivery tracks; renamed project to `vehicle-info` | D10 = TanStack Query ✅, D11 = Tailwind ✅, D6 = public repo ✅. All decisions made. Next: Phase 0 |
| 2026-11-21 | Planning | Added prerequisites (7b) and configuration (6c); D6 = public repo; D13 = secrets deferred; **renamed app to Vehicle Savvy (`vehicle-savvy`)**; VS Code as main editor (IntelliJ optional) | Phase 0: Prerequisites A–C |

---

## 14. Stretch ideas (only after D5)
- **OpenAPI** docs (springdoc) + **generate the TS types** from the spec (`openapi-typescript`) so they never drift
- NHTSA **Safety Ratings** API → star ratings in the summary card
- **Zippopotam.us** → ZIP code lookup endpoint
- Resilience4j circuit breaker + Actuator metrics
- Playwright end-to-end test against the deployed app
- Dark mode
- Rewrite one backend endpoint in Ktor to compare side by side
