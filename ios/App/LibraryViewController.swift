import UIKit
import UniformTypeIdentifiers

/// Where the games are: the app's Documents folder (the Files app shows it as On My iPad > PokeDaisy). Saves go in
/// Documents/saves, named after the game as mGBA and RetroArch name them, so a save copied in from another device is
/// found.
enum Library {
    static let changed = Notification.Name("PokeDaisyLibraryChanged")
    static let extensions: Set<String> = ["gba", "gb", "gbc"]

    static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }
    static var dataDir: URL { documents.appendingPathComponent("PokeDaisy", isDirectory: true) }
    static var savesDir: URL { documents.appendingPathComponent("saves", isDirectory: true) }

    static func games() -> [URL] {
        let files = (try? FileManager.default.contentsOfDirectory(at: documents, includingPropertiesForKeys: nil)) ?? []
        return files.filter { extensions.contains($0.pathExtension.lowercased()) }
            .sorted { $0.lastPathComponent.localizedCaseInsensitiveCompare($1.lastPathComponent) == .orderedAscending }
    }

    /// The save beside a game's name: a .sav, or a .srm already there (RetroArch's).
    static func save(for rom: URL) -> URL {
        try? FileManager.default.createDirectory(at: savesDir, withIntermediateDirectories: true)
        let base = rom.deletingPathExtension().lastPathComponent
        let srm = savesDir.appendingPathComponent(base + ".srm")
        if FileManager.default.fileExists(atPath: srm.path) { return srm }
        return savesDir.appendingPathComponent(base + ".sav")
    }

    /// Copies a picked file in (never moves or changes the original). A save (.sav / .srm) goes to saves/.
    @discardableResult
    static func importFile(_ url: URL) -> Bool {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let ext = url.pathExtension.lowercased()
        let dir: URL
        if extensions.contains(ext) {
            dir = documents
        } else if ext == "sav" || ext == "srm" {
            dir = savesDir
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        } else {
            return false
        }
        let to = dir.appendingPathComponent(url.lastPathComponent)
        if to.standardizedFileURL == url.standardizedFileURL { return true }
        // A save already here is kept: the new one comes in under another name rather than over it.
        var dest = to
        var n = 2
        while FileManager.default.fileExists(atPath: dest.path) {
            if extensions.contains(ext) { try? FileManager.default.removeItem(at: dest); break } // the same game again
            dest = dir.appendingPathComponent("\(url.deletingPathExtension().lastPathComponent) (\(n)).\(ext)")
            n += 1
        }
        do {
            try FileManager.default.copyItem(at: url, to: dest)
            return true
        } catch {
            NSLog("PokeDaisy: import failed: \(error)")
            return false
        }
    }
}

/// The game list: tap a game to play it, + to add games or saves from Files.
final class LibraryViewController: UITableViewController, UIDocumentPickerDelegate {
    private var games: [URL] = []

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "PokeDaisy"
        navigationItem.rightBarButtonItem = UIBarButtonItem(barButtonSystemItem: .add, target: self, action: #selector(addFiles))
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "game")
        NotificationCenter.default.addObserver(self, selector: #selector(reload), name: Library.changed, object: nil)
        reload()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reload()
    }

    @objc private func reload() {
        games = Library.games()
        tableView.reloadData()
    }

    @objc private func addFiles() {
        var types: [UTType] = Library.extensions.union(["sav", "srm"]).compactMap { UTType(filenameExtension: $0) }
        if types.isEmpty { types = [.data] }
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types + [.data], asCopy: true)
        picker.allowsMultipleSelection = true
        picker.delegate = self
        present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        urls.forEach { Library.importFile($0) }
        reload()
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        max(games.count, 1)
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "game", for: indexPath)
        var content = cell.defaultContentConfiguration()
        if games.isEmpty {
            content.text = "No games yet"
            content.secondaryText = "Tap + to add a .gba, .gb or .gbc file (and its save) from Files."
            cell.selectionStyle = .none
        } else {
            let game = games[indexPath.row]
            content.text = game.deletingPathExtension().lastPathComponent
            content.secondaryText = game.pathExtension.uppercased()
            cell.selectionStyle = .default
        }
        cell.contentConfiguration = content
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard !games.isEmpty else { return }
        let rom = games[indexPath.row]
        present(GameViewController(rom: rom, save: Library.save(for: rom)), animated: true)
    }
}
