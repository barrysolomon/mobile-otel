# Kiosk Demo App + Instrumentation Tutorial — Design

**Status:** Draft
**Date:** 2026-05-14
**Author:** Barry Solomon + Claude
**Audience:** The Taco Bell kiosk team (sales-demo asset) + Dash0 engineers building the tutorial

## Motivation

Dash0 has an opportunity to demo the Mobile Observability SDK to Taco Bell's kiosk team — the engineers who own the experience customers actually use when ordering. The strongest demo would be an Android app the kiosk team recognizes instantly as "their world" — accurate menu, real flow, real-feeling idle/attract loop — then a tutorial that walks one of their engineers through instrumenting it with our SDK.

The dual deliverable is intentional:
- The **kiosk app** is the recognizable, faithful clone — built **without** any observability dependencies. The bare uninstrumented baseline.
- The **tutorial** is what makes the kiosk app valuable: it shows what the SDK adds, step by step, with each step revealing a payoff in Dash0 that a kiosk product manager would care about.

## Goals

1. A Kotlin Android app that matches Taco Bell's real kiosk experience faithfully enough that the kiosk team recognizes their own product.
2. **Zero observability code** in the kiosk app itself — `build.gradle.kts` has no SDK dependency. The tutorial adds it.
3. A self-paced tutorial that takes a developer from "uninstrumented app" to "rich Dash0 telemetry" in under one hour.
4. Runs on a standard Android emulator (no special hardware) but is optimized for tablet-form-factor landscape orientation.

## Non-goals

