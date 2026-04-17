package io.opentelemetry.android.demo.shop.ui.cart

import androidx.lifecycle.ViewModel
import io.opentelemetry.android.demo.shop.ShopTelemetry
import io.opentelemetry.android.demo.shop.model.Product
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CartItem(
    val product: Product,
    var quantity: Int
) {
    fun totalPrice() = product.priceValue() * quantity
}

class CartViewModel : ViewModel() {
    private val _cartItems = MutableStateFlow<List<CartItem>>(emptyList())
    val cartItems: StateFlow<List<CartItem>> = _cartItems

    fun addProduct(product: Product, quantity: Int) {
        var lineQuantity = quantity
        _cartItems.value = _cartItems.value.toMutableList().apply {
            val index = indexOfFirst { it.product.id == product.id }
            if (index >= 0) {
                val updated = this[index].copy(quantity = this[index].quantity + quantity)
                this[index] = updated
                lineQuantity = updated.quantity
            } else {
                add(CartItem(product, quantity))
            }
        }
        // Canonical cross-platform emission: counter + INFO log + optional
        // WARN on large line quantity. See docs/design/shop-telemetry-contract.md.
        ShopTelemetry.emitCartAdd(
            product = product,
            quantityAdded = quantity,
            lineQuantity = lineQuantity,
            itemCount = itemCount()
        )
    }

    fun removeProduct(product: Product) {
        _cartItems.value = _cartItems.value.filterNot { it.product.id == product.id }
        ShopTelemetry.emitCartRemove(product = product, itemCount = itemCount())
    }

    fun getTotalPrice(): Double {
        return _cartItems.value.sumOf { it.totalPrice() }
    }

    fun itemCount(): Int = _cartItems.value.sumOf { it.quantity }

    fun clearCart() {
        _cartItems.value = emptyList()
    }
}
