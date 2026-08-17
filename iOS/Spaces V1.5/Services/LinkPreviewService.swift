import Foundation
import UIKit

struct EncryptedTextMessageContent: Codable {
    let version: Int
    let text: String
    let linkPreview: LinkPreviewData?
    let spaceLinks: [SpaceLinkAttachment]?
}

actor LinkPreviewService {
    static let shared = LinkPreviewService()

    private struct CachedPreviewRecord: Codable {
        let preview: LinkPreviewData
        let cachedAt: Date

        func isExpired(ttl: TimeInterval) -> Bool {
            Date().timeIntervalSince(cachedAt) > ttl
        }
    }

    private let defaults: UserDefaults
    private let session: URLSession
    private var memoryCache: [String: CachedPreviewRecord] = [:]
    private let storageKey = "spaces.linkpreviews.cache"
    private let cacheTTL: TimeInterval = 60 * 60 * 24 * 7

    init(defaults: UserDefaults = .standard, session: URLSession = .shared) {
        self.defaults = defaults
        self.session = session
        self.memoryCache = Self.loadCache(from: defaults, key: storageKey)
    }

    nonisolated static func firstURL(in text: String) -> URL? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        if let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) {
            let nsRange = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)
            if let match = detector.matches(in: trimmed, options: [], range: nsRange).first,
               let range = Range(match.range, in: trimmed) {
                let raw = String(trimmed[range])
                return normalizedURL(from: raw)
            }
        }

        for token in trimmed.split(whereSeparator: \.isWhitespace) {
            if let url = normalizedURL(from: String(token)) {
                return url
            }
        }
        return nil
    }

    func cachedPreview(for url: URL) -> LinkPreviewData? {
        let key = Self.cacheKey(for: url)
        guard let record = memoryCache[key], !record.isExpired(ttl: cacheTTL) else {
            memoryCache.removeValue(forKey: key)
            persistCache()
            return nil
        }
        return record.preview
    }

    func preview(for url: URL) async -> LinkPreviewData? {
        let key = Self.cacheKey(for: url)
        if let cached = cachedPreview(for: url) {
            return cached
        }

        do {
            var request = URLRequest(url: url)
            request.timeoutInterval = 15
            request.setValue("Spaces/1.1", forHTTPHeaderField: "User-Agent")
            let (data, response) = try await session.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse,
                  (200..<400).contains(httpResponse.statusCode),
                  let html = String(data: data.prefix(256_000), encoding: .utf8) else {
                return nil
            }

            let metadata = Self.parseMetadata(from: html, sourceURL: url)
            let trimmedTitle = metadata.title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !trimmedTitle.isEmpty else {
                return nil
            }

            let imagePayload = try await loadPreviewImage(
                from: metadata.imageURL,
                sourceURL: url
            )

            let preview = LinkPreviewData(
                originalURL: url.absoluteString,
                canonicalURL: metadata.canonicalURL?.absoluteString,
                domain: Self.domain(from: metadata.canonicalURL ?? url),
                title: Self.sanitizedText(trimmedTitle, limit: 120) ?? trimmedTitle,
                summary: Self.sanitizedText(metadata.description, limit: 220),
                siteName: Self.sanitizedText(metadata.siteName, limit: 80),
                imageDataBase64: imagePayload?.data.base64EncodedString(),
                imageMimeType: imagePayload?.mimeType
            )

            memoryCache[key] = CachedPreviewRecord(preview: preview, cachedAt: Date())
            persistCache()
            return preview
        } catch is CancellationError {
            return nil
        } catch {
            return nil
        }
    }

    private func loadPreviewImage(from candidateURL: URL?, sourceURL: URL) async throws -> (data: Data, mimeType: String)? {
        guard let candidateURL else { return nil }
        let resolvedURL = candidateURL.absoluteURL
        var request = URLRequest(url: resolvedURL)
        request.timeoutInterval = 15
        request.setValue("Spaces/1.1", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse,
              (200..<400).contains(httpResponse.statusCode),
              data.count > 0 else {
            return nil
        }

        guard let image = UIImage(data: data) else {
            return nil
        }

        let resized = Self.resizedImage(image, maxDimension: 720)
        if let jpeg = resized.jpegData(compressionQuality: 0.78) {
            return (jpeg, "image/jpeg")
        }
        return nil
    }

    private static func loadCache(from defaults: UserDefaults, key: String) -> [String: CachedPreviewRecord] {
        guard let data = defaults.data(forKey: key),
              let decoded = try? JSONDecoder().decode([String: CachedPreviewRecord].self, from: data) else {
            return [:]
        }
        return decoded
    }

    private func persistCache() {
        let filtered = memoryCache.filter { !$0.value.isExpired(ttl: cacheTTL) }
        memoryCache = filtered
        guard let data = try? JSONEncoder().encode(filtered) else { return }
        defaults.set(data, forKey: storageKey)
    }

    private static func normalizedURL(from raw: String) -> URL? {
        let trimmed = raw
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: ".,;:!?)]}\"'"))
        guard !trimmed.isEmpty else { return nil }
        if let direct = URL(string: trimmed), let scheme = direct.scheme?.lowercased(), ["http", "https"].contains(scheme) {
            return direct
        }
        if trimmed.lowercased().hasPrefix("www.") {
            return URL(string: "https://\(trimmed)")
        }
        return nil
    }

    private static func cacheKey(for url: URL) -> String {
        let normalized = (url.scheme?.lowercased() == nil ? "https://" : "") + url.absoluteString.lowercased()
        return normalized
    }

    private static func domain(from url: URL) -> String {
        url.host?.replacingOccurrences(of: #"^www\."#, with: "", options: .regularExpression) ?? url.absoluteString
    }

    private static func resizedImage(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let largest = max(image.size.width, image.size.height)
        guard largest > maxDimension else { return image }
        let scale = maxDimension / largest
        let targetSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: targetSize)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
    }

    private static func sanitizedText(_ text: String?, limit: Int) -> String? {
        guard let text else { return nil }
        let noTags = text.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        let decoded = decodeHTMLEntities(in: noTags)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !decoded.isEmpty else { return nil }
        return String(decoded.prefix(limit))
    }

    private static func decodeHTMLEntities(in text: String) -> String {
        text
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&nbsp;", with: " ")
    }

    private struct ParsedMetadata {
        let title: String?
        let description: String?
        let siteName: String?
        let canonicalURL: URL?
        let imageURL: URL?
    }

    private static func parseMetadata(from html: String, sourceURL: URL) -> ParsedMetadata {
        let ogTitle = metaContent(in: html, property: "og:title")
        let ogDescription = metaContent(in: html, property: "og:description")
        let ogSiteName = metaContent(in: html, property: "og:site_name")
        let ogImage = metaContent(in: html, property: "og:image")
        let title = ogTitle ?? titleTag(in: html)
        let description = ogDescription ?? metaContent(in: html, name: "description")
        let canonical = canonicalURL(in: html, sourceURL: sourceURL)
        let imageURL = resolvedURL(from: ogImage, sourceURL: sourceURL)

        return ParsedMetadata(
            title: title,
            description: description,
            siteName: ogSiteName,
            canonicalURL: canonical,
            imageURL: imageURL
        )
    }

    private static func metaContent(in html: String, property: String? = nil, name: String? = nil) -> String? {
        let attributeName = property != nil ? "property" : "name"
        let attributeValue = NSRegularExpression.escapedPattern(for: property ?? name ?? "")
        let patterns = [
            #"<meta[^>]*\#(attributeName)\s*=\s*["']\#(attributeValue)["'][^>]*content\s*=\s*["']([^"']+)["'][^>]*>"#,
            #"<meta[^>]*content\s*=\s*["']([^"']+)["'][^>]*\#(attributeName)\s*=\s*["']\#(attributeValue)["'][^>]*>"#
        ]

        for pattern in patterns {
            if let match = firstCapture(in: html, pattern: pattern) {
                return match
            }
        }
        return nil
    }

    private static func titleTag(in html: String) -> String? {
        firstCapture(in: html, pattern: #"<title[^>]*>(.*?)</title>"#, options: [.caseInsensitive, .dotMatchesLineSeparators])
    }

    private static func canonicalURL(in html: String, sourceURL: URL) -> URL? {
        let patterns = [
            #"<link[^>]*rel\s*=\s*["']canonical["'][^>]*href\s*=\s*["']([^"']+)["'][^>]*>"#,
            #"<link[^>]*href\s*=\s*["']([^"']+)["'][^>]*rel\s*=\s*["']canonical["'][^>]*>"#
        ]
        for pattern in patterns {
            if let raw = firstCapture(in: html, pattern: pattern),
               let resolved = resolvedURL(from: raw, sourceURL: sourceURL) {
                return resolved
            }
        }
        return nil
    }

    private static func resolvedURL(from raw: String?, sourceURL: URL) -> URL? {
        guard let raw = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return nil }
        if let absolute = URL(string: raw), absolute.scheme != nil {
            return absolute
        }
        return URL(string: raw, relativeTo: sourceURL)?.absoluteURL
    }

    private static func firstCapture(
        in text: String,
        pattern: String,
        options: NSRegularExpression.Options = [.caseInsensitive]
    ) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else { return nil }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, options: [], range: range),
              match.numberOfRanges > 1,
              let captureRange = Range(match.range(at: 1), in: text) else {
            return nil
        }
        return String(text[captureRange])
    }
}