- Real payment integration (fake "Processing..." screen)
- Multi-language support
- Accessibility polish beyond what Compose provides by default
- POS integration, receipt printing, loyalty/rewards
- A test suite for the kiosk app itself (it's a demo)
- iOS or React Native variants — Android-only for this round

## Audience expectations

The Taco Bell kiosk team knows kiosk UX better than anyone. Anything we get wrong, they'll notice. The bar is:
- Item names are real TB menu items
- Prices roughly match recent in-store pricing
- The flow matches the real kiosk: tap → menu → customize → cart → checkout → confirmation
- Brand colors are accurate (TB purple `#702082`, magenta `#F0E142`, supporting palette)
- Has the kiosk-specific behaviors they live with daily: attract loop, idle timeout, upsell prompts, customization flow

## Architecture

Single-Activity Android app:
- Kotlin 2.1, Jetpack Compose Material 3
- MVVM with `ViewModel` + `StateFlow`
- Compose Navigation between three top-level screens
- `kotlinx.coroutines` for the idle timer
- `androidx.media3` for the attract-loop video player
- No backend, no network — menu is static Kotlin constants

Three screens:
1. **AttractScreen** — full-bleed looping food video, "Tap to Order" pulse animation
2. **OrderScreen** — left-rail categories, right-grid items, customization sheet, cart drawer, checkout
3. **OrderConfirmedScreen** — "Your order is #428", auto-returns to attract after 8s

A `KioskController` singleton owns the global idle timer. Every Compose root wraps a `Modifier.pointerInput { detectAnyGesture { kioskController.touch() } }` that resets `lastInteractionAt`. A coroutine watches this; if `now - lastInteractionAt > 30s` while in `Active`, it transitions back to `Attract` and clears the cart.

```
AttractScreen ──tap──▶ OrderScreen (Menu)
   ▲                        │
   │                        ├──▶ ItemDetail (customize, as bottom sheet)
   │                        │       └──▶ back to Menu (added to cart)
   │                        │
   │                        ├──▶ Cart (right drawer)
   │                        │       └──▶ Checkout (fake payment)
   │                        │              └──▶ OrderConfirmed
   │                        │                       │
   │ ◀──── 30s timeout ─────┴───────────────────────┘
   │ ◀──── after order confirmed (8s auto-return) ──┘
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
    val comboEligible: Boolean
)

data class Customization(
    val id: String,
    val label: String,           // "No onions", "Extra cheese", "Make it a combo"
    val deltaPriceCents: Cents,  // 0, +50, +199
    val isDefault: Boolean       // true = included by default; tap to remove
)

data class CartLine(val item: MenuItem, val mods: List<Customization>, val qty: Int)

data class Order(val lines: List<CartLine>, val orderNumber: Int, val totalCents: Cents)
```

Menu lives in `app/src/main/java/com/dash0/kiosk/menu/MenuCatalog.kt` as a hand-written constant. Categories: Cravings Box, Tacos, Burritos, Specialties, Combos, Sides, Drinks, Sauces. ~30-40 items total.

## Kiosk-specific behaviors

### Attract loop

- Plays a muted looping food b-roll video in `res/raw/attract_loop.mp4`
- "Tap to Order" pulse animation overlays the video
- Any touch transitions to `OrderScreen`
- Attract has a slow Ken-Burns zoom; the pulse is on a 1.5s sine wave

### Idle timeout

- 30s of no input on OrderScreen or beyond → returns to attract, cart cleared
- 8s on OrderConfirmedScreen → auto-returns to attract
- Timer pauses during animations/transitions

### Upsell prompts

Two upsell seams:
1. **Add-to-cart**: When adding an a-la-carte item that's `comboEligible`, the customization sheet's footer shows "Make it a combo for $1.99". This is a customization toggle, not a separate modal.
2. **Pre-checkout**: Before payment, a modal "Try our new Crunchwrap Supreme for $2.49?" appears once per order. Two buttons: "Add it" / "No thanks". The eligible item is rotated based on a constant suggestion list (so the tutorial can show how to track which suggestion converts).

### Customization

Per-item bottom sheet with:
- Item image, name, description
- List of `Customization` toggles
- Quantity stepper
- "Add to Cart $X.XX" sticky button

Default customizations (e.g., "Onions" on a Crunchy Taco) start ON; tapping removes them.

### Order reset

When the kiosk times out from `Active → Attract`:
- Cart is cleared
- Customization sheet is dismissed
- A `kiosk_session_id` is rotated (so the next customer starts a new session)

This is critical to feel real and is a great instrumentation seam later (the tutorial uses it to show abandoned-order tracking).

## Form factor

**Primary target**: Pixel Tablet AVD profile (10.95", 2560x1600) rotated to **portrait** orientation. This matches the real TB kiosk hardware (Samsung KM24A and similar — 24" panels mounted vertically) most closely.

**Secondary target**: Phone landscape — adaptive Compose layout that falls back to a single-column flow on phones. Lets the kiosk team demo on whatever Android device they have handy.

Specifically:
- Two-column layout (category rail + item grid) when `windowSizeClass.widthSizeClass >= Medium`
- Single-column flow (category tabs across top, item grid below) on phone-class devices

## Brand assets

- **Colors**: TB brand palette in `Color.kt`:
  - `BellPurple = #702082`
  - `BellMagenta = #F0E142`
  - `BellOrange = #F4A41E`
  - Plus standard neutrals and a kiosk-specific dark surface tone
- **Fonts**: Roboto bundled with Android. Header text uses Roboto Bold + extended letter spacing as an approximation of Bellscript (the real TB display font isn't redistributable)
- **Logo**: TB logo as a vector drawable. README notes the final logo asset should come from TB's brand library before the actual demo recording
- **Food images**: Stock photos for now, one per menu item, optimized to ~150 KB each. Path: `res/drawable-nodpi/`. README flags these as placeholder
- **Attract video**: One short loop in `res/raw/`. README flags this as placeholder — should be replaced with TB's actual brand library before the demo

## Audio

- **Tap sound**: Short royalty-free WAV played on every interactive tap. ~80ms beep at ~800Hz with a soft envelope
- **Order confirmed chime**: 1-second chime on `OrderConfirmedScreen` reveal
- Both bundled in `res/raw/`. Volume controlled by a global toggle in `KioskController` (so the tutorial can show sound on/off as a config)

Audio routing uses `SoundPool` for the tap (low-latency, polyphonic) and `MediaPlayer` for the confirmation chime (one-shot, OK to allocate).

## File layout

```
examples/kiosk-app/
├── README.md                  ← Setup instructions; flags placeholder assets
├── android/
│   ├── build.gradle.kts       ← NO observability dependencies
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/dash0/kiosk/
│   │   │   ├── KioskApp.kt
│   │   │   ├── MainActivity.kt
│   │   │   ├── KioskController.kt
│   │   │   ├── menu/
│   │   │   │   ├── MenuCatalog.kt
│   │   │   │   └── models.kt
│   │   │   ├── ui/
│   │   │   │   ├── attract/AttractScreen.kt
│   │   │   │   ├── order/OrderScreen.kt
│   │   │   │   ├── order/MenuPane.kt
│   │   │   │   ├── order/ItemDetailSheet.kt
│   │   │   │   ├── order/CartDrawer.kt
│   │   │   │   ├── order/CheckoutScreen.kt
│   │   │   │   ├── order/UpsellPrompt.kt
│   │   │   │   ├── confirm/OrderConfirmedScreen.kt
│   │   │   │   └── theme/
│   │   │   │       ├── Color.kt
│   │   │   │       ├── Theme.kt
│   │   │   │       └── Type.kt
│   │   │   └── audio/SoundEffects.kt
│   │   └── res/
│   │       ├── drawable-nodpi/  ← food images
│   │       ├── raw/             ← attract video + sounds
│   │       └── values/
│   └── settings.gradle.kts
└── docs/
    └── ASSETS.md              ← Per-asset attribution + replacement instructions
```

## Tutorial structure

The tutorial lives separately at `docs/guides/TUTORIAL_KIOSK_INSTRUMENTATION.md`. Five parts, each 5-10 minutes, designed to run inside one hour total. Each part follows the same shape:

1. **What you're adding** — one sentence
2. **Why a kiosk PM cares** — the business question this answers
3. **The code change** — usually 5-30 lines, copy-paste-able
4. **What you see in Dash0** — exact query, expected result, what to point at

### Part 1 — One line of code

Add `OTelMobile.start(this, MobileConfig(...))` in `KioskApp.onCreate`. Run the app, tap around. Open Dash0 → filter `service.name = kiosk-demo`. **Payoff:** `ui.tap`, `ui.screen_view`, `app.foreground`, `app.background`, `device.heartbeat` all appear automatically. **Kiosk PM question answered:** "Is the app working? Are people interacting?"

### Part 2 — Identify the journey

Wrap each customer interaction in a journey span: `startJourney("order")` when leaving Attract, `endJourney()` on confirm OR on timeout (abandoned). **Payoff:** Every interaction becomes a trace waterfall. **Kiosk PM question:** "How long does an average order take? Where do customers stall?"

### Part 3 — Custom events that matter to the business

Emit named events at every business-meaningful point: `item.added_to_cart`, `customization.modified`, `upsell.accepted`, `upsell.declined`, `cart.viewed`, `checkout.started`, `order.placed`. Add attributes: item id, category, modifications, price delta. **Payoff:** Build a funnel: attract → menu → item-detail → cart → checkout → confirmation. **Kiosk PM question:** "What's our cart abandonment rate? Which upsells convert?"

### Part 4 — Selective flush on payment errors

Wire the fake payment screen to occasionally simulate a failure (an HTTP 500 to httpbin.org). The auto-instrumented OkHttp interceptor emits `http.error`; the default policy fires a `flushWindow(2)` minutes. **Payoff:** Even rare payment failures surface in Dash0 with the full context of what the user did before the failure. **Kiosk PM question:** "When checkout fails, what was the customer about to buy?"

### Part 5 — Screenshot/wireframe payoff

Enable `WireframeInstrumentation` and `ScreenshotInstrumentation` in `MobileConfig`. **Payoff:** Order replay — every step the customer took, attached to the journey trace, viewable in Dash0's UI. **Kiosk PM question:** "Show me literally what this customer saw when they walked away."

The tutorial closes with a "What's next" section pointing at the broader SDK guides.

## File layout for the tutorial

```
docs/guides/TUTORIAL_KIOSK_INSTRUMENTATION.md  ← The five-part tutorial
```

Existing `TUTORIAL_ANDROID_QUICKSTART.md` will get a one-line cross-reference but otherwise stays as-is.

## Risks + open questions

| Risk | Mitigation |
|---|---|
| Asset quality (food images, attract video) won't look professional | Use the best stock available initially; README flags that production demo should swap in TB's real brand assets |
| Real TB menu prices drift | Snapshot prices to a `MenuCatalog.kt` constant — easy to update in one place, README notes "prices accurate as of YYYY-MM-DD" |
| Tutorial parts depend on each other in subtle ways (e.g., Part 5 assumes Part 1 is done) | Each part links back to its predecessor; the README has a "If you got stuck, you can also clone the `tutorial-complete` branch" instruction |
| The TB kiosk team uses a custom Android skin we can't replicate | We're not trying to replicate their hardware skin — we're replicating the experience. The READMEs explicitly call this out as a UX clone, not a hardware clone |

## Out-of-scope items (parked)

- iOS and React Native variants — Android-only for this round
- Real payment integration
- Multi-language
- POS / receipt / loyalty integration
- Test suite beyond compile-and-smoke
- AVD profile customization (a `kiosk.png` device profile + automated boot)
- Voice prompts on the kiosk

## What success looks like

1. Someone on Dash0's sales team can demo the kiosk app cold and have a kiosk PM say "yeah, that's our kiosk"
2. A developer can complete the five-part tutorial in under one hour and see real telemetry from their interactions in Dash0
3. The kiosk app's `build.gradle.kts` shows zero `dash0` / `opentelemetry` dependencies until the tutorial adds them in Part 1
