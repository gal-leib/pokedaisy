import SwiftUI
import UIKit
import PokeDaisyUI

/// Sideways: the game left at the GBA's 3:2 with its buttons under it, the companion right in the rest. Upright: the
/// game across the top, its buttons, the companion under them - the Android app's layouts on one screen. With no game
/// running the game's place lists the games added from Files.
struct RootView: View {
    @State private var playing: String?
    @State private var failed: String?
    /** Bumped by RESTART GAME, so the game's view (and the game) is made again. */
    @State private var restarts = 0
    /// The proof of concept's UIKit buttons in place of the app's own, to time the two against each other.
    @AppStorage("native_touch_controls") private var nativeControls = false

    /// How tall the buttons' strip under the game is, at the least.
    private let strip: CGFloat = 220

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width > geo.size.height
            let layout = wide ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
            layout {
                gameColumn
                    .frame(width: wide ? min(geo.size.width * 0.6, (geo.size.height - strip) * 1.5) : nil,
                           height: wide ? nil : (playing == nil ? geo.size.width / 1.5 : geo.size.width / 1.5 + strip))
                ViewControllerView(make: { PokeDaisyHost.shared.companionViewController() })
            }
        }
        .background(Color.black)
        .onAppear {
            // The companion's SETTINGS > CLOSE GAME / RESTART GAME.
            PokeDaisyHost.shared.onCloseRequested = { stop() }
            PokeDaisyHost.shared.onRestartRequested = { restarts += 1 }
        }
    }

    @ViewBuilder private var gameColumn: some View {
        if let rom = playing {
            GeometryReader { col in
                VStack(spacing: 0) {
                    game(rom).frame(height: col.size.width / 1.5)
                    controls(rom)
                }
            }
        } else {
            GamesList(failed: failed) { rom in
                failed = nil
                playing = rom
            }
        }
    }

    private func game(_ rom: String) -> some View {
        ZStack(alignment: .topLeading) {
            ViewControllerView(make: {
                PokeDaisyHost.shared.gameViewController(romPath: rom, onFailed: {
                    DispatchQueue.main.async {
                        failed = rom
                        stop()
                    }
                })
            })
            .id("\(rom)#\(restarts)")
            HStack(spacing: 0) {
                chip("GAMES", action: stop)
                chip(nativeControls ? "TOUCH: NATIVE" : "TOUCH: SHARED") {
                    PokeDaisyHost.shared.setTouchKeys(keys: 0)
                    nativeControls.toggle()
                }
            }
        }
    }

    @ViewBuilder private func controls(_ rom: String) -> some View {
        if nativeControls {
            NativeGamepad()
        } else {
            let gameBoy = ["gb", "gbc"].contains((rom as NSString).pathExtension.lowercased())
            ViewControllerView(make: { PokeDaisyHost.shared.touchControlsViewController(gameBoy: gameBoy) })
                .id(rom)
        }
    }

    private func chip(_ title: String, action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .font(.system(size: 13, weight: .bold, design: .monospaced))
            .padding(8)
            .background(.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 6))
            .foregroundStyle(.white)
            .padding(8)
    }

    private func stop() {
        PokeDaisyHost.shared.closeGame()
        playing = nil
    }
}

/// The games added from Files, and ADD GAMES to pick more.
struct GamesList: View {
    let failed: String?
    let play: (String) -> Void
    @State private var games: [String] = PokeDaisyHost.shared.games()

    var body: some View {
        NavigationStack {
            List {
                if let failed {
                    Text("\(name(failed)) didn't open.").foregroundStyle(.red)
                }
                if games.isEmpty {
                    Text("No games yet. Add your own GBA or Game Boy ROMs from Files.").foregroundStyle(.secondary)
                }
                ForEach(games, id: \.self) { rom in
                    Button(name(rom)) { play(rom) }
                }
            }
            .navigationTitle("Games")
            .toolbar {
                Button("Add games") {
                    guard let top = topViewController() else { return }
                    PokeDaisyHost.shared.addGames(from: top) { _ in
                        games = PokeDaisyHost.shared.games()
                    }
                }
            }
        }
    }

    private func name(_ path: String) -> String {
        ((path as NSString).lastPathComponent as NSString).deletingPathExtension
    }
}

/// The view controller showing on top, to present the Files picker from.
private func topViewController() -> UIViewController? {
    let window = UIApplication.shared.connectedScenes
        .compactMap { ($0 as? UIWindowScene)?.keyWindow }
        .first
    var top = window?.rootViewController
    while let presented = top?.presentedViewController { top = presented }
    return top
}

/// A UIKit view controller inside SwiftUI, made once.
struct ViewControllerView: UIViewControllerRepresentable {
    let make: () -> UIViewController
    func makeUIViewController(context: Context) -> UIViewController { make() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
