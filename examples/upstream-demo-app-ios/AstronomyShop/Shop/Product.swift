import Foundation

/// Product model — matches `Product.kt` in the Android upstream demo so both
/// apps consume the same `products.json` catalog and produce comparable
/// telemetry. Kept intentionally unchanged in shape.
struct Product: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let description: String
    let picture: String
    let priceUsd: PriceUsd
    let categories: [String]

    var priceValue: Double {
        Double(priceUsd.units) + Double(priceUsd.nanos) / 1_000_000_000.0
    }
}

struct PriceUsd: Codable, Hashable {
    let currencyCode: String
    let units: Int64
    let nanos: Int64
}

struct ProductsWrapper: Codable {
    let products: [Product]
}
