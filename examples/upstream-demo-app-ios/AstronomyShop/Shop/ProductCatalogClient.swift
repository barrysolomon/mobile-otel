import Foundation
import OpenTelemetryApi

/// Loads the product catalog from the bundled `products.json`. Emits a span
/// so the loading path itself shows up in Dash0 (mirrors Android's
/// `ProductCatalogClient`).
final class ProductCatalogClient {
    let tracer: Tracer?

    init(tracer: Tracer?) {
        self.tracer = tracer
    }

    func loadProducts() -> [Product] {
        let span = tracer?.spanBuilder(spanName: "shop.load_catalog")
            .setSpanKind(spanKind: .internal)
            .startSpan()
        defer { span?.end() }

        guard let url = Bundle.main.url(forResource: "products", withExtension: "json") else {
            span?.status = .error(description: "products.json missing from bundle")
            return []
        }
        guard let data = try? Data(contentsOf: url) else {
            span?.status = .error(description: "failed to read products.json")
            return []
        }
        do {
            let wrapper = try JSONDecoder().decode(ProductsWrapper.self, from: data)
            span?.setAttribute(key: "shop.catalog.count", value: wrapper.products.count)
            span?.status = .ok
            return wrapper.products
        } catch {
            span?.status = .error(description: "decode error: \(error.localizedDescription)")
            return []
        }
    }
}
