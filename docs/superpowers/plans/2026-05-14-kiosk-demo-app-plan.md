# Kiosk Demo App + Tutorial — Implementation Plan

**Spec:** `docs/superpowers/specs/2026-05-14-kiosk-demo-app-design.md`
**Status:** Active
**Date:** 2026-05-14
**Mode:** Autonomous overnight implementation; human reviews in the morning

## Goal of this run

Produce a working, runnable kiosk-demo Android app + the five-part tutorial document, both committed to git, by the time the human wakes up. The kiosk-demo lives in a separate local directory; the tutorial lives in `mobile-otel/docs/guides/`.

Success = a `./gradlew assembleDebug` that produces an APK + a tutorial doc that reads coherently end-to-end.

## Pre-flight decisions (no human in the loop)

- **AGP / Kotlin version**: match `mobile-otel/` exactly to avoid version churn. AGP 9.0, Kotlin 2.1, Compose BOM, JVM target 17.
- **Min/target SDK**: minSdk 26 (matches `mobile-otel/`), targetSdk 36.
- **Gradle wrapper**: copy from `mobile-otel/examples/demo-app/` so it just works.
- **No git remote, no push**: just `git init` + initial commit. Human pushes wherever they want.
- **Honest placeholders only**: no asset hunting, no MP4, no WAV files in the repo.

## Step-by-step execution

Each step is sized to be small enough to recover from cleanly. If a step fails, I stop, diagnose, fix, and resume — I do not push forward into broken builds.

### Step 0: scaffold + Gradle (15 min budget)

1. Create `~/Projects/Dash0/kiosk-demo/`
2. `git init` inside it
3. Copy gradle wrapper from `mobile-otel/examples/demo-app/`
4. Write `settings.gradle.kts` (single `:app` module)
5. Write project-level `build.gradle.kts` (AGP + Kotlin plugin)
6. Write `app/build.gradle.kts` (single `dash0` flavor, debug + release, Compose, **no observability deps**)
7. Write `gradle.properties` matching `mobile-otel/`
8. Write `app/src/main/AndroidManifest.xml` (single Activity, no permissions besides INTERNET for Part 4 of tutorial)
9. **Verify**: `./gradlew :app:tasks` succeeds

### Step 1: data model + menu catalog (15 min budget)

1. `menu/Models.kt` — the data classes from the spec
2. `menu/MenuCatalog.kt` — 30 items across 7 categories, snapshot-dated, hand-tuned plausible prices
3. **Verify**: file compiles via `./gradlew :app:compileDebugKotlin`

### Step 2: ViewModels + config (20 min budget)

1. `KioskConfig.kt` — `idleTimeoutMs` (default 60s), `soundEnabled` (default false)
2. `KioskViewModel.kt`:
   - `StateFlow<KioskState>` with `Attract` / `Active(lastInteractionAt: Long)` sealed states
   - `touch()` updates `lastInteractionAt`
   - Coroutine in `init {}` watches state; on timeout while Active, transitions to Attract
   - `sessionId: String` regenerated on each `Attract → Active` transition
   - `orderConfirmed()` schedules an 8s return to Attract
3. `OrderViewModel.kt`:
   - `StateFlow<List<CartLine>>`
   - `addLine(item, mods, qty)`, `removeLine(index)`, `updateQty(index, delta)`, `clear()`
   - `totalCents()` derived
   - `currentItem: MenuItem?` for the customization sheet
   - `orderNumber: Int` — random per-session counter starting at 400
4. **Verify**: ViewModels compile

### Step 3: theme + brand (15 min budget)

1. `theme/Color.kt` — brand palette (deep magenta, gold, orange, neutrals)
2. `theme/Type.kt` — Roboto-based, oversized headers for kiosk feel
3. `theme/Theme.kt` — `KioskTheme` composable wrapping Material 3
4. `BrandAssets.kt` — single seam exposing `brandName`, `brandLogoText`, `brandPrimaryColor`, plus a `foodIcon(itemId)` lookup that returns an `ImageVector` placeholder per item
5. **Verify**: theme files compile

