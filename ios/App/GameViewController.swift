import GameController
import MetalKit
import UIKit

/// A running game: the game and the companion side by side (landscape) or stacked (portrait), the on-screen buttons
/// round them. The display's refresh drives everything: each one runs a frame of the game (pd_ios_tick) and draws.
final class GameViewController: UIViewController, MTKViewDelegate {
    private let romURL: URL
    private let saveURL: URL
    private var mtkView: MTKView!
    private var renderer: Renderer?
    private let gamepad = GamepadView()
    private let audio = AudioOutput()
    private var layout = Renderer.Layout()
    private var companionPoints = CGRect.zero
    private var lastTick = CACurrentMediaTime()
    private var keyboardKeys: UInt32 = 0
    /// Keys pressed since the last tick, held or not (a quick key press can fall between two refreshes).
    private var keyboardPressed: UInt32 = 0
    private var keyboardFF = false
    private var quickPending = false
    private var companionTouch: UITouch?
    private var started = false

    init(rom: URL, save: URL) {
        romURL = rom
        saveURL = save
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) { fatalError() }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var canBecomeFirstResponder: Bool { true }

    override func loadView() {
        let v = MTKView(frame: .zero)
        v.preferredFramesPerSecond = 60
        v.isMultipleTouchEnabled = true
        v.autoResizeDrawable = true
        mtkView = v
        view = v
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        renderer = Renderer(view: mtkView)
        mtkView.delegate = self
        gamepad.onQuick = { [weak self] in self?.quickPending = true }
        gamepad.onClose = { [weak self] in self?.leave() }
        view.addSubview(gamepad)
        UIDevice.current.isBatteryMonitoringEnabled = true

        let errorLen = 160
        var error = [CChar](repeating: 0, count: errorLen)
        let ok = romURL.path.withCString { rom in
            saveURL.path.withCString { save in pd_ios_open(rom, save, &error, errorLen) }
        }
        if !ok {
            mtkView.isPaused = true
            let why = String(cString: error)
            DispatchQueue.main.async { [weak self] in self?.fail(why) }
            return
        }
        started = true
        audio.start()
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(willResign), name: UIApplication.willResignActiveNotification, object: nil)
        center.addObserver(self, selector: #selector(didBecomeActive), name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        becomeFirstResponder()
    }

