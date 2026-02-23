# Demo App Design - Mobile OTel Showcase

## Overview
A realistic e-commerce/social app that demonstrates all Mobile OTel instrumentation features through natural user interactions.

## App Concept: "ShopSocial" - Social Shopping App
A mobile app combining e-commerce with social features, perfect for demonstrating all telemetry capabilities.

---

## Screen Architecture

### 1. Main Screen - Bottom Navigation (5 tabs)
```
┌─────────────────────────────┐
│  [Debug Toolbar] (collapsible, small) │
├─────────────────────────────┤
│                             │
│    Tab Content Area         │
│    (RecyclerView/ViewPager) │
│                             │
│                             │
│                             │
├─────────────────────────────┤
│ 🏠 Feed | 🔍 Shop | ➕ | ❤️ | 👤 │
└─────────────────────────────┘
```

**Bottom Navigation Tabs:**
1. **Feed** - Social feed with posts
2. **Shop** - Product catalog
3. **Post** - Create content (camera/photo)
4. **Likes** - Saved items
5. **Profile** - User profile & settings

---

## Feature Breakdown by Screen

### 🏠 Feed Tab
**Purpose**: Demonstrates vitals, network, breadcrumbs, scrolling performance

**Features**:
- Infinite scroll feed with images
- Pull-to-refresh
- Like/comment buttons
- Share functionality
- Image loading (Glide/Coil)
- Deep links to products

**Telemetry Coverage**:
- ✅ Jank detection (scroll performance)
- ✅ Network requests (feed API)
- ✅ Image loading spans
- ✅ Navigation breadcrumbs
- ✅ User interactions (likes/comments)

**API Endpoints**:
- `GET /api/feed` - Load posts
- `POST /api/posts/{id}/like` - Like post
- `POST /api/posts/{id}/comment` - Add comment

---

### 🔍 Shop Tab
**Purpose**: Demonstrates e-commerce flows, search, filtering

**Features**:
- Search bar with autocomplete
- Category filters (chips)
- Product grid (RecyclerView)
- Sort options (price, rating)
- Add to cart button
- Product details page

**Telemetry Coverage**:
- ✅ Search queries (breadcrumbs)
- ✅ Filter selections
- ✅ Product views
- ✅ Cart operations
- ✅ Network requests (product API)

**API Endpoints**:
- `GET /api/products?q={query}&category={cat}` - Search products
- `GET /api/products/{id}` - Product details
- `POST /api/cart/add` - Add to cart

---

### ➕ Post Tab (Modal)
**Purpose**: Demonstrates camera, file uploads, permissions

**Features**:
- Camera capture
- Photo picker
- Caption input
- Location tagging (optional)
- Upload with progress
- Error handling (network failures)

**Telemetry Coverage**:
- ✅ Permission requests
- ✅ Camera usage
- ✅ File upload spans
- ✅ Upload progress tracking
- ✅ Error capture (upload failures)

**API Endpoints**:
- `POST /api/posts/upload` - Upload media
- `POST /api/posts/create` - Create post

---

### ❤️ Likes Tab
**Purpose**: Demonstrates saved state, database queries

**Features**:
- Grid of liked posts/products
- Remove from favorites
- Filter by type (posts/products)
- Local database (Room)

**Telemetry Coverage**:
- ✅ Database operations
- ✅ Local storage vitals
- ✅ List rendering performance

---

### 👤 Profile Tab
**Purpose**: Demonstrates settings, logout, user identity

**Features**:
- User info display
- Edit profile
- Settings menu
- Logout button
- Theme toggle (dark/light)
- Notification preferences
- Clear cache

**Telemetry Coverage**:
- ✅ User identity tracking
- ✅ Session termination (logout)
- ✅ Settings changes (breadcrumbs)

---

## Additional Screens

### Login/Signup Screen
**Purpose**: Demonstrates authentication flow

**Features**:
- Email/password fields
- Social login buttons (Google, Facebook)
- Form validation
- Loading states
- Error messages

**Telemetry Coverage**:
- ✅ User identification (login success)
- ✅ Form validation errors
- ✅ Network requests (auth API)
- ✅ Session start

**API Endpoints**:
- `POST /api/auth/login` - Login
- `POST /api/auth/signup` - Register
- `POST /api/auth/social` - Social login

---

### Product Detail Screen
**Purpose**: Demonstrates complex UI, WebView integration

**Features**:
- Image carousel
- Product info (title, price, rating)
- Reviews section
- Related products
- Add to cart button
- Share button
- **WebView with product description** (HTML content)

**Telemetry Coverage**:
- ✅ Image loading performance
- ✅ WebView instrumentation
- ✅ Scroll performance (long content)
- ✅ User interactions

---

### Cart/Checkout Screen
**Purpose**: Demonstrates transaction flows, payment errors

**Features**:
- Cart items list
- Quantity adjustment
- Price calculation
- Payment method selection
- Checkout button
- Success/failure states

**Telemetry Coverage**:
- ✅ Transaction events
- ✅ Payment errors
- ✅ Network requests (checkout API)
- ✅ Journey breadcrumbs (full checkout flow)

**API Endpoints**:
- `GET /api/cart` - Get cart
- `POST /api/cart/update` - Update quantity
- `POST /api/checkout` - Process payment

---

### Settings Screen
**Purpose**: Demonstrates config changes, deep settings

**Features**:
- Account settings
- Notification settings
- Privacy settings
- About/version info
- Debug tools (collapsible)

---

## Debug Toolbar (Collapsible)

**Location**: Top of screen, always accessible, minimal UI

```
┌─────────────────────────────┐
│ [▼] Debug: [Crash] [ANR] [HTTP 500] [Memory] [Jank] [Clear] │
└─────────────────────────────┘
```

