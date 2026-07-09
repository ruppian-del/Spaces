import SwiftUI

struct ModuleCardView: View {
    let module: SpaceModule
    let tintHex: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Text(module.emoji)
                    .font(.title3)

                Image(systemName: module.icon)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color(hex: tintHex))
            }

            Text(module.title)
                .font(.headline)

            Text(module.description)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .padding(16)
        .frame(maxWidth: .infinity, minHeight: 132, alignment: .topLeading)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(Color(.secondarySystemBackground))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(Color.primary.opacity(0.05), lineWidth: 1)
        )
    }
}
