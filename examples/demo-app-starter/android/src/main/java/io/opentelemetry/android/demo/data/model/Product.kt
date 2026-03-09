package io.opentelemetry.android.demo.data.model

/**
 * Product data model.
 */
data class Product(
    val id: String,
    val name: String,
    val description: String,
    val price: Double,
    val imageUrl: String?,
    val category: String,
    val rating: Float,
    val reviewCount: Int,
    val inStock: Boolean = true
) {
    fun formattedPrice(): String = "$%.2f".format(price)
}

/**
 * Product category enum.
 */
enum class ProductCategory(val displayName: String) {
    ALL("All"),
    ELECTRONICS("Electronics"),
    FASHION("Fashion"),
    HOME("Home & Garden"),
    SPORTS("Sports"),
    BOOKS("Books")
}
