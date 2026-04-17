import Foundation
import OpenTelemetryApi

/// Loads the product catalog from the bundled `products.json`.
///
/// Emits a 4-span nested trace so the whole load path is observable:
///   shop.load_catalog
///     ├── catalog.read_bundle
///     ├── catalog.decode
///     └── catalog.enrich
final class ProductCatalogClient {
    let tracer: Tracer?

    init(tracer: Tracer?) {
        self.tracer = tracer
    }

    func loadProducts() -> [Product] {
        guard let tracer = tracer else {
            return loadProductsUntraced()
        }

        let parent = tracer.spanBuilder(spanName: "shop.load_catalog")
            .setSpanKind(spanKind: .internal)
            .startSpan()
        defer { parent.end() }

        let readSpan = tracer.spanBuilder(spanName: "catalog.read_bundle")
            .setParent(parent)
            .startSpan()
        guard let url = Bundle.main.url(forResource: "products", withExtension: "json") else {
            readSpan.status = .error(description: "products.json missing from bundle")
            readSpan.end()
            parent.status = .error(description: "products.json missing from bundle")
            return []
        }
        readSpan.setAttribute(key: "bundle.resource", value: "products.json")
        guard let data = try? Data(contentsOf: url) else {
            readSpan.status = .error(description: "failed to read products.json")
            readSpan.end()
            parent.status = .error(description: "failed to read products.json")
            return []
        }
        readSpan.setAttribute(key: "bundle.bytes", value: data.count)
        readSpan.status = .ok
        readSpan.end()

        let decodeSpan = tracer.spanBuilder(spanName: "catalog.decode")
            .setParent(parent)
            .startSpan()
        let products: [Product]
        do {
            let wrapper = try JSONDecoder().decode(ProductsWrapper.self, from: data)
            products = wrapper.products
            decodeSpan.setAttribute(key: "shop.catalog.count", value: products.count)
            decodeSpan.status = .ok
        } catch {
            decodeSpan.status = .error(description: "decode error: \(error.localizedDescription)")
            decodeSpan.end()
            parent.status = .error(description: "decode error: \(error.localizedDescription)")
            return []
        }
        decodeSpan.end()

        let enrichSpan = tracer.spanBuilder(spanName: "catalog.enrich")
            .setParent(parent)
            .startSpan()
        let minPrice = products.map { $0.priceValue }.min() ?? 0
        let maxPrice = products.map { $0.priceValue }.max() ?? 0
        enrichSpan.setAttribute(key: "shop.catalog.min_price_usd", value: minPrice)
        enrichSpan.setAttribute(key: "shop.catalog.max_price_usd", value: maxPrice)
        enrichSpan.status = .ok
        enrichSpan.end()

        parent.setAttribute(key: "shop.catalog.count", value: products.count)
        parent.status = .ok
        return products
    }

    private func loadProductsUntraced() -> [Product] {
        guard let url = Bundle.main.url(forResource: "products", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let wrapper = try? JSONDecoder().decode(ProductsWrapper.self, from: data)
        else {
            return []
        }
        return wrapper.products
    }
}
