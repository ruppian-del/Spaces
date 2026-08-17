import Foundation

enum InviteLink {
    private static let scheme = "spaces"
    private static let host = "join"

    static func url(for code: String) -> URL? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = host
        components.queryItems = [
            URLQueryItem(name: "code", value: code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased())
        ]
        return components.url
    }

    static func code(from url: URL) -> String? {
        guard url.scheme?.lowercased() == scheme, url.host?.lowercased() == host else {
            return nil
        }

        return URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?
            .first(where: { $0.name == "code" })?
            .value?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
    }

    static func organizationURL(for code: String) -> URL? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = "organization-invite"
        components.queryItems = [URLQueryItem(name: "code", value: code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased())]
        return components.url
    }

    static func organizationCode(from url: URL) -> String? {
        guard url.scheme?.lowercased() == scheme, url.host?.lowercased() == "organization-invite" else { return nil }
        return URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "code" })?.value?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }
}
