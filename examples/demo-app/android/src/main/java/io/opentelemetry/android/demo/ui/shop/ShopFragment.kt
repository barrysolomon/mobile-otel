package io.opentelemetry.android.demo.ui.shop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.demo.data.model.Product
import io.opentelemetry.android.demo.data.model.ProductCategory
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbManager
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb

/**
 * Shop fragment showing products in a grid.
 *
 * Demonstrates:
 * - Grid layout (RecyclerView)
 * - Search functionality
 * - Category filtering
 * - Product click tracking
 * - Add to cart actions
 */
class ShopFragment : Fragment() {

    private lateinit var searchView: SearchView
    private lateinit var categoryChipGroup: ChipGroup
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ProductAdapter

    private var allProducts = listOf<Product>()
    private var selectedCategory: ProductCategory = ProductCategory.ALL

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_shop, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        searchView = view.findViewById(R.id.searchView)
        categoryChipGroup = view.findViewById(R.id.categoryChipGroup)
        recyclerView = view.findViewById(R.id.productsRecyclerView)

        setupRecyclerView()
        setupSearch()
        setupCategoryFilters()
        loadProducts()

        trackBreadcrumb("view_shop")
    }

    private fun setupRecyclerView() {
        adapter = ProductAdapter { product ->
            onProductClick(product)
        }

        recyclerView.apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = this@ShopFragment.adapter
            setHasFixedSize(true)
        }
    }

    private fun setupSearch() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let {
                    trackBreadcrumb("search", mapOf("query" to it))
                    filterProducts(it)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                newText?.let { filterProducts(it) }
                return true
            }
        })
    }

    private fun setupCategoryFilters() {
        ProductCategory.values().forEach { category ->
            val chip = Chip(requireContext()).apply {
                text = category.displayName
                isCheckable = true
                isChecked = category == ProductCategory.ALL
                setOnClickListener {
                    selectedCategory = category
                    trackBreadcrumb("filter_category", mapOf("category" to category.name))
                    filterProducts(searchView.query.toString())
                }
            }
            categoryChipGroup.addView(chip)
        }
    }

    private fun loadProducts() {
        // TODO: Load from API
        allProducts = generateMockProducts()
        filterProducts("")
    }

    private fun filterProducts(query: String) {
        val filtered = allProducts.filter { product ->
            val matchesQuery = query.isEmpty() ||
                    product.name.contains(query, ignoreCase = true) ||
                    product.description.contains(query, ignoreCase = true)

            val matchesCategory = selectedCategory == ProductCategory.ALL ||
                    product.category == selectedCategory.name

            matchesQuery && matchesCategory
        }

        adapter.submitList(filtered)
    }

    private fun onProductClick(product: Product) {
        trackBreadcrumb("view_product", mapOf("product_id" to product.id, "product_name" to product.name))
        // TODO: Navigate to product detail screen
    }

    private fun trackBreadcrumb(action: String, attributes: Map<String, String> = emptyMap()) {
        if (BreadcrumbManager.isInitialized()) {
            BreadcrumbManager.add(
                JourneyBreadcrumb.custom(
                    screen = "ShopFragment",
                    action = action,
                    attributes = attributes
                )
            )
        }
    }

    private fun generateMockProducts(): List<Product> {
        return listOf(
            Product(
                id = "p1",
                name = "Wireless Headphones",
                description = "Premium noise-cancelling headphones",
                price = 299.99,
                imageUrl = null,
                category = ProductCategory.ELECTRONICS.name,
                rating = 4.5f,
                reviewCount = 128
            ),
            Product(
                id = "p2",
                name = "Running Shoes",
                description = "Comfortable athletic footwear",
                price = 89.99,
                imageUrl = null,
                category = ProductCategory.SPORTS.name,
                rating = 4.2f,
                reviewCount = 89
            ),
            Product(
                id = "p3",
                name = "Smart Watch",
                description = "Fitness tracking and notifications",
                price = 399.99,
                imageUrl = null,
                category = ProductCategory.ELECTRONICS.name,
                rating = 4.7f,
                reviewCount = 256
            ),
            Product(
                id = "p4",
                name = "Coffee Maker",
                description = "Programmable 12-cup coffee maker",
                price = 79.99,
                imageUrl = null,
                category = ProductCategory.HOME.name,
                rating = 4.3f,
                reviewCount = 64
            ),
            Product(
                id = "p5",
                name = "Yoga Mat",
                description = "Non-slip exercise mat",
                price = 29.99,
                imageUrl = null,
                category = ProductCategory.SPORTS.name,
                rating = 4.6f,
                reviewCount = 145
            ),
            Product(
                id = "p6",
                name = "Denim Jacket",
                description = "Classic style denim jacket",
                price = 69.99,
                imageUrl = null,
                category = ProductCategory.FASHION.name,
                rating = 4.4f,
                reviewCount = 92
            )
        )
    }
}
