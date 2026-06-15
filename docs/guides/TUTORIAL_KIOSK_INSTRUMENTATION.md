# Tutorial: Instrumenting a Kiosk App with Dash0 Mobile Observability

A walkthrough for an Android engineer working on a self-order kiosk app. We start
with an uninstrumented Compose app and add Dash0 telemetry in five short parts.
Total time: under one hour.

Each part follows the same shape:

1. **What you're adding** — one sentence
2. **Why a kiosk PM cares** — the business question this answers
3. **The code** — copy-paste-able
4. **What you see in Dash0** — exact query, expected result
5. **Adoption friction** — coexistence, footprint, opt-out

## Prerequisites

- Android Studio, JDK 17, an emulator or device running API 26+
- A buildable kiosk app — the rest of this tutorial assumes the
  uninstrumented baseline at `~/Projects/Dash0/kiosk-demo/` (see
  `README.md` in that repo)
- A Dash0 account with an OTLP/gRPC ingest endpoint + auth token

## What you'll have when you're done

Telemetry from a real kiosk session in Dash0, including:

- Every tap and screen view (auto-instrumented)
- A journey span per customer interaction
- Custom funnel events for cart, upsell, checkout
- Selective context flush on payment errors
- Screenshot + wireframe replay, with PII redacted

---

## Part 1 — One line of code

**What you're adding:** the Dash0 SDK initialization.

**Why a kiosk PM cares:** "Is the app working in our stores? Are customers
actually interacting with it?" Before instrumentation, the only way to know
this is to ask store managers. After Part 1, every device in the fleet
reports in.

### The code

Add the SDK dependency to `app/build.gradle.kts`:

```kotlin
dependencies {
    // ... existing dependencies ...

    // Dash0 Mobile Observability — added by tutorial Part 1
    implementation("io.opentelemetry.android:mobile:0.4.2-alpha")
}
```

Then add init to `KioskApp.onCreate`:

```kotlin
package com.dash0.kiosk

import android.app.Application
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.android.mobile.config.ExportMode
import io.opentelemetry.android.mobile.config.MobileConfig

class KioskApp : Application() {
    override fun onCreate() {
        super.onCreate()

        OTelMobile.start(
            application = this,
            config = MobileConfig(
                serviceName = "kiosk-demo",
                serviceVersion = BuildConfig.VERSION_NAME,
                collectorEndpoint = "https://YOUR-DASH0-ENDPOINT:4317",
                exportMode = ExportMode.HYBRID,
                headers = mapOf(
                    "Authorization" to "Bearer YOUR_AUTH_TOKEN",
                    "Dash0-Dataset" to "kiosk-demo",
                ),
            ),
        )
    }
}
```

Rebuild and launch. Tap through one order — Attract → Menu → tap an item →
Cart → Checkout → done.

### What you see in Dash0

In the Dash0 UI: **Logs → filter `service.name is kiosk-demo`**.

You should see these event types appearing on their own, no extra code required:

| Event | When it fires |
|---|---|
| `app.start` | App cold-launch |
| `app.foreground` / `app.background` | Process lifecycle |
| `ui.tap` | Every button tap |
| `ui.screen_view` | Each screen transition |
| `device.heartbeat` | Every 30 seconds in HYBRID mode |

Drill into one `ui.tap` event. Notice it carries `target.id`,
`target.text` (PII-scrubbed), and is parented under a `page.MenuScreen`
span — so you can reconstruct the order of interactions for free.

### Adoption friction

- **APK footprint:** ~150 KB added by the SDK
- **Coexistence:** doesn't conflict with existing analytics. The SDK
  exports OTLP — if you already have Firebase or your own pipeline,
  both run side by side, neither blocks the other
- **Main-thread impact:** zero. All buffering and export happens on a
  dedicated background executor
- **Opt-out:** delete the `OTelMobile.start(...)` call. The dependency
  can stay; it does nothing without the start call

### A note on sampling

The SDK's default sampling rate is **5–10%** of traces. That default is
sized for **high-volume mobile fleets** — a consumer app with millions
of devices each producing thousands of events per day, where battery
and bandwidth matter and you only need a representative sample.

**A kiosk is the opposite environment:**

- One device per store, not millions in customer pockets
- Wall power and ethernet (battery and bandwidth are irrelevant)
- An operator wants to see **every** order, not 1 in 20

So in the kiosk reference app we override the default:

```kotlin
samplingConfig = SamplingConfig.alwaysOn(),
```