### Step 4: shared UI components (15 min budget)

1. `ui/components/FoodPlaceholder.kt` — Compose that renders a solid-color rounded rectangle with the item name centered (the food image stand-in)
2. `ui/components/PulseBox.kt` — animated pulse modifier used by the attract screen
3. **Verify**: compose preview works (manual visual confirm deferred to human review)

### Step 5: AttractScreen (15 min budget)

1. `ui/attract/AttractScreen.kt`
2. Full-bleed gradient background (brand colors animating slowly)
3. Centered logo composable
4. "Tap to Order" pulse text below
5. Whole screen has `Modifier.clickable { onTap() }` — calls `kioskViewModel.touch()` + `onTap()`
6. Slow Ken-Burns animation on the gradient
7. **Verify**: compile

### Step 6: MenuScreen + ItemDetailSheet (30 min budget)

1. `ui/menu/MenuScreen.kt`:
   - Adaptive layout: `widthDp >= 600` → 2-column (category rail left + item grid right); else stacked (category tabs + item grid)
   - Item grid renders FoodPlaceholder + name + price for each item in the selected category
   - Tapping an item opens `ItemDetailSheet`
   - Header: brand logo + cart icon with badge (item count)
2. `ui/menu/ItemDetailSheet.kt`:
   - `ModalBottomSheet`
   - FoodPlaceholder, name, description, customization checkboxes, quantity stepper
   - "Add to Cart $X.XX" sticky button
   - Combo upsell row when `comboEligible = true`
3. Every tap on this screen calls `kioskViewModel.touch()`
4. **Verify**: compile

### Step 7: CartScreen + UpsellPrompt (15 min budget)

1. `ui/cart/CartScreen.kt`:
   - List of cart lines with line totals
   - Quantity steppers, remove buttons
   - Subtotal + tax + total
   - "Place Order" CTA → navigates to upsell modal
2. `ui/checkout/UpsellPrompt.kt`:
   - Modal with "Try Crunchy Wrap Supremo for $2.49?" — single suggestion
   - "Add it" / "No thanks" buttons
   - After dismissal → navigates to CheckoutScreen
3. **Verify**: compile

### Step 8: CheckoutScreen — PhoneRewards + CardEntry (25 min budget)

1. `ui/checkout/CheckoutScreen.kt` — orchestrates the multi-step checkout flow
2. `ui/checkout/PhoneRewardsStep.kt`:
   - "Enter phone for points (optional)"
   - Phone input field
   - "Skip" button + "Continue" button
3. `ui/checkout/CardEntryStep.kt`:
   - Standard 4-field card form
   - Pre-populated with Stripe test card
   - "Pay $X.XX" button → 2-second "Processing…" overlay → confirm
4. **Verify**: compile

### Step 9: OrderConfirmedScreen (10 min budget)

1. `ui/confirm/OrderConfirmedScreen.kt`:
   - Big "Your order is #428"
   - Brand-colored check mark
   - "Returning to start in 8…" countdown
   - Auto-navigates to Attract on timer expiry
2. **Verify**: compile

### Step 10: MainActivity + navigation (15 min budget)

1. `KioskApp.kt` — Application class, no SDK init (will be added by tutorial)
2. `MainActivity.kt`:
   - Holds `KioskViewModel` and `OrderViewModel` via `viewModel()` factory
   - `NavHost` with routes: `attract`, `menu`, `cart`, `checkout`, `confirmed`
   - Observes `KioskViewModel.state`; on `Attract` while not on attract route, navigates back
   - Wraps the whole NavHost in `Modifier.pointerInteropFilter { kioskViewModel.touch(); false }` to capture every touch
3. **Verify**: compile

### Step 11: audio generation (15 min budget)

1. `audio/SoundEffects.kt`:
   - `playTap(context)` — generates a sine-wave 80ms tone on first call, caches WAV in `cacheDir`, plays via `SoundPool`
   - `playConfirm(context)` — generates a 1s chime, plays via `MediaPlayer`
   - All gated by `KioskConfig.soundEnabled`
