import Metal
import MetalKit
import UIKit

/// Draws the game, its overlay (HUD, QUICK MENU, notices) and the companion as textured quads in one Metal pass.
/// Pixel art is scaled with "sharp bilinear": whole pixels with a one-screen-pixel blend at their edges, so a game
/// shown at 4.3x looks even, as mGBA's own sharp look and the 3DS port's SHARP 1.5x do.
final class Renderer {
    let device: MTLDevice
    private let queue: MTLCommandQueue
    private let pipeline: MTLRenderPipelineState
    private let sampler: MTLSamplerState

    private var gameTex: MTLTexture?
    private var overlayTex: MTLTexture
    private var companionTex: MTLTexture

    private struct Quad {
        var rect: SIMD4<Float>    // NDC x0, y0, x1, y1
        var uv: SIMD4<Float>      // u0, v0, u1, v1
        var size: SIMD4<Float>    // texture w, h; output px per texel x, y
        var params: SIMD4<Float>  // x: use the texture's alpha, y: dim, z: solid colour
        var color: SIMD4<Float>
    }

    private static let shader = """
    #include <metal_stdlib>
    using namespace metal;
    struct Quad { float4 rect; float4 uv; float4 size; float4 params; float4 color; };
    struct V { float4 pos [[position]]; float2 uv; };
    vertex V quad_vertex(uint vid [[vertex_id]], constant Quad& q [[buffer(0)]]) {
        float2 c = float2(vid & 1, vid >> 1);
        V out;
        out.pos = float4(mix(q.rect.x, q.rect.z, c.x), mix(q.rect.y, q.rect.w, c.y), 0, 1);
        out.uv = float2(mix(q.uv.x, q.uv.z, c.x), mix(q.uv.y, q.uv.w, c.y));
        return out;
    }
    fragment float4 quad_fragment(V in [[stage_in]], constant Quad& q [[buffer(0)]],
                                  texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]]) {
        if (q.params.z > 0.5) return q.color;
        float2 texSize = q.size.xy;
        float2 scale = max(q.size.zw, float2(1.0));
        float2 texel = in.uv * texSize;
        float2 base = floor(texel);
        float2 d = fract(texel) - 0.5;
        float2 range = 0.5 - 0.5 / scale;
        float2 f = (d - clamp(d, -range, range)) * scale + 0.5;
        float4 c = tex.sample(s, (base + f) / texSize);
        if (q.params.x < 0.5) c.a = 1.0;
        c.rgb *= (1.0 - q.params.y);
        return c;
    }
    """

    init?(view: MTKView) {
        guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else { return nil }
        self.device = device
        self.queue = queue
        view.device = device
        view.colorPixelFormat = .bgra8Unorm
        view.clearColor = MTLClearColor(red: 0.07, green: 0.07, blue: 0.09, alpha: 1)
        do {
            let library = try device.makeLibrary(source: Renderer.shader, options: nil)
            let desc = MTLRenderPipelineDescriptor()
            desc.vertexFunction = library.makeFunction(name: "quad_vertex")
            desc.fragmentFunction = library.makeFunction(name: "quad_fragment")
            let att = desc.colorAttachments[0]!
            att.pixelFormat = view.colorPixelFormat
            att.isBlendingEnabled = true
            att.sourceRGBBlendFactor = .sourceAlpha
            att.destinationRGBBlendFactor = .oneMinusSourceAlpha
            att.sourceAlphaBlendFactor = .one
            att.destinationAlphaBlendFactor = .oneMinusSourceAlpha
            pipeline = try device.makeRenderPipelineState(descriptor: desc)
        } catch {
            NSLog("PokeDaisy: Metal setup failed: \(error)")
            return nil
        }
        let sd = MTLSamplerDescriptor()
        sd.minFilter = .linear
        sd.magFilter = .linear
        sd.sAddressMode = .clampToEdge
        sd.tAddressMode = .clampToEdge
        guard let sampler = device.makeSamplerState(descriptor: sd),
              let overlay = Renderer.texture(device, .bgra8Unorm, Int(PD_IOS_TOP_W), Int(PD_IOS_TOP_H)),
              let companion = Renderer.texture(device, .bgra8Unorm, Int(PD_IOS_COMPANION_W), Int(PD_IOS_COMPANION_H))
        else { return nil }
        self.sampler = sampler
        self.overlayTex = overlay
        self.companionTex = companion
    }