This captures 100% of traces. The trade-off is volume — every tap and
order produces telemetry — but at kiosk scale that volume is trivial
(a few hundred events per device per day, vs. millions for a phone fleet).

If you need to dial it back later (e.g., a high-traffic drive-thru kiosk
producing too much data), the same field accepts:

| Preset                              | Use case                                                                                       |
| ----------------------------------- | ---------------------------------------------------------------------------------------------- |
| `SamplingConfig.alwaysOn()`         | Demos, single-store rollouts, anything where you want every event                              |
| `SamplingConfig.production(0.5)`    | Multi-store rollout, 50% sample                                                                |
| `SamplingConfig.dynamic(0.1, 1.0)`  | High volume; sample 10% of normal traces, 100% of high-priority traces (errors, payments)      |
| `SamplingConfig.alwaysOff()`        | Quickly disable trace export without ripping out the SDK                                       |

Full sampling reference: [docs/SAMPLING.md](../SAMPLING.md).

---

## Part 2 — Identify the journey

**What you're adding:** an explicit journey span around each customer
interaction. Starts when leaving Attract; ends on confirmation or
timeout.

**Why a kiosk PM cares:** "How long does an average order take? Where
do customers stall — at the menu, the customization sheet, or
checkout?"

### The code

Two changes. First, in `KioskViewModel.kt`, add hooks for journey
start/end:

```kotlin
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.api.trace.Span

class KioskViewModel : ViewModel() {
    // ... existing fields ...

    private var journeySpan: Span? = null

    fun startSession() {
        _sessionId.value = newSessionId()
        _state.value = State.Active(System.currentTimeMillis())

        // Start the journey span. Any spans created while this is the
        // current span become children — page spans, tap spans, etc.
        journeySpan = OTelMobile.startJourney("order")
    }

    private fun returnToAttract() {
        // End the journey before clearing state. If we got here from
        // OrderConfirmedScreen, the journey was a successful order; if
        // we got here from the idle timer, it was abandoned.
        journeySpan?.setAttribute("order.outcome", outcomeForCurrentState())
        journeySpan?.end()
        journeySpan = null

        _state.value = State.Attract
        onIdleTimeout?.invoke()
    }

    private fun outcomeForCurrentState(): String =
        if (_state.value is State.Active) "abandoned" else "confirmed"
}
```

### What you see in Dash0

**Traces → filter `service.name is kiosk-demo`**.

Each customer session shows as a single trace tree with `journey.order`
as the root. Inside: `page.MenuScreen → ui.tap → page.CartScreen → ...`
in the order the customer did them. Tap the root span — `duration` is
the total time customer spent.

Built-in saved query that's useful right away:

```
service.name is kiosk-demo
AND span.name is journey.order
```

Aggregate by `order.outcome` to see your abandonment rate.

### Adoption friction

- **One method to install, one to call:** the SDK exposes
  `OTelMobile.startJourney(name)` returning a `Span` — same shape as
  any OpenTelemetry API consumer is used to
- **Re-entrancy:** if a journey is already active, calling
  `startJourney` again will return the existing one; no duplicates
- **Performance:** spans are buffered locally; nothing leaves the
  device until a flush trigger fires (in HYBRID mode, every 30s or on
  policy match)

---

## Part 3 — Custom events that matter to the business

**What you're adding:** named log records at every business-meaningful
point — cart adds, upsell decisions, checkout-started, order-placed.

**Why a kiosk PM cares:** "Which menu items get added but removed
before checkout? Which upsells convert? Is there a step in the funnel
where customers consistently stall?"

### The code

Create a `KioskTelemetry.kt` helper to keep emit calls in one place:

```kotlin
package com.dash0.kiosk

import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity

object KioskTelemetry {
    private val logger by lazy {
        OTelMobile.getLoggerProvider().get("com.dash0.kiosk.events")
    }

    fun itemAddedToCart(itemId: String, qty: Int, priceCents: Int, modCount: Int) {
        emit(
            name = "item.added_to_cart",
            attrs = mapOf(
                "item.id" to itemId,
                "item.qty" to qty.toString(),
                "item.price_cents" to priceCents.toString(),
                "item.mod_count" to modCount.toString(),
            ),
        )
    }

    fun upsellShown(itemId: String) =
        emit("upsell.shown", mapOf("item.id" to itemId))

    fun upsellAccepted(itemId: String) =
        emit("upsell.accepted", mapOf("item.id" to itemId))

    fun upsellDeclined(itemId: String) =
        emit("upsell.declined", mapOf("item.id" to itemId))

    fun cartViewed(itemCount: Int, subtotalCents: Int) =
        emit(
            "cart.viewed",
            mapOf(
                "cart.item_count" to itemCount.toString(),
                "cart.subtotal_cents" to subtotalCents.toString(),
            ),
        )

    fun checkoutStarted(totalCents: Int) =
        emit("checkout.started", mapOf("order.total_cents" to totalCents.toString()))

    fun orderPlaced(orderNumber: Int, totalCents: Int, lineCount: Int) =
        emit(
            "order.placed",
            mapOf(
                "order.number" to orderNumber.toString(),
                "order.total_cents" to totalCents.toString(),
                "order.line_count" to lineCount.toString(),
            ),
        )

    private fun emit(name: String, attrs: Map<String, String>) {
        val builder = Attributes.builder()
            .put(AttributeKey.stringKey("event.name"), name)
        attrs.forEach { (k, v) ->
            builder.put(AttributeKey.stringKey(k), v)
        }
        logger.logRecordBuilder()
            .setBody(name)
            .setSeverity(Severity.INFO)
            .setAllAttributes(builder.build())
            .emit()
    }
}
```

Then call `KioskTelemetry.*` from the right places — in
`OrderViewModel.addLine()` after appending a line, in `CartScreen` on
entry, in `UpsellPromptDialog`'s onAccept/onDecline, and in
`CheckoutScreen` on each step transition.

### What you see in Dash0

Build a funnel chart with these stages:

1. `event.name is page.MenuScreen`
2. `event.name is item.added_to_cart`
3. `event.name is cart.viewed`
4. `event.name is checkout.started`
5. `event.name is order.placed`

The conversion rate between stages 3 → 4 is your cart abandonment rate.
The split of `upsell.accepted` vs `upsell.declined` is your upsell
conversion.

### Adoption friction

- **Where these go:** these events are logs, not spans, and live in
  the same dataset as the auto-instrumented ones. No new pipeline to
  manage
- **Naming:** event names follow `domain.action` (OTel semconv style).
  Use whatever names your existing analytics uses if you want
  cross-system reconciliation
- **Rate-limited internally:** the SDK rate-limits log emit to prevent
  loops. You can't accidentally DoS your backend from the device

---

## Part 4 — Selective flush on payment errors

**What you're adding:** a payment-error simulation, and a policy that
flushes the buffer when one happens.

**Why a kiosk PM cares:** "When a checkout fails, what was the
customer about to buy? Can I see their full session so I know whether
this was a fluke or a pattern?"

### The code

Wire OkHttp through the SDK's interceptor in `KioskApp.onCreate`:

```kotlin
import io.opentelemetry.android.mobile.network.OTelNetworkInterceptor
import okhttp3.OkHttpClient

class KioskApp : Application() {
    lateinit var httpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        OTelMobile.start(/* ... as Part 1 ... */)

        httpClient = OkHttpClient.Builder()
            .addInterceptor(OTelNetworkInterceptor())
            .build()
    }
}
```

In `CheckoutScreen.kt`, replace the fake `delay(2_000L)` in
`ProcessingStep` with a real HTTP call that occasionally fails:

```kotlin
@Composable
private fun ProcessingStep(onComplete: () -> Unit, onFailure: () -> Unit) {
    val app = LocalContext.current.applicationContext as KioskApp

    LaunchedEffect(Unit) {
        // Simulate a payment endpoint. httpbin.org/status/500 returns 500,
        // which the auto-instrumented interceptor records as http.error and
        // triggers the default flush policy.
        val req = Request.Builder()
            .url("https://httpbin.org/status/500")
            .post(RequestBody.create(null, "demo"))
            .build()
        withContext(Dispatchers.IO) {
            try {
                app.httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) onComplete() else onFailure()
                }
            } catch (e: IOException) {
                onFailure()
            }
        }
    }

    // ... existing UI ...
}
```

### What you see in Dash0

Filter to `event.name is http.error AND service.name is kiosk-demo`.

Each error record carries `http.response.status_code = 500` and the
URL. Click into one — the trace tree shows everything the customer did
in the last 2 minutes leading up to the error, because the
`http-error-detector` policy fires `flushWindow(2)` automatically.

For PMs: build a saved query "Failed checkouts" and pin it to the
dashboard. Any spike is visible immediately.

### Adoption friction

- **Interceptor placement:** has to be the first application
  interceptor in your `OkHttpClient.Builder`. If your existing chain
  already has interceptors, add Dash0's first so it sees the URL the
  app dispatched, not whatever a downstream interceptor rewrites
