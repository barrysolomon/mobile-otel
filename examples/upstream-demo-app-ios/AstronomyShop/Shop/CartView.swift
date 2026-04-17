import SwiftUI
import ScreenInstrumentation

struct CartView: View {
    @EnvironmentObject var cart: CartViewModel
    @State private var checkoutRunning = false
    @State private var lastCheckoutSucceeded = false
    @State private var showConfirmation = false

    var body: some View {
        Group {
            if cart.lines.isEmpty {
                ContentUnavailableViewCompat(
                    title: "Cart is empty",
                    systemImage: "cart",
                    description: "Browse products and add some items"
                )
            } else {
                List {
                    ForEach(cart.lines) { line in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(line.product.name)
                                    .font(.headline)
                                Text("$\(line.product.priceValue, specifier: "%.2f") × \(line.quantity)")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Text("$\(line.lineTotal, specifier: "%.2f")")
                                .font(.subheadline).bold()
                        }
                    }
                    .onDelete { offsets in
                        for i in offsets { cart.remove(cart.lines[i].product) }
                    }

                    Section {
                        HStack {
                            Text("Total")
                                .font(.title3).bold()
                            Spacer()
                            Text("$\(cart.total, specifier: "%.2f")")
                                .font(.title3).bold()
                        }
                    }
                }

                VStack(spacing: 12) {
                    Button {
                        checkoutRunning = true
                        cart.checkout { success in
                            checkoutRunning = false
                            lastCheckoutSucceeded = success
                            showConfirmation = true
                        }
                    } label: {
                        if checkoutRunning {
                            ProgressView().frame(maxWidth: .infinity)
                        } else {
                            Text("Checkout")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(checkoutRunning || cart.lines.isEmpty)

                    Button("Clear cart") { cart.clear() }
                        .foregroundColor(.red)
                        .disabled(cart.lines.isEmpty)
                }
                .padding()
            }
        }
        .navigationTitle("Cart")
        .trackScreen("Cart")
        .alert(
            lastCheckoutSucceeded ? "Order placed!" : "Checkout failed",
            isPresented: $showConfirmation
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(lastCheckoutSucceeded
                 ? "Your order has been submitted. Thanks!"
                 : "Something went wrong — please try again.")
        }
    }
}

/// Fallback for iOS 15–16 which don't have ContentUnavailableView.
private struct ContentUnavailableViewCompat: View {
    let title: String
    let systemImage: String
    let description: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 48))
                .foregroundColor(.secondary)
            Text(title)
                .font(.title3).bold()
            Text(description)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
