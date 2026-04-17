import SwiftUI
import ScreenInstrumentation

struct ProductListView: View {
    let products: [Product]
    @EnvironmentObject var cart: CartViewModel

    var body: some View {
        NavigationStack {
            List(products) { product in
                NavigationLink(value: product) {
                    HStack(spacing: 12) {
                        productImage(for: product)
                            .frame(width: 60, height: 60)
                            .clipped()
                            .cornerRadius(8)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(product.name)
                                .font(.headline)
                                .lineLimit(2)
                            Text("$\(product.priceValue, specifier: "%.2f")")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
            .navigationTitle("Astronomy Shop")
            .trackScreen("ProductList")
            .navigationDestination(for: Product.self) { product in
                ProductDetailView(product: product)
                    .environmentObject(cart)
            }
            .toolbar {
                NavigationLink {
                    CartView().environmentObject(cart)
                } label: {
                    Image(systemName: "cart")
                        .overlay(alignment: .topTrailing) {
                            if cart.itemCount > 0 {
                                Text("\(cart.itemCount)")
                                    .font(.caption2).bold()
                                    .foregroundColor(.white)
                                    .padding(4)
                                    .background(Color.red)
                                    .clipShape(Circle())
                                    .offset(x: 8, y: -6)
                            }
                        }
                }
            }
        }
    }

    @ViewBuilder
    private func productImage(for product: Product) -> some View {
        let imageName = product.picture.replacingOccurrences(of: ".jpg", with: "")
        if let uiImage = UIImage(named: imageName) ?? UIImage(named: product.picture) {
            Image(uiImage: uiImage).resizable().aspectRatio(contentMode: .fill)
        } else {
            Color.gray.opacity(0.2)
                .overlay(Image(systemName: "photo").foregroundColor(.gray))
        }
    }
}
