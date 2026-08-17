import Foundation

struct GiphyResponse: Decodable {
    struct GIFObject: Decodable {
        struct Rendition: Decodable {
            let url: String
            let width: String?
            let height: String?
        }

        let id: String
        let title: String
        let images: Images

        struct Images: Decodable {
            let fixedWidthStill: Rendition?
            let fixedWidth: Rendition?
            let original: Rendition

            enum CodingKeys: String, CodingKey {
                case fixedWidthStill = "fixed_width_still"
                case fixedWidth = "fixed_width"
                case original
            }
        }
    }

    let data: [GIFObject]
}

enum GiphyServiceError: LocalizedError {
    case missingAPIKey
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .missingAPIKey:
            return "GIPHY is not configured yet. Add a GIPHYAPIKey value to the app plist."
        case .invalidResponse:
            return "Unable to load GIFs right now."
        }
    }
}

final class GiphyService {
    private let session: URLSession
    private let apiKey: String?
    private let decoder = JSONDecoder()

    init(session: URLSession = .shared, bundle: Bundle = .main) {
        self.session = session
        self.apiKey = (bundle.object(forInfoDictionaryKey: "GIPHYAPIKey") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
    }

    func trending(limit: Int = 36) async throws -> [GiphyAsset] {
        let endpoint = try endpoint(
            path: "trending",
            queryItems: [URLQueryItem(name: "limit", value: "\(limit)")]
        )
        return try await fetchAssets(from: endpoint)
    }

    func search(query: String, limit: Int = 36) async throws -> [GiphyAsset] {
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedQuery.isEmpty else { return try await trending(limit: limit) }
        let endpoint = try endpoint(
            path: "search",
            queryItems: [
                URLQueryItem(name: "q", value: trimmedQuery),
                URLQueryItem(name: "limit", value: "\(limit)")
            ]
        )
        return try await fetchAssets(from: endpoint)
    }

    func fetchData(from url: URL) async throws -> Data {
        let (data, response) = try await session.data(from: url)
        guard let httpResponse = response as? HTTPURLResponse, (200..<300).contains(httpResponse.statusCode) else {
            throw GiphyServiceError.invalidResponse
        }
        return data
    }

    private func endpoint(path: String, queryItems: [URLQueryItem]) throws -> URL {
        guard let apiKey else {
            throw GiphyServiceError.missingAPIKey
        }
        var components = URLComponents(string: "https://api.giphy.com/v1/gifs/\(path)")
        components?.queryItems = [URLQueryItem(name: "api_key", value: apiKey)] + queryItems
        guard let url = components?.url else {
            throw GiphyServiceError.invalidResponse
        }
        return url
    }

    private func fetchAssets(from url: URL) async throws -> [GiphyAsset] {
        let (data, response) = try await session.data(from: url)
        guard let httpResponse = response as? HTTPURLResponse, (200..<300).contains(httpResponse.statusCode) else {
            throw GiphyServiceError.invalidResponse
        }
        let decoded = try decoder.decode(GiphyResponse.self, from: data)
        return decoded.data.compactMap { item in
            let previewURLString = item.images.fixedWidthStill?.url ?? item.images.fixedWidth?.url
            guard
                let previewURLString,
                let previewURL = URL(string: previewURLString),
                let originalURL = URL(string: item.images.original.url)
            else {
                return nil
            }

            return GiphyAsset(
                id: item.id,
                title: item.title,
                previewURL: previewURL,
                originalURL: originalURL,
                mimeType: "image/gif",
                width: Int(item.images.original.width ?? ""),
                height: Int(item.images.original.height ?? "")
            )
        }
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
