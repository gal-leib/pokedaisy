import SwiftUI
import UniformTypeIdentifiers
import UIKit
import PokeDaisyUI

/// The games added from Files until one is picked, then the shared game screen: the game, its touch controls and the
/// companion, laid out by the shared UI.
struct RootView: View {
    @State private var playing: String?
    @State private var failed: String?
    @State private var restarts = 0

    var body: some View {
        Group {
            if let rom = playing {
                ViewControllerView(make: {
                    PokeDaisyHost.shared.gameScreenViewController(romPath: rom, onFailed: {
                        DispatchQueue.main.async { failed = rom; stop() }
                    })
                }).id("\(rom)#\(restarts)")
            } else {
                GamesList(failed: failed) { rom in
                    failed = nil
                    playing = rom
                }
            }
        }
        .background(Color.black)
        .onAppear {
            PokeDaisyHost.shared.onCloseRequested = { stop() }
            PokeDaisyHost.shared.onRestartRequested = { restarts += 1 }
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

struct ViewControllerView: UIViewControllerRepresentable {
    let make: () -> UIViewController
    func makeUIViewController(context: Context) -> UIViewController { make() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