    private func fail(_ why: String) {
        let alert = UIAlertController(title: "Can't play this", message: why, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in self?.dismiss(animated: true) })
        present(alert, animated: true)
    }

    private func leave() {
        guard started else { return }
        started = false
        mtkView.isPaused = true
        audio.stop()
        pd_ios_close()
        dismiss(animated: true)
    }

    @objc private func willResign() {
        mtkView.isPaused = true
        if started { pd_ios_suspend() }
    }

    @objc private func didBecomeActive() {
        lastTick = CACurrentMediaTime()
        if started { mtkView.isPaused = false }
        becomeFirstResponder()
    }

    // MARK: layout

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        gamepad.frame = view.bounds
        relayout()
        // A rotation can leave the keyboard's presses with no one: take them back after every relayout.
        if view.window != nil && !isFirstResponder { becomeFirstResponder() }
    }

    /// Landscape: the companion on the right at a whole scale, the game left of it, the buttons under the game.
    /// Portrait: the game across the top, the companion under it with the D-pad and A / B either side.
    private func relayout() {
        let b = view.bounds.inset(by: view.safeAreaInsets)
        guard b.width > 0, b.height > 0 else { return }
        let px = view.contentScaleFactor
        let m: CGFloat = 12
        var game = CGRect.zero, comp = CGRect.zero
        var buttons: [GamepadView.Button: CGRect] = [:]
        // The companion's whole scale that fits w x h points.
        func companionScale(_ w: CGFloat, _ h: CGFloat) -> CGFloat {
            max(1, floor(min(w * px / 320, h * px / 240))) / px
        }
        let pad: CGFloat = min(170, max(120, min(b.width, b.height) * 0.2))
        let ab: CGFloat = pad * 0.42
        if b.width > b.height {
            // The buttons along the bottom (D-pad under the left thumb, A / B under the right), the game and the
            // companion side by side over them, the pair centred in the height that's left.
            let rowH: CGFloat = 34
            let controlsH = pad + rowH + 2 * m
            let topH = b.height - controlsH - m
            let s = companionScale(b.width * 0.5 - 1.5 * m, topH)
            let cw = 320 * s, ch = 240 * s
            let gw = b.width - cw - 3 * m
            let gh = min(gw * 0.6, topH)
            let bandH = max(ch, gh)
            let bandY = b.minY + m + (topH - bandH) / 2
            comp = CGRect(x: b.maxX - m - cw, y: bandY + (bandH - ch) / 2, width: cw, height: ch)
            let gameW = gh / 0.6
            game = CGRect(x: b.minX + m + (gw - gameW) / 2, y: bandY + (bandH - gh) / 2, width: gameW, height: gh)
            let area = CGRect(x: b.minX + m, y: b.maxY - controlsH, width: b.width - 2 * m, height: controlsH - m)
            let cy = area.maxY - pad / 2
            buttons[.dpad] = CGRect(x: area.minX + m, y: cy - pad / 2, width: pad, height: pad)
            buttons[.a] = CGRect(x: area.maxX - m - ab, y: cy - ab * 1.1, width: ab, height: ab)
            buttons[.b] = CGRect(x: area.maxX - m - ab * 2.2, y: cy - ab * 0.1, width: ab, height: ab)
            buttons[.l] = CGRect(x: area.minX + m, y: area.minY, width: 90, height: rowH)
            buttons[.r] = CGRect(x: area.maxX - m - 90, y: area.minY, width: 90, height: rowH)
            let rowY = cy - 15
            buttons[.select] = CGRect(x: area.midX - 215, y: rowY, width: 80, height: 30)
            buttons[.start] = CGRect(x: area.midX - 125, y: rowY, width: 80, height: 30)
            buttons[.quick] = CGRect(x: area.midX - 35, y: rowY, width: 80, height: 30)
            buttons[.ff] = CGRect(x: area.midX + 55, y: rowY, width: 70, height: 30)
            buttons[.close] = CGRect(x: area.midX + 135, y: rowY, width: 80, height: 30)
        } else {
            let gw = b.width - 2 * m
            game = CGRect(x: b.minX + m, y: b.minY + m, width: gw, height: gw * 0.6)
            let rest = CGRect(x: b.minX, y: game.maxY + m, width: b.width, height: b.maxY - game.maxY - m)
            let rowH: CGFloat = 34
            let s = companionScale(b.width - 2 * (pad + 2 * m), rest.height - 2 * rowH - 3 * m)
            comp = CGRect(x: rest.midX - 160 * s, y: rest.minY + rowH + m, width: 320 * s, height: 240 * s)
            let cy = comp.midY
            let sideL = CGRect(x: rest.minX, y: comp.minY, width: comp.minX - rest.minX, height: comp.height)
            let sideR = CGRect(x: comp.maxX, y: comp.minY, width: rest.maxX - comp.maxX, height: comp.height)
            let p = min(pad, sideL.width - 2 * m)
            buttons[.dpad] = CGRect(x: sideL.midX - p / 2, y: cy - p / 2, width: p, height: p)
            let a = min(ab, (sideR.width - 3 * m) / 2.2)
            buttons[.a] = CGRect(x: sideR.maxX - m - a, y: cy - a * 1.1, width: a, height: a)
            buttons[.b] = CGRect(x: sideR.maxX - m - a * 2.2, y: cy - a * 0.1, width: a, height: a)
            buttons[.l] = CGRect(x: rest.minX + m, y: rest.minY, width: 90, height: rowH)
            buttons[.r] = CGRect(x: rest.maxX - m - 90, y: rest.minY, width: 90, height: rowH)
            buttons[.ff] = CGRect(x: rest.midX - 35, y: rest.minY, width: 70, height: rowH - 4)
            let rowY = comp.maxY + m
            buttons[.select] = CGRect(x: rest.midX - 170, y: rowY, width: 80, height: 30)
            buttons[.start] = CGRect(x: rest.midX - 80, y: rowY, width: 80, height: 30)
            buttons[.quick] = CGRect(x: rest.midX + 10, y: rowY, width: 80, height: 30)
            buttons[.close] = CGRect(x: rest.midX + 100, y: rowY, width: 80, height: 30)
        }
        companionPoints = comp
        gamepad.place(buttons)
        func toPx(_ r: CGRect) -> CGRect {
            CGRect(x: (r.minX * px).rounded(), y: (r.minY * px).rounded(), width: (r.width * px).rounded(), height: (r.height * px).rounded())
        }
        layout = Renderer.Layout(top: toPx(game), companion: toPx(comp))
    }

    // MARK: the companion's touches

    private func companionPoint(_ t: UITouch) -> (Int32, Int32) {
        let p = t.location(in: view)
        let x = (p.x - companionPoints.minX) / companionPoints.width * 320
        let y = (p.y - companionPoints.minY) / companionPoints.height * 240
        return (Int32(max(0, min(319, x))), Int32(max(0, min(239, y))))
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard companionTouch == nil, let t = touches.first(where: { companionPoints.contains($0.location(in: view)) }) else { return }
        companionTouch = t
        let (x, y) = companionPoint(t)
        pd_ios_touch(x, y, true)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = companionTouch, touches.contains(t) else { return }
        let (x, y) = companionPoint(t)
        pd_ios_touch(x, y, true)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = companionTouch, touches.contains(t) else { return }
        companionTouch = nil
        pd_ios_touch(0, 0, false)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { touchesEnded(touches, with: event) }

    // MARK: a keyboard (the simulator's is the Mac's) and game controllers

    private static let keyMap: [UIKeyboardHIDUsage: UInt32] = [
        .keyboardUpArrow: Keys.up, .keyboardDownArrow: Keys.down, .keyboardLeftArrow: Keys.left, .keyboardRightArrow: Keys.right,
        .keyboardX: Keys.a, .keyboardZ: Keys.b, .keyboardA: Keys.l, .keyboardS: Keys.r,
        .keyboardReturnOrEnter: Keys.start, .keyboardDeleteOrBackspace: Keys.select, .keyboardRightShift: Keys.select,
    ]

    private func handle(_ presses: Set<UIPress>, down: Bool) -> Bool {
        var handled = false
        for p in presses {
            guard let code = p.key?.keyCode else { continue }
            if let bit = GameViewController.keyMap[code] {
                if down { keyboardKeys |= bit; keyboardPressed |= bit } else { keyboardKeys &= ~bit }
                handled = true
            } else if code == .keyboardQ {
                if down { quickPending = true }
                handled = true
            } else if code == .keyboardTab || code == .keyboardSpacebar {
                keyboardFF = down
                handled = true
            }
        }
        return handled
    }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if !handle(presses, down: true) { super.pressesBegan(presses, with: event) }
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if !handle(presses, down: false) { super.pressesEnded(presses, with: event) }
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if !handle(presses, down: false) { super.pressesCancelled(presses, with: event) }
    }

    private var controllerQuickWas = false

    private func controllerInput() -> (UInt32, Bool, Bool, Int32) {
        guard let pad = GCController.controllers().first?.extendedGamepad else { return (0, false, false, -1) }
        var k: UInt32 = 0
        let dpad = pad.dpad, stick = pad.leftThumbstick
        if dpad.up.isPressed || stick.yAxis.value > 0.5 { k |= Keys.up }
        if dpad.down.isPressed || stick.yAxis.value < -0.5 { k |= Keys.down }
        if dpad.left.isPressed || stick.xAxis.value < -0.5 { k |= Keys.left }
        if dpad.right.isPressed || stick.xAxis.value > 0.5 { k |= Keys.right }
        // Nintendo's places: the east button is A.
        if pad.buttonB.isPressed { k |= Keys.a }
        if pad.buttonA.isPressed { k |= Keys.b }
        if pad.leftShoulder.isPressed { k |= Keys.l }
        if pad.rightShoulder.isPressed { k |= Keys.r }
        if pad.buttonMenu.isPressed { k |= Keys.start }
        if pad.buttonOptions?.isPressed == true { k |= Keys.select }
        let quick = pad.buttonY.isPressed
        let quickDown = quick && !controllerQuickWas
        controllerQuickWas = quick
        let ff = pad.rightTrigger.isPressed
        // The left stick aims the QUICK MENU's ring: degrees clockwise from up.
        let x = stick.xAxis.value, y = stick.yAxis.value
        var angle: Int32 = -1
        if hypot(x, y) > 0.5 {
            var a = atan2(Double(x), Double(y)) * 180 / .pi
            if a < 0 { a += 360 }
            angle = Int32(a)
        }
        return (k, quickDown, ff, angle)
    }

    // MARK: the refresh

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {
        relayout()
    }

    func draw(in view: MTKView) {
        guard started, let renderer else { return }
        let now = CACurrentMediaTime()
        let ms = UInt32(max(0, min(250, (now - lastTick) * 1000)))
        lastTick = now
        let (padKeys, padQuick, padFF, angle) = controllerInput()
        let level = UIDevice.current.batteryLevel
        pd_ios_set_battery(level < 0 ? -1 : Int32(level * 100))
        let quick = quickPending || padQuick
        quickPending = false
        let keys = gamepad.takeKeys() | keyboardKeys | keyboardPressed | padKeys
        keyboardPressed = 0
        pd_ios_tick(keys, quick, gamepad.ffHeld || keyboardFF || padFF, angle, ms)
        if pd_ios_take_leave() {
            leave()
            return
        }
        renderer.upload()
        renderer.draw(in: view, layout: layout)
    }
}
