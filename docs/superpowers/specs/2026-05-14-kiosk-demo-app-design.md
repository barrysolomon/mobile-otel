# Kiosk Demo App + Instrumentation Tutorial — Design

**Status:** Draft v3 (post second review pass)
**Date:** 2026-05-14
**Author:** Barry Solomon + Claude
**Audience:** A specific customer's kiosk team (scheduled meeting in 2–4 weeks)

## Motivation

A scheduled meeting with a customer's kiosk team — the engineers who own the experience customers use to order — needs a sales-demo asset. The strongest demo is an Android app the kiosk team recognizes instantly as "their world" — accurate menu, real flow, real-feeling idle/attract loop — paired with a tutorial that walks one of their engineers through instrumenting it with the Dash0 Mobile Observability SDK.

The dual deliverable is intentional:

- The **kiosk app** is the recognizable, faithful recreation — built **without** any observability dependencies. The bare uninstrumented baseline.
- The **tutorial** shows what the SDK adds, step by step, with each step revealing a payoff in Dash0 that a kiosk product manager would care about.

## Goals

1. A Kotlin Android app that matches a fast-food kiosk experience faithfully enough that the kiosk team recognizes their product.
2. **Zero observability code** in the kiosk app itself — `build.gradle.kts` has no SDK dependency. The tutorial adds it.
3. A self-paced tutorial that takes a developer from "uninstrumented app" to "rich Dash0 telemetry" in under one hour.
4. A clear privacy/PII story baked into the tutorial — the kiosk team will ask "what does Dash0 see about our customers?" within five minutes.
5. Runs on standard Android emulators (no special hardware) but optimized for tablet portrait orientation (the real kiosk form factor).

## Non-goals

