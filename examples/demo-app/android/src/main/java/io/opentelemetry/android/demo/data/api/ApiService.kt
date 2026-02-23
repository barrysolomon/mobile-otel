package io.opentelemetry.android.demo.data.api

import io.opentelemetry.android.demo.data.model.Post
import io.opentelemetry.android.demo.data.model.Product
import io.opentelemetry.android.demo.data.model.User
import io.opentelemetry.android.mobile.network.OTelNetworkInterceptor
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * API service for demo app.
 *
 * Uses OkHttp with OpenTelemetry instrumentation to demonstrate network tracing.
 * For demo purposes, this returns mock data instead of making real network calls.
 */
class ApiService private constructor() {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // TODO: Add OTelNetworkInterceptor when making real API calls
            // .addInterceptor(OTelNetworkInterceptor.create(...))
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        @Volatile
        private var instance: ApiService? = null

        fun getInstance(): ApiService {
            return instance ?: synchronized(this) {
                instance ?: ApiService().also { instance = it }
            }
        }
    }

    /**
     * Fetch social feed posts.
     */
    suspend fun fetchFeed(): Result<List<Post>> {
        return try {
            // Simulate network delay
            delay(500)

            // TODO: Replace with actual API call
            // val request = Request.Builder()
            //     .url("https://api.example.com/feed")
            //     .get()
            //     .build()
            // val response = client.newCall(request).execute()

            // Return mock data
            Result.success(generateMockPosts())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch products.
     */
    suspend fun fetchProducts(category: String? = null): Result<List<Product>> {
        return try {
            delay(500)

            // TODO: Replace with actual API call
            Result.success(generateMockProducts(category))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch user profile.
     */
    suspend fun fetchUserProfile(userId: String): Result<User> {
        return try {
            delay(300)

            // TODO: Replace with actual API call
            Result.success(
                User(
                    id = userId,
                    username = "demo_user",
                    email = "demo@example.com",
                    displayName = "Demo User",
                    bio = "Testing OpenTelemetry mobile instrumentation",
                    followersCount = 1234,
                    followingCount = 567
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Simulate API error (for testing error instrumentation).
     */
    suspend fun triggerError(errorType: String): Result<Unit> {
        return try {
            delay(200)

            when (errorType) {
                "500" -> throw Exception("Internal Server Error (500)")
                "timeout" -> {
                    delay(35_000)
                    Result.success(Unit)
                }
                else -> throw Exception("Unknown error type")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun generateMockPosts(): List<Post> {
        val now = System.currentTimeMillis()
        return listOf(
            Post(
                id = "post1",
                userId = "user1",
                username = "alice_tech",
                content = "Just integrated OpenTelemetry into my mobile app! The observability is amazing. 🚀",
                likesCount = 42,
                commentsCount = 8,
                timestamp = now - 7_200_000 // 2 hours ago
            ),
            Post(
                id = "post2",
                userId = "user2",
                username = "bob_dev",
                content = "Anyone else excited about the new Kotlin features? Multiplatform is getting better every day!",
                likesCount = 128,
                commentsCount = 23,
                timestamp = now - 14_400_000 // 4 hours ago
            ),
            Post(
                id = "post3",
                userId = "user3",
                username = "carol_designer",
                content = "Working on a new Material Design 3 component library. Check out these color schemes! 🎨",
                likesCount = 89,
                commentsCount = 15,
                timestamp = now - 21_600_000 // 6 hours ago
            ),
            Post(
                id = "post4",
                userId = "user4",
                username = "david_mobile",
                content = "Pro tip: Always test your app with different network conditions. Slow 3G reveals so many issues!",
                likesCount = 256,
                commentsCount = 34,
                timestamp = now - 28_800_000 // 8 hours ago
            ),
            Post(
                id = "post5",
                userId = "user5",
                username = "eve_product",
                content = "Just shipped a major update! Thanks to the amazing dev team for making it happen. 🎉",
                likesCount = 512,
                commentsCount = 67,
                timestamp = now - 43_200_000 // 12 hours ago
            )
        )
    }

    private fun generateMockProducts(category: String?): List<Product> {
        val allProducts = listOf(
            Product(
                id = "p1",
                name = "Wireless Headphones",
                description = "Premium noise-cancelling headphones with 30-hour battery life",
                price = 299.99,
                imageUrl = null,
                category = "ELECTRONICS",
                rating = 4.5f,
                reviewCount = 128
            ),
            Product(
                id = "p2",
                name = "Running Shoes",
                description = "Lightweight athletic footwear with responsive cushioning",
                price = 89.99,
                imageUrl = null,
                category = "SPORTS",
                rating = 4.2f,
                reviewCount = 89
            ),
            Product(
                id = "p3",
                name = "Smart Watch",
                description = "Fitness tracking, notifications, and heart rate monitoring",
                price = 399.99,
                imageUrl = null,
                category = "ELECTRONICS",
                rating = 4.7f,
                reviewCount = 256
            ),
            Product(
                id = "p4",
                name = "Coffee Maker",
                description = "Programmable 12-cup coffee maker with thermal carafe",
                price = 79.99,
                imageUrl = null,
                category = "HOME",
                rating = 4.3f,
                reviewCount = 64
            ),
            Product(
                id = "p5",
                name = "Yoga Mat",
                description = "Non-slip exercise mat with carrying strap",
                price = 29.99,
                imageUrl = null,
                category = "SPORTS",
                rating = 4.6f,
                reviewCount = 145
            ),
            Product(
                id = "p6",
                name = "Denim Jacket",
                description = "Classic style denim jacket with vintage wash",
                price = 69.99,
                imageUrl = null,
                category = "FASHION",
                rating = 4.4f,
                reviewCount = 92
            ),
            Product(
                id = "p7",
                name = "Desk Lamp",
                description = "LED desk lamp with adjustable brightness and color temperature",
                price = 49.99,
                imageUrl = null,
                category = "HOME",
                rating = 4.5f,
                reviewCount = 78
            ),
            Product(
                id = "p8",
                name = "Backpack",
                description = "Water-resistant laptop backpack with USB charging port",
                price = 59.99,
                imageUrl = null,
                category = "FASHION",
                rating = 4.6f,
                reviewCount = 156
            ),
            Product(
                id = "p9",
                name = "Bluetooth Speaker",
                description = "Portable wireless speaker with 360-degree sound",
                price = 79.99,
                imageUrl = null,
                category = "ELECTRONICS",
                rating = 4.4f,
                reviewCount = 234
            ),
            Product(
                id = "p10",
                name = "Fiction Novel",
                description = "Bestselling science fiction novel",
                price = 14.99,
                imageUrl = null,
                category = "BOOKS",
                rating = 4.8f,
                reviewCount = 1024
            )
        )

        return if (category != null && category != "ALL") {
            allProducts.filter { it.category == category }
        } else {
            allProducts
        }
    }
}