    private static func texture(_ device: MTLDevice, _ format: MTLPixelFormat, _ w: Int, _ h: Int) -> MTLTexture? {
        let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: w, height: h, mipmapped: false)
        d.usage = .shaderRead
        d.storageMode = .shared
        return device.makeTexture(descriptor: d)
    }

    /// Where things go, in the drawable's pixels.
    struct Layout {
        var top: CGRect = .zero       // the 400x240 "top screen": the game, the overlay over it
        var companion: CGRect = .zero // 320x240 at a whole scale
    }

    private var frameW = 0, frameH = 0, frameFills = false, bar: UInt32 = 0

    /// Width over height of what shows of the top screen: all of it with EXPAND's window or a Game Boy game's coloured
    /// bars, else the game's frame (a GBA's 3:2) centred in it. The layout keeps even edges by this, not the canvas.
    var visibleAspect: CGFloat {
        let canvas = CGFloat(PD_IOS_TOP_W) / CGFloat(PD_IOS_TOP_H)
        guard frameW > 0, frameH > 0, !frameFills, bar == 0 else { return canvas }
        return min(canvas, CGFloat(frameW) / CGFloat(frameH))
    }
    private var overlayShown = false, overlayDim = false

    /// Copies what changed since the last refresh out of the C side.
    func upload() {
        var w: Int32 = 0, h: Int32 = 0, stride: Int32 = 0
        var fills = false
        var barRgb: UInt32 = 0
        if let px = pd_ios_frame(&w, &h, &stride, &fills, &barRgb), w > 0, h > 0 {
            if gameTex == nil || gameTex!.width != Int(w) || gameTex!.height != Int(h) {
                // 0x00BBGGRR words are R, G, B, X bytes.
                gameTex = Renderer.texture(device, .rgba8Unorm, Int(w), Int(h))
            }
            gameTex?.replace(region: MTLRegionMake2D(0, 0, Int(w), Int(h)), mipmapLevel: 0, withBytes: px,
                             bytesPerRow: Int(stride) * 4)
            frameW = Int(w)
            frameH = Int(h)
            frameFills = fills
            bar = barRgb
        } else {
            frameW = 0
        }
        var shown = false, dim = false, dirty = false
        let overlay = pd_ios_overlay(&shown, &dim, &dirty)
        overlayShown = shown
        overlayDim = dim
        if dirty, let overlay {
            overlayTex.replace(region: MTLRegionMake2D(0, 0, Int(PD_IOS_TOP_W), Int(PD_IOS_TOP_H)), mipmapLevel: 0,
                               withBytes: overlay, bytesPerRow: Int(PD_IOS_TOP_W) * 4)
        }
        var companionDirty = false
        if let comp = pd_ios_companion(&companionDirty), companionDirty {
            companionTex.replace(region: MTLRegionMake2D(0, 0, Int(PD_IOS_COMPANION_W), Int(PD_IOS_COMPANION_H)),
                                 mipmapLevel: 0, withBytes: comp, bytesPerRow: Int(PD_IOS_COMPANION_W) * 4)
        }
    }

    func draw(in view: MTKView, layout: Layout) {
        guard let pass = view.currentRenderPassDescriptor, let drawable = view.currentDrawable,
              let cmd = queue.makeCommandBuffer(), let enc = cmd.makeRenderCommandEncoder(descriptor: pass) else { return }
        let size = view.drawableSize
        enc.setRenderPipelineState(pipeline)
        enc.setFragmentSamplerState(sampler, index: 0)

        func ndc(_ r: CGRect) -> SIMD4<Float> {
            let x0 = Float(r.minX / size.width * 2 - 1), x1 = Float(r.maxX / size.width * 2 - 1)
            let y0 = Float(1 - r.minY / size.height * 2), y1 = Float(1 - r.maxY / size.height * 2)
            return SIMD4(x0, y0, x1, y1)
        }
        func quad(_ r: CGRect, _ tex: MTLTexture?, alpha: Bool = false, dim: Float = 0, color: SIMD4<Float>? = nil) {
            var q = Quad(rect: ndc(r), uv: SIMD4(0, 0, 1, 1), size: .zero, params: .zero, color: color ?? .zero)
            if let tex {
                q.size = SIMD4(Float(tex.width), Float(tex.height),
                               Float(r.width) / Float(tex.width), Float(r.height) / Float(tex.height))
                q.params = SIMD4(alpha ? 1 : 0, dim, 0, 0)
                enc.setFragmentTexture(tex, index: 0)
            } else {
                q.params = SIMD4(0, 0, 1, 0)
            }
            enc.setVertexBytes(&q, length: MemoryLayout<Quad>.stride, index: 0)
            enc.setFragmentBytes(&q, length: MemoryLayout<Quad>.stride, index: 0)
            enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        }

        let top = layout.top
        if frameW > 0, let gameTex {
            let dim: Float = overlayShown && overlayDim ? 0.4 : 0
            if frameFills {
                quad(top, gameTex, dim: dim)
            } else {
                // As high as the top screen, centred; a Game Boy game's bars in its paper colour.
                let w = top.height * CGFloat(frameW) / CGFloat(frameH)
                let r = CGRect(x: top.midX - w / 2, y: top.minY, width: w, height: top.height)
                if bar != 0 {
                    let c = SIMD4<Float>(Float(bar >> 16 & 0xFF) / 255, Float(bar >> 8 & 0xFF) / 255, Float(bar & 0xFF) / 255, 1)
                    quad(top, nil, color: c * (1 - dim) + SIMD4(0, 0, 0, dim))
                }
                quad(r, gameTex, dim: dim)
            }
        }
        if overlayShown { quad(top, overlayTex, alpha: true) }
        quad(layout.companion, companionTex)
        enc.endEncoding()
        cmd.present(drawable)
        cmd.commit()
    }
}
