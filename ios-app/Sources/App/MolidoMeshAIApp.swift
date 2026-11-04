import SwiftUI

@main
struct MolidoMeshAIApp: App {
    @StateObject private var vpn = VPNController()

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(vpn)
                .preferredColorScheme(.dark)
        }
    }
}