- **Customize policies:** the default policies live in
  `PolicyEvaluator`. You can override or extend them; see
  `docs/CONFIGURATION_GUIDE.md`
- **Failure modes:** if the SDK can't reach the Dash0 endpoint,
  events buffer locally (50MB SQLite, 24h TTL) and replay on next
  successful connection

---

## Part 5 — Screenshot & wireframe — and how we protect the customer

**What you're adding:** visual capture of every customer session, so a
support engineer can see *literally* what a customer saw when something
went wrong. And the redaction config that ensures we don't capture PII.

**Why a kiosk PM cares:** "Show me literally what this customer saw
when they walked away. But — without showing me their card number or
phone."

### Step 1: Show what's possible (unredacted)

Add to your `MobileConfig`:

```kotlin
OTelMobile.start(
    application = this,
    config = MobileConfig(
        // ... existing fields ...
        screenshotConfig = ScreenshotConfig(
            enabled = true,
            captureOnScreenView = true,
            captureOnError = true,
            captureOnPolicyMatch = true,
            redactTextViews = false,  // ⚠️ no redaction yet — we'll fix this
        ),
        wireframeConfig = WireframeConfig(
            enabled = true,
            captureOnScreenView = true,
            captureOnError = true,
            captureOnPolicyMatch = true,
            dedupeByContentHash = true,
        ),
    ),
)
```

Run an order. Filter to `event.name is ui.screenshot` in Dash0. Each
record has a `mobile.screenshot.data_url` attribute — paste it into a
browser address bar to see the captured screen.

You'll see the phone-rewards entry screen and the card-entry screen.
The card number is visible. Phone digits are visible.

### Step 2: Acknowledge the elephant

A kiosk PM in the demo will react to this. The next step is the
turn-around.

### Step 3: Enable text redaction

```kotlin
screenshotConfig = ScreenshotConfig(
    // ... existing fields ...
    redactTextViews = true,  // ✅ all text now blacked out in screenshots
),
wireframeConfig = WireframeConfig(
    // ... existing fields ...
    redactText = true,       // ✅ wireframe JSON omits text node contents
),
```

Re-run. Same screens, now with black bars over all text. The card
number is unreadable. So is the customer's phone.

### Step 4: Per-field opt-out for known-sensitive inputs

Some fields you want completely off the wire — not just visually
redacted but genuinely never captured. Tag them:

```kotlin
// In CheckoutScreen.kt, on the phone-rewards input:
OutlinedTextField(
    value = phone,
    onValueChange = { /* ... */ },
    modifier = Modifier.semantics {
        // SDK-specific semantic key — the wireframe walker honors this.
        this[SemanticsProperties.PII] = true
    },
    // ... rest ...
)
```

Same for each field on the card-entry screen.

Re-run. Now the wireframe JSON for those fields contains no value at
all — even the field name doesn't appear in the captured tree.

### What you see in Dash0

For any session, the timeline shows:

- A scrubbable sequence of screenshots, all redacted
- Wireframe records that link to a single canonical wireframe per
  layout via `mobile.wireframe.id` (the dedup at work)
- Card-entry and phone-rewards fields completely absent from the
  capture metadata

### Adoption friction

- **PCI scope:** the redaction defaults plus the per-field opt-out
  mean card numbers never leave the device. If your kiosk team has a
  separate PCI compliance review process, this section is what to
  show them
- **Performance:** wireframe capture is ~1-5KB per screen and
  rate-limited. Screenshot is heavier (~50-500KB) but defaults to
  off-screen-view-only, not every tap
- **Storage:** captures land in the same OTLP pipeline as logs and
  spans — same retention rules, no separate storage to manage
- **Opt-out entirely:** set `screenshotConfig.enabled = false` and
  `wireframeConfig.enabled = false`. Everything else still works

---

## What's next

You now have:

- Auto-instrumented taps, screens, lifecycle, network errors
- A journey span per customer interaction
- Business-meaningful events for funnel analysis
- Selective context flush on payment errors
- Visual replay with PII redaction

Where to go from here:

- **`docs/AUTO_INSTRUMENTATION.md`** — every signal the SDK auto-captures
- **`docs/CONFIGURATION_GUIDE.md`** — every config knob, including
  custom policies
- **`docs/EXPORT_MODES.md`** — when to use CONTINUOUS vs HYBRID vs
  CONDITIONAL
- **`docs/reference/TELEMETRY_SIGNALS.md`** — the canonical attribute
  reference for every record the SDK emits
