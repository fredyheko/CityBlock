import SwiftUI

@main
struct ProcessMakerApp: App {
    @StateObject private var store = Store()

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store)
        }
    }
}