2. Wire `playTap` to every tap (via the global `touch()` listener)
3. Wire `playConfirm` to `OrderConfirmedScreen` first composition
4. **Verify**: compile

### Step 12: build APK (10 min budget)

1. `./gradlew :app:assembleDebug`
2. **Verify**: APK exists at `app/build/outputs/apk/debug/app-debug.apk`
3. **Smoke-test** (if emulator is up): `adb install` and check the app launches to Attract screen

### Step 13: README + checklist + initial commit (10 min budget)

1. `README.md`:
   - One-paragraph project description
   - Build + run instructions
   - "What is this?" framing — "Dash0's interpretation of a kiosk experience"
   - Form-factor note: optimized for Pixel Tablet AVD portrait
   - Link to tutorial in `mobile-otel/docs/guides/`
2. `BEFORE-DEMO-CHECKLIST.md`:
   - Every asset placeholder with replacement instructions
   - Idle timeout default and how to tune
   - Sound: how to enable
3. `.gitignore` — standard Android + Gradle
4. `git add` + `git commit -m "feat: initial kiosk-demo app"`
5. **Verify**: `git log` shows commit, working tree clean

### Step 14: tutorial document (30 min budget)

1. `mobile-otel/docs/guides/TUTORIAL_KIOSK_INSTRUMENTATION.md`
2. Five parts following the spec's structure
3. Each part has: intro, code snippet, expected Dash0 result, adoption-friction callout
4. Each code snippet is small enough to copy-paste
5. **Verify**: doc reads coherently end-to-end (re-read once)

### Step 15: commit tutorial + overnight report (10 min budget)

1. `cd mobile-otel`, `git add docs/guides/TUTORIAL_KIOSK_INSTRUMENTATION.md`
2. `git commit -m "docs(tutorial): add kiosk-app instrumentation tutorial"`
3. Push to `main`
4. Produce overnight report at the end of the conversation

## Total time budget

~4 hours of focused work. Padding: if any step blows past 2x its budget, I pause and reassess rather than push through with a broken build.

## Risks for this autonomous run

| Risk | Mitigation |
|---|---|
| Gradle/AGP version mismatch breaks the build | Copy verbatim from `mobile-otel/examples/demo-app/` |
| Compose Navigation API churn | Use the most recent stable API; if breakage, fall back to state-hoisted routing |
| Audio generation produces invalid WAV | Default `soundEnabled = false` means this never runs unless someone flips it on |
| Tutorial code snippets reference SDK APIs that don't exist | Snippets use the actual SDK API I've worked with all week — `OTelMobile.start`, `MobileConfig`, etc. |
| 30-item menu takes too long to hand-write | Generate from a structured template; quality over quantity (20 items if needed) |
| Compose `pointerInteropFilter` is deprecated in newer Compose | Use `Modifier.pointerInput { detectTapGestures }` if needed |
| `viewModel()` factory in MainActivity not reachable | Use `ViewModelProvider` directly if needed |

## What I will NOT do autonomously

- Push to a remote git repo (kiosk-demo or otherwise)
- Add any observability/Dash0 dependencies to the kiosk-demo `build.gradle.kts`
- Modify anything in `mobile-otel/` except adding the new tutorial file
- Make any decisions about the brand identity beyond "fast-food chain palette"
- Generate "Taco Bell" trademarked text anywhere in source code

## Self-review of this plan

Reading the plan back:

1. **Steps are properly sized** — most are 10-20 min. The largest (MenuScreen + ItemDetail) is 30 min and could split if it runs long.
2. **Each step has a clear verify gate** — compile check, file exists, or smoke-test.
3. **Dependencies between steps are linear** — no cross-cutting refactors. Steps can stop and resume.
4. **Risks are real and have mitigations** — not just hand-waving.
5. **The "what I will NOT do" section is concrete** — protects against autonomous-mode scope creep.

One gap: I don't have a Compose Preview pass anywhere. That's OK because the human will visually review in the morning; my job is to produce a buildable artifact.

Approved by self. Executing.
