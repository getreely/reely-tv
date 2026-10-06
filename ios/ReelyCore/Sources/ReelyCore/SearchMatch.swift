import Foundation

/*
 * Which search results are about what was typed, best first; as the Fire TV's SearchMatch.
 * A match has the words in its name; case, accents and punctuation don't count.
 */

public func searchWords(_ text: String) -> [String] {
    let folded = text.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).lowercased()
    return folded.components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }
}

public func relevant(_ query: String, _ items: [PlexItem]) -> [PlexItem] {
    let wanted = searchWords(query)
    guard !wanted.isEmpty else { return [] }
    let joined = wanted.joined()
    let ranked = items.compactMap { item -> (PlexItem, Int)? in
        let ranks = [item.title, item.grandparentTitle, item.titleSort].compactMap { $0 }
            .compactMap { rankName(searchWords($0), wanted, joined) }
        return ranks.min().map { (item, $0) }
    }
    return stableSorted(ranked) { $0.1 < $1.1 }.map(\.0)
}

/// What's about the words, best first; and the rest Plex found.
public func splitResults(_ query: String, _ items: [PlexItem]) -> (matches: [PlexItem], others: [PlexItem]) {
    let matches = relevant(query, items)
    let keys = Set(matches.map(\.id))
    return (matches, items.filter { !keys.contains($0.id) })
}

/// Every word typed is in [name], in any order: "hanks" or "tom hanks" names Tom Hanks.
public func namesAll(_ name: String, _ query: String) -> Bool {
    let typed = query.lowercased().split(whereSeparator: \.isWhitespace)
    let whole = name.lowercased()
    return !typed.isEmpty && typed.allSatisfy { whole.contains($0) }
}

private func rankName(_ name: [String], _ wanted: [String], _ joined: String) -> Int? {
    guard !name.isEmpty else { return nil }
    let whole = name.joined()
    if whole == joined { return 0 }
    if whole.hasPrefix(joined) { return 1 }
    if wanted.allSatisfy({ w in name.contains { $0.hasPrefix(w) } }) { return 2 }
    var offset = 0
    for w in name {
        if whole.dropFirst(offset).hasPrefix(joined) { return 3 }
        offset += w.count
    }
    return nil
}

/// Recent searches with [query] first, once.
public func rememberedSearches(_ recent: [String], _ query: String, limit: Int = 8) -> [String] {
    let text = query.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    guard !text.isEmpty else { return recent }
    return Array(([text] + recent.filter { $0.lowercased() != text.lowercased() }).prefix(limit))
}