- Real payment integration (fake "Processing…" screen)
- Multi-language support
- Accessibility polish beyond what Compose provides by default
- POS integration, receipt printing, loyalty/rewards persistence
- A test suite for the kiosk app itself (it's a demo)
- iOS or React Native variants — Android-only

## Strategy decisions (locked from review pass)

These decisions shape the rest of this spec and should not change without explicit conversation.

### 1. Repo location: separate directory (private later)

The kiosk demo lives in a **separate directory** at `~/Projects/Dash0/kiosk-demo/`, NOT inside `mobile-otel/`. Reasons:

- Brand-evocative content stays out of a public OSS repo
- Audience is one specific customer team, not the OTel community
- A `git init` + push to a private GitHub repo can happen later without restructuring

This directory will be its own git repo (`git init` ran during scaffolding). Pushing to a private GitHub remote is deferred to the human user.

### 2. Tutorial location: kept generic in public repo

The tutorial lives at `mobile-otel/docs/guides/TUTORIAL_KIOSK_INSTRUMENTATION.md` in the public repo, **titled and phrased generically** — "Instrumenting a kiosk app" — with no brand-specific text. It walks through instrumenting "a kiosk app" with the example screenshots and code generic. The kiosk-demo repo's README points at this tutorial.

This decouples the educational value (public, reusable) from the brand-evocative artifact (private, customer-specific).

### 3. Brand assets: honest placeholders, real assets swap at meeting prep

The kiosk-demo app ships with **honest placeholders**:

- Brand colors approximate a fast-food chain palette (deep magenta, gold, off-white) — generic enough that a kiosk team will say "ah, your brand" but legally we're not appropriating a specific trademark
- Logo placeholder is the words "KIOSK DEMO" in the chain palette
- Food images are solid-color rectangles labeled with the item name (functional, not pretty)
- Attract "video" is a Compose animation, not an MP4 (no asset hunting)

A `BEFORE-DEMO-CHECKLIST.md` file in the kiosk-demo repo lists every asset that should be swapped to real branded versions before the meeting. The swap points are isolated to `BrandAssets.kt` and `res/drawable-nodpi/` — one file and one directory.

Reasoning: this lets the implementation complete now without any blocked-on-assets steps. The customer-specific branding becomes a 30-minute meeting-prep task, not a multi-day asset hunt.

### 4. PII posture: realistic-then-redact

The tutorial does NOT lead with "look at all the safety knobs." It leads with capability — "look at the customer's full session, every tap they made before the payment failed" — and then immediately shows the redaction config that prevents the SDK from capturing card numbers and phone numbers.

The demo app has TWO deliberate PII moments to demonstrate this on:

- **Phone-number entry** for rewards ("Enter your phone for points")
- **Card-entry screen** for payment (Stripe test card `4242 4242 4242 4242` pre-populated — well-known and not a real card)

Both are NEW features added specifically because they make Part 5 of the tutorial real instead of theoretical.

### 5. Meeting timeline: 2–4 weeks

The 2–4 week window means:

- **In scope**: full spec below — both form factors, ~30 menu items, attract animation, all 5 tutorial parts, pre-meeting polish pass
- **Stretch (only if early)**: real-asset swap-in dry run, send-ahead artifact prep

## Audience expectations

The kiosk team knows kiosk UX better than anyone — anything we get wrong, they'll notice instantly. The bar is:

- Item names that resemble real fast-food menu items
- Plausible prices ($1.49–$6.99 range)
- The flow matches a real kiosk: tap → menu → customize → cart → checkout → confirmation
- Brand colors are accurate-feeling for the category
- Kiosk-specific behaviors they live with daily: attract loop, idle timeout, upsell prompts, customization flow, optional rewards/phone entry

Framing for the README and meeting intro: **"Dash0's interpretation of a kiosk experience, using your category's conventions as familiar territory."** Pre-empts the "you got X wrong" trap and refocuses on "look what the SDK shows you about this kind of app."

## Architecture

Single-Activity Android app:

- Kotlin 2.1, Jetpack Compose Material 3
- MVVM with `ViewModel` + `StateFlow` (no DI framework — `viewModel()` factory)
- **Compose Navigation** for screen routing (reverted from v2 — at 5+ screens it's simpler than hoisted state)
- `kotlinx.coroutines` for the idle timer
- No backend, no network — menu is static Kotlin constants

Two ViewModels (split for clean boundaries):

- `KioskViewModel`: session state (Attract / Active), idle timer, session id rotation, sound on/off config
- `OrderViewModel`: cart, current item being customized, totals, order number

Six top-level screens:

1. **AttractScreen** — Compose-animated full-bleed background, "Tap to Order" pulse
2. **MenuScreen** — left-rail categories, right-grid items
3. **ItemDetailSheet** — bottom sheet for customization (not a route, modal)
4. **CartScreen** — right drawer with line items, totals, "Place Order"
5. **CheckoutScreen** — phone-rewards (optional) → card entry → "Processing…"
6. **OrderConfirmedScreen** — "Your order is #428," auto-returns to attract after 8s

Any user interaction calls `kioskViewModel.touch()`. A coroutine watches `lastInteractionAt`; if `now - lastInteractionAt > idleTimeoutMs` while in `Active`, transitions back to `Attract` and clears the cart.

Idle timeout is configured by `KioskConfig.idleTimeoutMs` (default 60s) — tunable at meeting prep.

```
AttractScreen ──tap──▶ MenuScreen
   ▲                       │
   │                       ├──▶ ItemDetailSheet (modal)
   │                       │       └──▶ back to MenuScreen
   │                       │
   │                       ├──▶ CartScreen
   │                       │       └──▶ CheckoutScreen
   │                       │              ├──▶ phone-rewards (optional)
   │                       │              ├──▶ card-entry
   │                       │              └──▶ OrderConfirmedScreen
   │ ◀── idleTimeoutMs idle ┴───────────────────────┘
   │ ◀── 8s after confirmation ─────────────────────┘
```

## Data model

```kotlin
typealias Cents = Int

data class MenuCategory(val id: String, val name: String, val items: List<MenuItem>)

data class MenuItem(
    val id: String,
    val name: String,
    val basePriceCents: Cents,
    val imageRes: Int,
    val description: String,
    val customizations: List<Customization>,
    val comboEligible: Boolean,
)

data class Customization(
    val id: String,
    val label: String,           // "No onions", "Extra cheese", "Make it a combo"
    val deltaPriceCents: Cents,  // 0, +50, +199
    val isDefault: Boolean,      // true = included by default; tap to remove
)

data class CartLine(val item: MenuItem, val mods: List<Customization>, val qty: Int)

data class Order(
    val lines: List<CartLine>,
    val orderNumber: Int,
    val totalCents: Cents,
    val rewardsPhoneLast4: String?,  // null if customer skipped rewards
)
```

Menu lives in `app/src/main/java/com/dash0/kiosk/menu/MenuCatalog.kt` as a hand-written constant, dated at the top: `// Snapshot date: 2026-05-14`. Categories: Combos, Tacos, Burritos, Specialties, Sides, Drinks, Sauces. ~30 items total.

## Kiosk-specific behaviors

### Attract loop

- Full-bleed background gradient + slowly-animated food-icon arrangement (Compose only, no MP4)
- "Tap to Order" pulse animation (1.5s sine wave on scale + alpha)
- Any touch transitions to `MenuScreen`

Why no MP4: avoids asset hunting, keeps APK small, makes the demo reproducible. README notes this could be swapped to a real video at meeting prep.

### Idle timeout

- `KioskConfig.idleTimeoutMs` (default 60s)
- On `MenuScreen` and beyond: idle → return to attract, cart cleared, session id rotated
- `OrderConfirmedScreen`: fixed 8s auto-return regardless of idle setting

### Upsell prompts

Single upsell, two seams:

1. **Add-to-cart**: When adding a `comboEligible` item, the customization sheet shows a "Make it a combo for $1.99" toggle inline
2. **Pre-checkout**: One modal "Add a Crunchy Wrap Supremo for $2.49?" before payment. Two buttons: "Add it" / "No thanks"

Single suggestion across all orders — keeps the tutorial simple. Tutorial Part 3 instruments this so the funnel can be queried.

### Customization

Per-item bottom sheet with:

- Item image (solid-color placeholder rectangle initially), name, description
- List of `Customization` toggles
- Quantity stepper
- "Add to Cart $X.XX" sticky button

Default customizations (e.g., "Onions" on a Crunchy Taco) start ON; tapping removes them.

### Phone-rewards entry (NEW for PII demo)

Optional screen between `CartScreen` and card entry. Displays "Enter phone for points (optional)" with a phone input and "Skip" button. The phone number is stored only in `OrderViewModel` memory (never persisted, never sent over the wire) — this is a real-world signal-flow the kiosk team will recognize. Tutorial Part 5 uses this as the primary PII moment.

### Card-entry screen (NEW for PII demo)

Standard 4-field card form: card number, expiry MM/YY, CVV, ZIP. Pre-populated with Stripe test card `4242 4242 4242 4242 / 12/30 / 123 / 94103`. Tap "Pay $X.XX" → 2-second "Processing…" → `OrderConfirmedScreen`.

README explicitly calls out: "Real kiosks today often use tap-to-pay rather than manual card entry. This demo uses card entry deliberately because it makes the PII redaction tutorial more concrete." Avoids the team thinking we didn't know.

### Order reset

When the kiosk times out from `Active → Attract`:

- Cart is cleared
- Customization sheet is dismissed
- A `kiosk_session_id` is rotated (the next customer starts a new session)
- Order-in-flight (mid-checkout, etc.) is abandoned silently

## Form factor

**Primary target**: Pixel Tablet AVD profile (10.95", 2560×1600) rotated to **portrait** orientation. Closest standard emulator to the real kiosk hardware (24" portrait panels).

**Secondary target**: Phone landscape — adaptive Compose layout that falls back to a single-column stacked flow.

Breakpoint logic: use **screen width in dp** rather than `WindowSizeClass`. If `widthDp >= 600` → two-column (categories + items); else → stacked (categories as horizontal tabs, items below). This gets tablet-portrait right (which `WindowSizeClass` would have wrongly classified).

## Brand assets

- **Colors**: Approximate fast-food chain palette in `Color.kt` (`BrandPrimary` deep magenta `#702082`, `BrandAccent` gold `#F0E142`, `BrandSecondary` orange `#F4A41E`, neutrals, and a kiosk-specific dark surface tone). README notes these should be swapped to the customer's actual brand palette at meeting prep.
- **Fonts**: Roboto bundled with Android. Header text uses Roboto Bold + extended letter spacing.
- **Logo**: Text-only placeholder ("KIOSK DEMO" in brand colors) as a Compose composable. README notes the real logo SVG should drop into `res/drawable/` and replace the placeholder at meeting prep.
- **Food images**: Solid-color rectangles labeled with item name, generated as Compose composables (not raster). README notes real photos should drop into `res/drawable-nodpi/` and replace the placeholder composable at meeting prep.
- **Attract animation**: Compose-only animated gradient + bouncing food icons. README notes a real video can replace this if desired.

Single seam for the asset swap: `BrandAssets.kt` is the only file the meeting-prep person needs to edit (besides dropping image files into `res/drawable-nodpi/`).

`BEFORE-DEMO-CHECKLIST.md` lives at the repo root and lists every asset placeholder with a one-line replacement instruction.

## Audio

- **Tap sound**: Short royalty-free WAV played on every interactive tap (~80 ms, ~800 Hz, soft envelope). `SoundPool` for low-latency polyphonic playback. **Will be generated programmatically** as a fallback — no asset hunting required.
- **Order confirmed chime**: 1-second chime on `OrderConfirmedScreen` reveal. `MediaPlayer` one-shot. Programmatically generated.
- **Global default: OFF.** Real kiosks are often muted; we ship with sound off. `KioskConfig.soundEnabled` (default `false`) gates everything. The tutorial does not enable sound; this is for the live demo specifically.

Sound generation strategy: at app startup, if no WAV files are present in `res/raw/`, generate them programmatically using `AudioTrack` writing a simple sine wave to a `ByteArray`, then write to the cache dir as a WAV. This means the repo ships with NO audio files but the demo has sound when toggled on.

## PII redaction defaults

This is the part that materially affects the demo's credibility. Three layers of PII protection are added across the tutorial:

1. **Default-off capture** (Part 1): when the SDK is initialized, `screenshotConfig.enabled = false` and `wireframeConfig.enabled = false`
2. **Text-redaction defaults** (Part 5, when capture is enabled): `screenshotConfig.redactTextViews = true` and `wireframeConfig.redactText = true`
3. **Per-field opt-out**: phone-rewards input and card-entry inputs tagged with `MobileSemconv.PII = true` so they're stripped from spans/logs regardless of capture mode

Tutorial Part 5 walks through enabling capture, showing the gap, then closing it with the redaction config.

## File layout

```
~/Projects/Dash0/kiosk-demo/                       ← separate local git repo
├── README.md                                      ← build + run instructions + meeting-prep checklist
├── BEFORE-DEMO-CHECKLIST.md                       ← what real assets need swapping
├── settings.gradle.kts
├── build.gradle.kts                               ← project-level
├── gradle.properties
├── gradle/wrapper/...                             ← shared from mobile-otel/
├── app/
│   ├── build.gradle.kts                           ← NO observability dependencies
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/dash0/kiosk/
│       │   ├── KioskApp.kt
│       │   ├── MainActivity.kt
│       │   ├── KioskViewModel.kt                  ← session + idle timer
│       │   ├── OrderViewModel.kt                  ← cart + customization
│       │   ├── KioskConfig.kt                     ← idle timeout, sound on/off
│       │   ├── BrandAssets.kt                     ← single seam for swapping in real assets
│       │   ├── menu/
│       │   │   ├── MenuCatalog.kt                 ← snapshot-dated constant
│       │   │   └── Models.kt
│       │   ├── ui/
│       │   │   ├── attract/AttractScreen.kt
│       │   │   ├── menu/MenuScreen.kt
│       │   │   ├── menu/ItemDetailSheet.kt
│       │   │   ├── cart/CartScreen.kt
│       │   │   ├── checkout/CheckoutScreen.kt
│       │   │   ├── checkout/PhoneRewardsStep.kt
│       │   │   ├── checkout/CardEntryStep.kt
│       │   │   ├── checkout/UpsellPrompt.kt
│       │   │   ├── confirm/OrderConfirmedScreen.kt
│       │   │   ├── components/FoodPlaceholder.kt  ← solid-color image stand-in
│       │   │   └── theme/{Color,Theme,Type}.kt
│       │   └── audio/SoundEffects.kt              ← generates sounds on first call
│       └── res/
│           ├── drawable/                          ← logo placeholder; real logo drops here
│           ├── drawable-nodpi/                    ← (empty initially; real photos drop here)
│           ├── raw/                               ← (empty initially; audio generated)
│           └── values/

mobile-otel/                                       ← existing public repo
└── docs/guides/
    └── TUTORIAL_KIOSK_INSTRUMENTATION.md          ← five-part tutorial, generic phrasing
```

## Tutorial structure

The tutorial lives at `mobile-otel/docs/guides/TUTORIAL_KIOSK_INSTRUMENTATION.md`. Generic title and phrasing — "a kiosk app" not "the kiosk app." Five parts, each 5–15 minutes, designed to run inside one hour total. Each part has:

1. **What you're adding** — one sentence
2. **Why a kiosk PM cares** — the business question this answers
3. **The code change** — usually 5–30 lines, copy-paste-able
4. **What you see in Dash0** — exact query, expected result, what to point at
5. **Adoption-friction callout** — coexistence, footprint, opt-out (NEW from v2)

### Part 1 — One line of code

Add the dependency, add `OTelMobile.start(this, MobileConfig(serviceName = "kiosk-demo", …))` in `KioskApp.onCreate`. Run, tap around. Open Dash0 → filter `service.name = kiosk-demo`. **Payoff:** `ui.tap`, `ui.screen_view`, `app.foreground`, `app.background`, `device.heartbeat` appear automatically. **PM question:** "Is the app working? Are people interacting?"

### Part 2 — Identify the journey

Wrap each customer interaction in a journey span: `startJourney("order")` when leaving Attract, `endJourney()` on confirm OR on timeout (abandoned). **Payoff:** Every interaction is a trace waterfall. **PM question:** "How long does an average order take? Where do customers stall?"

### Part 3 — Custom events that matter to the business

Emit named events at every business-meaningful point: `item.added_to_cart`, `customization.modified`, `upsell.accepted`, `upsell.declined`, `cart.viewed`, `checkout.started`, `order.placed`. **Payoff:** Build a funnel. **PM question:** "What's our cart abandonment rate? Which upsells convert?"

### Part 4 — Selective flush on payment errors

Wire the fake payment screen to simulate a failure (HTTP 500 to httpbin.org). The auto-instrumented OkHttp interceptor emits `http.error`; the default policy fires a `flushWindow(2)`. **Payoff:** Even rare payment failures surface in Dash0 with full context. **PM question:** "When checkout fails, what was the customer about to buy?"

### Part 5 — Screenshot/wireframe — and how we protect the customer

Sequenced deliberately:

1. **Show what's possible.** Enable `WireframeInstrumentation` and `ScreenshotInstrumentation`. Walk through an order including the phone-rewards screen and card-entry screen.
2. **Acknowledge the elephant.** "Notice anything that shouldn't be in there? The card number. The phone number. Let's fix that."
3. **Enable text-redaction defaults.** `ScreenshotConfig(redactTextViews = true)`, `WireframeConfig(redactText = true)`. Re-run an order. Black bars over all text.
4. **Per-field opt-out for sensitive fields.** Tag phone-rewards and card-entry inputs with `MobileSemconv.PII = true`. Show that even the wireframe metadata doesn't carry the field's contents.

**Payoff:** "We get the full customer story for triage; we never leak customer data." **PM question:** "Show me literally what this customer saw — without showing me their card number."

Tutorial closes with a "What's next" section pointing at the broader SDK guides.

## Risks + open questions

| Risk | Mitigation |
|---|---|
| Brand assets won't look professional with honest placeholders | Acceptable for a working demo; `BEFORE-DEMO-CHECKLIST.md` lists every swap point for meeting prep |
| Kiosk team notices we don't have real tap-to-pay | README explicitly calls this out as a deliberate simplification for PII demo value |
| Tutorial part order depends on previous parts | Each part has a "starting from" reference to the previous state |
| Kiosk team has internal analytics; "drop in our SDK" feels presumptuous | Each tutorial part has an "adoption friction" callout explicitly addressing coexistence, footprint, opt-out |
| Idle timeout default (60s) is wrong | Configurable in `KioskConfig.idleTimeoutMs`; tuned at meeting prep |
| Compose Material 3 styling in custom palette | Custom `ColorScheme` in `theme/`; budget half a day for tuning |
| Sound generation produces tinny output | Acceptable — sound is off by default anyway; if it sounds bad in the demo, swap to a real WAV |

## Out-of-scope items (parked)

- iOS and React Native variants — Android-only
- Real payment integration
- Multi-language
- POS / receipt / loyalty persistence
- Test suite beyond compile-and-smoke
- AVD profile customization (a `kiosk.png` device profile + automated boot)
- Voice prompts on the kiosk
- Real-asset swap-in (done at meeting prep, not in this implementation)
- Pushing kiosk-demo to a private GitHub remote (done by human user)

## Sequencing (rough)

The implementation plan will detail this, but the spec assumes:

**Implementation tonight (autonomous run):**

1. Scaffold the kiosk-demo repo + Gradle
2. Menu catalog + data models
3. Two ViewModels (KioskViewModel, OrderViewModel)
4. All six screens
5. Theme, brand placeholders, audio generation
6. Build + smoke-test APK
7. Tutorial document

**Human follow-up (in the morning):**

1. `git init` already done; push to private GitHub remote
2. Visual polish review
3. Meeting-prep asset swap (when meeting is closer)

## What success looks like

1. Someone on Dash0's sales team can demo the kiosk app cold and have at least one kiosk team member say "yeah, that's our kiosk."
2. A kiosk engineer can complete the five-part tutorial in under one hour and see real telemetry from their interactions in Dash0.
3. The kiosk app's `build.gradle.kts` shows zero `dash0` / `opentelemetry` dependencies until Part 1 of the tutorial adds them.
4. The kiosk team leaves the meeting with no follow-up "but what about our customer data?" questions — because Part 5 of the tutorial already answered it.
5. The artifact is ready 1+ week before the meeting, allowing time for dry-runs and asset swaps.
