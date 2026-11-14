import SwiftUI
import UniformTypeIdentifiers
import UIKit
import PokeDaisyUI

/// Sideways: the game left at the GBA's 3:2, the companion right in the rest. Upright: the game across the top,
/// the companion under it - the Android app's layouts on one screen. With no game running the game's place lists
/// the games added from Files.
struct RootView: View {
    @State private var playing: String?
    @State private var failed: String?
    /** Bumped by RESTART GAME, so the game's view (and the game) is made again. */
    @State private var restarts = 0

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width > geo.size.height
            let layout = wide ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
            layout {
                gameArea
                    .frame(width: wide ? min(geo.size.width * 0.6, geo.size.height * 1.5) : nil,
                           height: wide ? nil : geo.size.width / 1.5)
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

    @ViewBuilder private var gameArea: some View {
        if let rom = playing {
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
                Button("GAMES", action: { PokeDaisyHost.shared.requestCloseGame() })
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .padding(8)
                    .background(.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 6))
                    .foregroundStyle(.white)
                    .padding(8)
            }
        } else {
            GamesList(failed: failed) { rom in
                failed = nil
                playing = rom
            }
        }
    }

    private func stop() {
        PokeDaisyHost.shared.closeGame()
        playing = nil
    }
}

/// The games added from Files, ADD GAMES to pick more, and each game's menu (LOAD SAVE: a save from Files in place of
/// its own, the one before kept as a backup).
struct GamesList: View {
    let failed: String?
    let play: (String) -> Void
    @State private var games: [String] = PokeDaisyHost.shared.games()
    /** The game whose save is being picked. */
    @State private var savingFor: String?
    @State private var importing = false
    @State private var note: String?

    var body: some View {
        NavigationStack {
            List {
                if let failed {
                    Text("\(name(failed)) didn't open.").foregroundStyle(.red)
                }
                if let note {
                    Text(note).foregroundStyle(.secondary)
                }
                if games.isEmpty {
                    Text("No games yet. Add your own GBA or Game Boy ROMs from Files.").foregroundStyle(.secondary)
                }
                ForEach(games, id: \.self) { rom in
                    HStack {
                        Button(name(rom)) { play(rom) }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Menu {
                            Button("Load a save from Files") {
                                savingFor = rom
                                importing = true
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle").imageScale(.large)
                        }
                    }
                    .buttonStyle(.borderless)
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
            .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { result in
                guard let rom = savingFor else { return }
                savingFor = nil
                guard case .success(let url) = result else { return }
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                if let error = PokeDaisyHost.shared.loadSave(romPath: rom, fromPath: url.path) {
                    note = "\(name(rom)): \(error)."
                } else {
                    note = "Loaded \(url.lastPathComponent) for \(name(rom)). Any save it had is kept in saves/ as a backup."
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
