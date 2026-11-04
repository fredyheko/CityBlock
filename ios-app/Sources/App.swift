import SwiftUI

@main
struct ProcessMakerApp: App {
    @StateObject private var store = Store()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store)
        }
        .onChange(of: phase) { p in
            if p == .background { BackgroundCheck.schedule() }
        }
        .backgroundTask(.appRefresh(BackgroundCheck.id)) {
            await BackgroundCheck.run()
        }
    }
}
