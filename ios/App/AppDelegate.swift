import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Settings, states, cheats and the saves' backups: a folder of the app's Documents, so the Files app shows it.
        pd_ios_init(Library.dataDir.path, Int32(AudioOutput.rate))
        return true
    }

    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "Default", sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        return config
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = UINavigationController(rootViewController: LibraryViewController())
        window.makeKeyAndVisible()
        self.window = window
        importAll(connectionOptions.urlContexts)
    }

    // A game file opened with the app (Files' "Open in", AirDrop): copied into the library.
    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        importAll(URLContexts)
    }

    private func importAll(_ contexts: Set<UIOpenURLContext>) {
        guard !contexts.isEmpty else { return }
        contexts.forEach { Library.importFile($0.url) }
        NotificationCenter.default.post(name: Library.changed, object: nil)
    }
}
