import SwiftUI
import UIKit
import PokeDaisyUI

/// Hosts the native game surface and shared Compose controllers. Library, setup, settings and controls are shared UI.
struct RootView: View {
    @State private var playing: String?
    @State private var failed: String?
    @State private var restarts = 0
    @State private var touchRevision = 0

    var body: some View {
        Group {
            if let rom = playing {
                GeometryReader { geo in
                    let _ = touchRevision
                    let gameBoy = ["gb", "gbc"].contains((rom as NSString).pathExtension.lowercased())
                    let frame = PokeDaisyHost.shared.gamePanelLayout(
                        width: Double(geo.size.width), height: Double(geo.size.height), gameBoy: gameBoy)
                    let panes = frame.wide ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
                    panes {
                        VStack(spacing: 0) {
                            game(rom).frame(height: CGFloat(frame.gameHeight))
                            if frame.controlsHeight > 0 {
                                ViewControllerView(make: {
                                    PokeDaisyHost.shared.touchControlsViewController(gameBoy: gameBoy, portrait: !frame.wide)
                                })
                                .id("\(rom)#\(!frame.wide)")
                                .frame(height: CGFloat(frame.controlsHeight))
                            }
                        }
                        .frame(width: frame.wide ? CGFloat(frame.columnWidth) : nil,
                               height: frame.wide ? nil : CGFloat(frame.columnHeight), alignment: .top)
                        .frame(maxHeight: frame.wide ? .infinity : nil, alignment: .top)
                        ViewControllerView(make: { PokeDaisyHost.shared.companionViewController() })
                    }
                }
            } else {
                ViewControllerView(make: {
                    PokeDaisyHost.shared.libraryViewController(failed: failed, onPlay: { rom in
                        failed = nil
                        playing = rom
                    })
                })
            }
        }
        .background(Color.black)
        .onAppear {
            PokeDaisyHost.shared.onCloseRequested = { stop() }
            PokeDaisyHost.shared.onRestartRequested = { restarts += 1 }
            PokeDaisyHost.shared.onTouchLayoutChanged = { touchRevision += 1 }
        }
    }

    private func game(_ rom: String) -> some View {
        ZStack(alignment: .topLeading) {
            ViewControllerView(make: {
                PokeDaisyHost.shared.gameViewController(romPath: rom, onFailed: {
                    DispatchQueue.main.async { failed = rom; stop() }
                })
            }).id("\(rom)#\(restarts)")
            ViewControllerView(make: { PokeDaisyHost.shared.gameToolbarViewController() })
                .frame(width: 160, height: 60).padding(8)
        }
    }

    private func stop() {
        PokeDaisyHost.shared.closeGame()
        playing = nil
    }
}

struct ViewControllerView: UIViewControllerRepresentable {
    let make: () -> UIViewController
    func makeUIViewController(context: Context) -> UIViewController { make() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
