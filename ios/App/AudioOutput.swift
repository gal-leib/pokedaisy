import AVFoundation

/// The game's sound: AVAudioEngine pulls it from the C side's ring (pd_ios_audio_read) on its own thread.
final class AudioOutput {
    static let rate = 48000

    private let engine = AVAudioEngine()
    private var node: AVAudioSourceNode?
    private let scratch = UnsafeMutablePointer<Int16>.allocate(capacity: 8192 * 2)

    func start() {
        let session = AVAudioSession.sharedInstance()
        // Like any game: quiet with the ring / silent switch, mixed with other apps' sound.
        try? session.setCategory(.ambient, mode: .default)
        try? session.setPreferredSampleRate(Double(AudioOutput.rate))
        try? session.setPreferredIOBufferDuration(0.01)
        try? session.setActive(true)

        guard let format = AVAudioFormat(standardFormatWithSampleRate: Double(AudioOutput.rate), channels: 2) else { return }
        let scratch = self.scratch
        let node = AVAudioSourceNode(format: format) { _, _, frameCount, bufferList -> OSStatus in
            let buffers = UnsafeMutableAudioBufferListPointer(bufferList)
            let want = min(Int(frameCount), 8192)
            let got = pd_ios_audio_read(scratch, want)
            guard buffers.count >= 2,
                  let left = buffers[0].mData?.assumingMemoryBound(to: Float.self),
                  let right = buffers[1].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            for i in 0..<Int(frameCount) {
                if i < got {
                    left[i] = Float(scratch[i * 2]) / 32768
                    right[i] = Float(scratch[i * 2 + 1]) / 32768
                } else {
                    // The game fell behind: silence rather than a stale sample.
                    left[i] = 0
                    right[i] = 0
                }
            }
            return noErr
        }
        self.node = node
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        do {
            try engine.start()
        } catch {
            NSLog("PokeDaisy: audio didn't start: \(error)")
        }
    }

    func stop() {
        engine.stop()
        if let node { engine.detach(node) }
        node = nil
    }

    deinit {
        scratch.deallocate()
    }
}
