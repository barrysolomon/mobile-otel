import SwiftUI

struct ProductDetailView: View {
    let product: Product
    @EnvironmentObject var cart: CartViewModel
    @State private var quantity: Int = 1
    @State private var showAdded: Bool = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                productImage
                    .frame(maxWidth: .infinity)
                    .frame(height: 240)
                    .clipped()
                    .cornerRadius(12)

                Text(product.name)
                    .font(.title).bold()

                Text("$\(product.priceValue, specifier: "%.2f")")
                    .font(.title3)
                    .foregroundColor(.green)

                Text(product.description)
                    .font(.body)

                Divider()

                HStack {
                    Text("Quantity")
                        .font(.headline)
                    Spacer()
                    Stepper(value: $quantity, in: 1...10) {
                        Text("\(quantity)")
                            .font(.headline)
                            .frame(minWidth: 30)
                    }
                }

                Button {
                    cart.add(product, quantity: quantity)
                    withAnimation { showAdded = true }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                        withAnimation { showAdded = false }
                    }
                } label: {
                    Text(showAdded ? "Added to cart!" : "Add to cart")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(showAdded ? Color.green : Color.blue)
                        .foregroundColor(.white)
                        .cornerRadius(8)
                }
                .buttonStyle(.plain)
            }
            .padding()
        }
        .navigationTitle("Details")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private var productImage: some View {
        let imageName = product.picture.replacingOccurrences(of: ".jpg", with: "")
        if let uiImage = UIImage(named: imageName) ?? UIImage(named: product.picture) {
            Image(uiImage: uiImage).resizable().aspectRatio(contentMode: .fill)
        } else {
            Color.gray.opacity(0.2)
                .overlay(Image(systemName: "photo").foregroundColor(.gray))
        }
    }
}
