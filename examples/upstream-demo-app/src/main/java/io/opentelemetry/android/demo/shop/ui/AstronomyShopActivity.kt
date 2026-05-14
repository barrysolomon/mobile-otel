/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.demo.shop.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import io.opentelemetry.android.demo.shop.ShopTelemetry
import io.opentelemetry.android.demo.shop.clients.ProductCatalogClient
import io.opentelemetry.android.demo.shop.ui.cart.CartScreen
import io.opentelemetry.android.demo.shop.ui.cart.CartViewModel
import io.opentelemetry.android.demo.shop.ui.cart.CheckoutConfirmationScreen
import io.opentelemetry.android.demo.shop.ui.cart.CheckoutInfoViewModel
import io.opentelemetry.android.demo.shop.ui.cart.InfoScreen
import io.opentelemetry.android.demo.shop.ui.errors.ErrorTriggersScreen
import io.opentelemetry.android.demo.shop.ui.products.ProductDetails
import io.opentelemetry.android.demo.shop.ui.products.ProductList
import io.opentelemetry.android.demo.theme.DemoAppTheme
import kotlinx.coroutines.CoroutineScope

class AstronomyShopActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AstronomyShopScreen()
        }
    }
}

@Composable
fun AstronomyShopScreen() {
    val productsClient = ProductCatalogClient(LocalContext.current)
    val products by remember { mutableStateOf(productsClient.get()) }
    val context = LocalContext.current
    val astronomyShopNavController = rememberAstronomyShopNavController()
    val cartViewModel: CartViewModel = viewModel()
    val checkoutInfoViewModel: CheckoutInfoViewModel = viewModel()
    val checkoutScope = rememberCoroutineScope()
    // Emit the canonical `app.home_appeared` log the first time the shop
    // composable renders. Mirrors iOS `RootState.onAppear`.
    LaunchedEffect(Unit) { ShopTelemetry.emitAppHomeAppeared() }

    DemoAppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                bottomBar = {
                    BottomNavigationBar(
                        items = listOf(BottomNavItem.Exit, BottomNavItem.List, BottomNavItem.Cart, BottomNavItem.Errors),
                        currentRoute = astronomyShopNavController.currentRoute,
                        onItemClicked = { route ->
                            astronomyShopNavController.navController.navigate(route) {
                                popUpTo(astronomyShopNavController.navController.graph.startDestinationId)
                                launchSingleTop = true
                            }
                        },
                        onExitClicked = {
                            (context as? Activity)?.finish()
                        }
                    )
                }
            ) { innerPadding ->
                NavHost(
                    navController = astronomyShopNavController.navController,
                    startDestination = MainDestinations.HOME_ROUTE,
                    Modifier.padding(innerPadding)
                ) {
                    composable(BottomNavItem.List.route) {
                        ProductList(products = products) { productId ->
                            astronomyShopNavController.navigateToProductDetail(productId)
                        }
                    }
                    composable(BottomNavItem.Cart.route) {
                        CartScreen(cartViewModel = cartViewModel, onCheckoutClick = {astronomyShopNavController.navigateToCheckoutInfo()},  onProductClick = { productId ->
                            astronomyShopNavController.navigateToProductDetail(productId)
                        })
                    }
                    composable(BottomNavItem.Errors.route) {
                        ErrorTriggersScreen()
                    }
                    composable("${MainDestinations.PRODUCT_DETAIL_ROUTE}/{${MainDestinations.PRODUCT_ID_KEY}}") { backStackEntry ->
                        val productId = backStackEntry.arguments?.getString(MainDestinations.PRODUCT_ID_KEY)
                        val product = products.find { it.id == productId }
                        product?.let { ProductDetails(
                            product = it,
                            cartViewModel,
                            upPress = {astronomyShopNavController.upPress()},
                            onProductClick = { productId ->
                                astronomyShopNavController.navigateToProductDetail(productId)
                            }
                        )
                        }
                    }
                    composable(MainDestinations.CHECKOUT_INFO_ROUTE) {
                        InfoScreen(
                            onPlaceOrderClick = { instrumentedPlaceOrder(
                                astronomyShopNavController = astronomyShopNavController,
                                cartViewModel = cartViewModel,
                                scope = checkoutScope
                            ) },
                            upPress = { astronomyShopNavController.upPress() },
                            checkoutInfoViewModel = checkoutInfoViewModel
                        )
                    }
                    composable(MainDestinations.CHECKOUT_CONFIRMATION_ROUTE){
                        CheckoutConfirmationScreen(
                            cartViewModel = cartViewModel,
                            checkoutInfoViewModel = checkoutInfoViewModel
                        )
                    }
                }
            }
        }
    }
}

private fun instrumentedPlaceOrder(
    astronomyShopNavController: InstrumentedAstronomyShopNavController,
    cartViewModel: CartViewModel,
    scope: CoroutineScope
) {
    // Emit the canonical 14-span checkout trace + histogram sample per the
    // cross-platform contract in docs/design/shop-telemetry-contract.md.
    // Replaces the legacy `order.placed` single-event emission.
    val lines = cartViewModel.cartItems.value.map { item ->
        ShopTelemetry.CheckoutLine(
            productId = item.product.id,
            productName = item.product.name,
            quantity = item.quantity,
            lineTotal = item.totalPrice()
        )
    }
    val totalUsd = cartViewModel.getTotalPrice()
    val itemCount = lines.sumOf { it.quantity }
    ShopTelemetry.emitCheckout(
        lines = lines,
        totalUsd = totalUsd,
        itemCount = itemCount,
        scope = scope
    )
    astronomyShopNavController.navigateToCheckoutConfirmation()
}