**Collapsed (default)**:
- Single row with small icon

**Expanded**:
- 2 rows of buttons, 6 triggers total

**Triggers**:
1. **Crash** - Throw uncaught exception
2. **ANR** - Block main thread 6s
3. **HTTP 500** - Trigger server error
4. **Memory** - Allocate large objects
5. **Jank** - Force 10 dropped frames
6. **Clear** - Clear breadcrumbs/reset

---

## Mock API Backend

### Backend Behavior
- Use local MockWebServer or JSON files
- Introduce random delays (simulate network)
- Random errors (5% failure rate)
- Pagination support

### Endpoints Summary
```
# Auth
POST /api/auth/login
POST /api/auth/signup

# Feed
GET /api/feed?page={page}&limit={limit}
POST /api/posts/{id}/like
POST /api/posts/{id}/comment

# Products
GET /api/products?q={query}&category={cat}&page={page}
GET /api/products/{id}

# Cart
GET /api/cart
POST /api/cart/add
POST /api/cart/update
POST /api/checkout

# User
GET /api/user/profile
PUT /api/user/profile
GET /api/user/favorites
```

---

## WebView Integration Example

### Product Description WebView
**Content**: Rich HTML with images, tables, videos

```kotlin
webView.apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true

    // Add OTel WebView client
    webViewClient = OTelWebViewClient(
        tracer = otel.getTracer("webview"),
        config = WebViewConfig(
            capturePageLoads = true,
            captureResourceLoads = true,
            captureConsoleErrors = true
        )
    )

    loadUrl("https://api.example.com/products/123/description")
}
```

**Telemetry**:
- Page load time
- Resource loading (images, CSS, JS)
- JavaScript errors
- Navigation within WebView

---

## Journey Examples

### Happy Path: Purchase Flow
```
1. Launch app (cold start)
2. Login (auth network request)
3. Browse feed (scroll, jank detection)
4. Click product (navigation breadcrumb)
5. View product details (WebView load)
6. Add to cart (network request)
7. Checkout (network request)
8. Success (transaction event)
```

**Breadcrumbs Captured**:
- screen_enter: FeedScreen
- user_input: click_product_123
- screen_enter: ProductDetailScreen
- network: GET /products/123
- user_input: add_to_cart
- screen_enter: CheckoutScreen
- network: POST /checkout
- custom: checkout_success

### Error Path: Failed Checkout
```
1. Add item to cart
2. Go to checkout
3. Submit payment
4. HTTP 500 error
5. Error screen shown
```

**Telemetry**:
- Breadcrumbs leading to error
- HTTP 500 span with error status
- Error log with breadcrumbs attached
- Vitals at time of error
- Automatic flush triggered

---

## UI/UX Design Principles

### Color Scheme
- Primary: Modern blue (#2196F3)
- Accent: Orange (#FF9800)
- Background: White/Dark (theme aware)
- Error: Red (#F44336)

### Typography
- Title: 24sp, Bold
- Body: 16sp, Regular
- Caption: 12sp, Light

### Components
- Material Design 3
- RecyclerView for lists
- CardView for items
- BottomNavigationView
- ViewPager2 for tabs
- Coil for image loading
- Material Motion transitions

---

## Implementation Priority

### Phase 1: Core Screens (1-2 days)
- Login screen
- Bottom navigation shell
- Feed tab (with mock data)
- Shop tab (grid layout)
- Profile tab (basic)

### Phase 2: Instrumentation (1 day)
- Hook up all telemetry
- Add debug toolbar
- Test breadcrumb capture
- Verify vitals collection

### Phase 3: Polish (1 day)
- WebView integration
- Image loading
- Smooth animations
- Error states
- Loading states

### Phase 4: Mock API (1 day)
- Create JSON fixtures
- MockWebServer setup
- Random error injection
- Network delay simulation

---

## File Structure
```
demo-app/
├── ui/
│   ├── auth/
│   │   ├── LoginActivity.kt
│   │   └── SignupActivity.kt
│   ├── main/
│   │   ├── MainActivity.kt
│   │   ├── FeedFragment.kt
│   │   ├── ShopFragment.kt
│   │   ├── PostFragment.kt
│   │   ├── LikesFragment.kt
│   │   └── ProfileFragment.kt
│   ├── product/
│   │   └── ProductDetailActivity.kt
│   ├── cart/
│   │   └── CartActivity.kt
│   └── debug/
│       └── DebugToolbar.kt
├── data/
│   ├── api/
│   │   ├── ApiService.kt
│   │   └── MockApiInterceptor.kt
│   ├── model/
│   │   ├── User.kt
│   │   ├── Post.kt
│   │   └── Product.kt
│   └── repository/
│       ├── FeedRepository.kt
│       └── ProductRepository.kt
└── util/
    └── OTelExtensions.kt
```

---

## Summary

**App Type**: Social shopping app (realistic use case)

**Key Features**:
- 5 main tabs with distinct functionality
- Authentication flow
- E-commerce features (browse, cart, checkout)
- Social features (feed, likes, comments)
- WebView integration for rich content
- Camera/photo upload
- Search and filtering

**Telemetry Coverage**:
- ✅ All vitals (start time, jank, memory, thermal)
- ✅ All network requests (with trace propagation)
- ✅ All navigation (breadcrumbs)
- ✅ All errors (crashes, ANRs, network failures)
- ✅ User journey reconstruction
- ✅ Session management
- ✅ WebView instrumentation

**Debug Toolbar**: Small, collapsible, always accessible for triggering error scenarios.

This design provides a realistic app that naturally exercises all instrumentation features without feeling artificial.
