import SwiftUI

struct SpaceIconView: View {
    let emoji: String
    let tintHex: String
    let font: Font

    var body: some View {
        Text(emoji)
            .font(font)
    }
}
