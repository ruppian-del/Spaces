import SwiftUI

struct RootTabView: View {
    var body: some View {
        TabView {
            HomeView()
                .tabItem {
                    Label("Home", systemImage: "house.fill")
                }

            PingsView()
                .tabItem {
                    Label("Pings", systemImage: "message.fill")
                }

            ActivityView()
                .tabItem {
                    Label("Activity", systemImage: "waveform.path.ecg")
                }

            ProfileView()
                .tabItem {
                    Label("You", systemImage: "person.crop.circle.fill")
                }
        }
        .tint(.indigo)
    }
}
