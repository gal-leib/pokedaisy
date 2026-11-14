import SwiftUI
import UIKit
import PokeDaisyUI

/// Where the game goes. The game side (the iOS core, its Metal view, sound and input) replaces this with its own
/// view controller; until then a dark placeholder stands in.
enum GameScreen {
    static var makeViewController: () -> UIViewController = { PlaceholderGameViewController() }
}

/// Sideways: the game left at the GBA's 3:2, the companion right in the rest. Upright: the game across the top,
/// the companion under it - the Android app's layouts on one screen.
struct RootView: View {
    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width > geo.size.height
            if wide {
                HStack(spacing: 0) {
                    ViewControllerView(make: GameScreen.makeViewController)
                        .frame(width: min(geo.size.width * 0.6, geo.size.height * 1.5))
                    ViewControllerView(make: { PokeDaisyHost.shared.companionViewController() })
                }
            } else {
                VStack(spacing: 0) {
                    ViewControllerView(make: GameScreen.makeViewController)
                        .frame(height: geo.size.width / 1.5)
                    ViewControllerView(make: { PokeDaisyHost.shared.companionViewController() })
                }
            }
        }
        .background(Color.black)
    }
}

/// A UIKit view controller inside SwiftUI, made once.
struct ViewControllerView: UIViewControllerRepresentable {
    let make: () -> UIViewController
    func makeUIViewController(context: Context) -> UIViewController { make() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

final class PlaceholderGameViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
    }
}
