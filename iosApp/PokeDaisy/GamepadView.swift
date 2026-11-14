import SwiftUI
import UIKit
import PokeDaisyUI

/// The game's buttons drawn by UIKit, the proof of concept's: kept beside the app's own (shared) touch controls so the
/// two can be timed against each other on a real iPad. A D-pad, A / B, L / R, START / SELECT; several fingers at once,
/// and a finger may slide from one button to the next or round the D-pad.
final class GamepadView: UIView {
    enum Button: Hashable { case dpad, a, b, l, r, start, select }

    /// The keys held, as the core takes them ([PkApiCore] KEY_*), whenever they change.
    var onKeys: ((Int32) -> Void)?

    private var keys: Int32 = 0
    private var frames: [Button: CGRect] = [:]
    private var views: [Button: UIView] = [:]
    private var dpadArms: [UIView] = []
    private var touchButtons: [UITouch: Set<Button>] = [:]
    private var touchDirs: [UITouch: Int32] = [:]

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = .black
        let titles: [(Button, String)] = [(.a, "A"), (.b, "B"), (.l, "L"), (.r, "R"), (.start, "START"), (.select, "SELECT")]
        for (button, title) in titles {
            let v = UILabel()
            v.text = title
            v.textAlignment = .center
            v.textColor = UIColor(white: 1, alpha: 0.85)
            v.font = .systemFont(ofSize: button == .a || button == .b ? 26 : 13, weight: .bold)
            v.layer.borderColor = UIColor(white: 1, alpha: 0.35).cgColor
            v.layer.borderWidth = 1.5
            v.clipsToBounds = true
            v.isUserInteractionEnabled = false
            addSubview(v)
            views[button] = v
        }
        let pad = UIView()
        pad.isUserInteractionEnabled = false
        addSubview(pad)
        views[.dpad] = pad
        for _ in 0..<4 {
            let arm = UIView()
            arm.layer.borderColor = UIColor(white: 1, alpha: 0.35).cgColor
            arm.layer.borderWidth = 1.5
            arm.layer.cornerRadius = 6
            arm.isUserInteractionEnabled = false
            pad.addSubview(arm)
            dpadArms.append(arm)
        }
        refreshLook()
    }

    required init?(coder: NSCoder) { fatalError() }

    /// The buttons in a strip: the D-pad left, A / B right, L / R over them, START / SELECT in the middle.
    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width, h = bounds.height
        let edge: CGFloat = 16
        let pad = min(h - 2 * edge, w * 0.3, 170)
        let ab = pad * 0.42
        let shoulder = CGSize(width: min(110, w * 0.18), height: 34)
        let midY = h / 2 + shoulder.height / 2
        var r: [Button: CGRect] = [:]
        r[.dpad] = CGRect(x: edge, y: midY - pad / 2, width: pad, height: pad).intersection(bounds.insetBy(dx: 0, dy: shoulder.height / 2))
        if let d = r[.dpad] { r[.dpad] = CGRect(x: d.minX, y: d.minY, width: d.height, height: d.height) }
        let right = w - edge
        r[.a] = CGRect(x: right - ab, y: midY - ab * 0.9, width: ab, height: ab)
        r[.b] = CGRect(x: right - ab * 2.1, y: midY - ab * 0.1, width: ab, height: ab)
        r[.l] = CGRect(x: edge, y: edge * 0.5, width: shoulder.width, height: shoulder.height)
        r[.r] = CGRect(x: w - edge - shoulder.width, y: edge * 0.5, width: shoulder.width, height: shoulder.height)
        let small = CGSize(width: 76, height: 30)
        r[.select] = CGRect(x: w / 2 - small.width - 6, y: h - edge - small.height, width: small.width, height: small.height)
        r[.start] = CGRect(x: w / 2 + 6, y: h - edge - small.height, width: small.width, height: small.height)
        place(r)
    }

    private func place(_ placed: [Button: CGRect]) {
        frames = placed
        for (button, v) in views {
            guard let r = placed[button] else { v.isHidden = true; continue }
            v.isHidden = false
            v.frame = r
            switch button {
            case .a, .b: v.layer.cornerRadius = r.height / 2
            case .dpad: break
            default: v.layer.cornerRadius = min(10, r.height / 2)
            }
        }
        if let r = placed[.dpad] {
            let arm = r.width / 3
            // up, right, down, left
            let rects = [CGRect(x: arm, y: 0, width: arm, height: arm + 2), CGRect(x: arm * 2 - 2, y: arm, width: arm + 2, height: arm),
                         CGRect(x: arm, y: arm * 2 - 2, width: arm, height: arm + 2), CGRect(x: 0, y: arm, width: arm + 2, height: arm)]
            for (i, a) in dpadArms.enumerated() { a.frame = rects[i] }
        }
    }

    private func buttons(at p: CGPoint) -> (Set<Button>, Int32) {
        var hit = Set<Button>()
        var dirs: Int32 = 0
        for (button, r) in frames where r.insetBy(dx: -12, dy: -12).contains(p) {
            if button == .dpad {
                let dx = p.x - r.midX, dy = p.y - r.midY
                if hypot(dx, dy) > r.width * 0.12 {
                    // Eight ways: a diagonal holds both.
                    let angle = atan2(dy, dx) * 180 / .pi // 0 = right, 90 = down
                    if angle > -67.5 && angle < 67.5 { dirs |= Keys.right }
                    if angle > 22.5 && angle < 157.5 { dirs |= Keys.down }
                    if angle > 112.5 || angle < -112.5 { dirs |= Keys.left }
                    if angle > -157.5 && angle < -22.5 { dirs |= Keys.up }
                }
            }
            hit.insert(button)
        }
        return (hit, dirs)
    }

    private func update(_ touches: Set<UITouch>, ended: Bool) {
        for t in touches {
            if ended {
                touchButtons[t] = nil
                touchDirs[t] = nil
            } else {
                let (now, dirs) = buttons(at: t.location(in: self))
                touchButtons[t] = now
                touchDirs[t] = dirs
            }
        }
        var k: Int32 = 0
        for (t, set) in touchButtons {
            for b in set {
                switch b {
                case .a: k |= Keys.a
                case .b: k |= Keys.b
                case .l: k |= Keys.l
                case .r: k |= Keys.r
                case .start: k |= Keys.start
                case .select: k |= Keys.select
                case .dpad: break
                }
            }
            k |= touchDirs[t] ?? 0
        }
        if k != keys {
            keys = k
            onKeys?(k)
        }
        refreshLook()
    }

    private func refreshLook() {
        let held = touchButtons.values.reduce(into: Set<Button>()) { $0.formUnion($1) }
        for (button, v) in views where button != .dpad {
            v.backgroundColor = UIColor(white: 1, alpha: held.contains(button) ? 0.4 : 0.12)
        }
        let dirs = [Keys.up, Keys.right, Keys.down, Keys.left]
        for (i, a) in dpadArms.enumerated() {
            a.backgroundColor = UIColor(white: 1, alpha: keys & dirs[i] != 0 ? 0.4 : 0.12)
        }
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) { update(touches, ended: false) }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) { update(touches, ended: false) }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { update(touches, ended: true) }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { update(touches, ended: true) }
}

/// The keys as the core takes them (pk_api.h's PK_API_KEY_*, PkApiCore's KEY_*).
private enum Keys {
    static let a: Int32 = 1 << 0
    static let b: Int32 = 1 << 1
    static let select: Int32 = 1 << 2
    static let start: Int32 = 1 << 3
    static let right: Int32 = 1 << 4
    static let left: Int32 = 1 << 5
    static let up: Int32 = 1 << 6
    static let down: Int32 = 1 << 7
    static let r: Int32 = 1 << 8
    static let l: Int32 = 1 << 9
}

/// [GamepadView] in SwiftUI, its keys to the running game.
struct NativeGamepad: UIViewRepresentable {
    func makeUIView(context: Context) -> GamepadView {
        let v = GamepadView()
        v.onKeys = { PokeDaisyHost.shared.setTouchKeys(keys: $0) }
        return v
    }
    func updateUIView(_ view: GamepadView, context: Context) {}
}
