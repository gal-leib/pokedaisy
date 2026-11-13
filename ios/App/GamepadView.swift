import UIKit

/// The on-screen game buttons: a D-pad, A / B, L / R, START / SELECT and the app's own QUICK MENU, FF and CLOSE.
/// Several fingers at once; a finger may slide from one button to the next (or round the D-pad). Touches outside the
/// buttons go through to what's under (the companion).
final class GamepadView: UIView {
    enum Button: Hashable {
        case dpad, a, b, l, r, start, select, quick, ff, close
    }

    /// GBA buttons held (PD_IOS_KEY_*), and the app's own.
    private(set) var keys: UInt32 = 0
    private(set) var ffHeld = false
    var onQuick: (() -> Void)?
    var onClose: (() -> Void)?

    private var frames: [Button: CGRect] = [:]
    private var views: [Button: UIView] = [:]
    private var dpadArrows: [UIView] = []
    private var touchButtons: [UITouch: Set<Button>] = [:]
    private var touchDirs: [UITouch: UInt32] = [:]

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = .clear
        let titles: [(Button, String)] = [(.a, "A"), (.b, "B"), (.l, "L"), (.r, "R"), (.start, "START"),
                                          (.select, "SELECT"), (.quick, "QUICK"), (.ff, "FF"), (.close, "CLOSE")]
        for (button, title) in titles {
            let v = UILabel()
            v.text = title
            v.textAlignment = .center
            v.textColor = UIColor(white: 1, alpha: 0.85)
            v.font = .systemFont(ofSize: button == .a || button == .b ? 26 : 13, weight: .bold)
            v.backgroundColor = UIColor(white: 1, alpha: 0.12)
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
            arm.backgroundColor = UIColor(white: 1, alpha: 0.12)
            arm.layer.borderColor = UIColor(white: 1, alpha: 0.35).cgColor
            arm.layer.borderWidth = 1.5
            arm.layer.cornerRadius = 6
            arm.isUserInteractionEnabled = false
            pad.addSubview(arm)
            dpadArrows.append(arm)
        }
    }

    required init?(coder: NSCoder) { fatalError() }

    /// Places the buttons (in this view's points).
    func place(_ placed: [Button: CGRect]) {
        frames = placed
        for (button, v) in views {
            guard let r = placed[button] else {
                v.isHidden = true
                continue
            }
            v.isHidden = false
            v.frame = r
            switch button {
            case .a, .b: v.layer.cornerRadius = r.height / 2
            case .dpad: break
            default: v.layer.cornerRadius = min(10, r.height / 2)
            }
        }
        if let r = placed[.dpad] {
            let s = r.width, arm = s / 3
            // up, right, down, left
            let rects = [CGRect(x: arm, y: 0, width: arm, height: arm + 2), CGRect(x: arm * 2 - 2, y: arm, width: arm + 2, height: arm),
                         CGRect(x: arm, y: arm * 2 - 2, width: arm, height: arm + 2), CGRect(x: 0, y: arm, width: arm + 2, height: arm)]
            for (i, a) in dpadArrows.enumerated() { a.frame = rects[i] }
        }
        refreshLook()
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        frames.values.contains { $0.insetBy(dx: -12, dy: -12).contains(point) }
    }

    private func buttons(at p: CGPoint) -> (Set<Button>, UInt32) {
        var hit = Set<Button>()
        var dirs: UInt32 = 0
        for (button, r) in frames where r.insetBy(dx: -12, dy: -12).contains(p) {
            if button == .dpad {
                let dx = p.x - r.midX, dy = p.y - r.midY
                let dead = r.width * 0.12
                if hypot(dx, dy) > dead {
                    // Eight ways: a diagonal holds both.
                    let angle = atan2(dy, dx) * 180 / .pi // 0 = right, 90 = down
                    if angle > -67.5 && angle < 67.5 { dirs |= Keys.right }
                    if angle > 22.5 && angle < 157.5 { dirs |= Keys.down }
                    if angle > 112.5 || angle < -112.5 { dirs |= Keys.left }
                    if angle > -157.5 && angle < -22.5 { dirs |= Keys.up }
                }
                hit.insert(.dpad)
            } else {
                hit.insert(button)
            }
        }
        return (hit, dirs)
    }

    private func update(_ touches: Set<UITouch>, ended: Bool) {
        for t in touches {
            let before = touchButtons[t] ?? []
            if ended {
                touchButtons[t] = nil
                touchDirs[t] = nil
                continue
            }
            var (now, dirs) = buttons(at: t.location(in: self))
            // The app's own buttons fire once, on the first touch; a slide doesn't pick them up.
            let once: Set<Button> = [.quick, .close]
            for b in now.intersection(once) where !before.contains(b) && t.phase == .began {
                if b == .quick { onQuick?() }
                if b == .close { onClose?() }
            }
            if t.phase != .began { now.subtract(once.subtracting(before)) }
            touchButtons[t] = now
            touchDirs[t] = dirs
        }
        var k: UInt32 = 0
        var ff = false
        for (t, set) in touchButtons {
            for b in set {
                switch b {
                case .a: k |= Keys.a
                case .b: k |= Keys.b
                case .l: k |= Keys.l
                case .r: k |= Keys.r
                case .start: k |= Keys.start
                case .select: k |= Keys.select
                case .ff: ff = true
                default: break
                }
            }
            k |= touchDirs[t] ?? 0
        }
        keys = k
        ffHeld = ff
        refreshLook()
    }

    private func refreshLook() {
        let held = touchButtons.values.reduce(into: Set<Button>()) { $0.formUnion($1) }
        for (button, v) in views where button != .dpad {
            v.backgroundColor = UIColor(white: 1, alpha: held.contains(button) ? 0.4 : 0.12)
        }
        let dirs = [Keys.up, Keys.right, Keys.down, Keys.left]
        for (i, a) in dpadArrows.enumerated() {
            a.backgroundColor = UIColor(white: 1, alpha: keys & dirs[i] != 0 ? 0.4 : 0.12)
        }
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) { update(touches, ended: false) }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) { update(touches, ended: false) }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { update(touches, ended: true) }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { update(touches, ended: true) }
}

/// The GBA's buttons as the C side takes them (pd_ios.h's PD_IOS_KEY_*).
enum Keys {
    static let a: UInt32 = 1 << 0
    static let b: UInt32 = 1 << 1
    static let select: UInt32 = 1 << 2
    static let start: UInt32 = 1 << 3
    static let right: UInt32 = 1 << 4
    static let left: UInt32 = 1 << 5
    static let up: UInt32 = 1 << 6
    static let down: UInt32 = 1 << 7
    static let r: UInt32 = 1 << 8
    static let l: UInt32 = 1 << 9
}
