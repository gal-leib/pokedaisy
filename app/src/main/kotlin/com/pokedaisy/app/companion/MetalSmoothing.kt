package com.pokedaisy.app.companion

import com.pokedaisy.app.game.PictureEffects

/** Metal transport for the existing xBRZ asset. The edge/blend algorithm stays in its original GLSL. */
object MetalSmoothing {
    fun xbrz(source: String): PictureEffects.Program {
        val at = source.indexOf("#define BLEND_NONE")
        require(at >= 0) { "Unknown xBRZ fragment" }
        val coordinates = Regex("TEX0\\.xy\\s*=\\s*(TexCoord\\.xy[^;]*);").find(source)?.groupValues?.get(1)
            ?: error("Unknown xBRZ vertex coordinates")
        var body = source.substring(at).substringBeforeLast("#endif")
            .replace("void main()", "float4 shade()")
            .replace("FragColor =", "return")
        // GLSL's vector == / != return one boolean; Metal's return a vector of booleans.
        body = body.replace("#define eq(a,b)  (a == b)", "#define eq(a,b)  all(a == b)")
            .replace("#define neq(a,b) (a != b)", "#define neq(a,b) any(a != b)")
        require("#define eq(a,b)  all(a == b)" in body && "#define neq(a,b) any(a != b)" in body)
        val shader = """
${source.substringBefore("#if defined(VERTEX)")}
#define vec2 float2
#define vec3 float3
#define vec4 float4
#define ivec4 int4
#define COMPAT_TEXTURE sample
#define Source Texture
#define SourceSize vec4(TextureSize, 1.0 / TextureSize)
#define vTexCoord TEX0.xy
struct SmoothEffect {
    texture2d<float> Texture;
    sampler s;
    float2 OutputSize;
    float2 TextureSize;
    float4 TEX0;
    float4 sample(texture2d<float> t, float2 uv) {
        return t.sample(s, clamp(uv * TextureSize, float2(0.5), TextureSize - 0.5) / float2(t.get_width(), t.get_height()));
    }
    $body
};
fragment float4 game_fragment(V in [[stage_in]], constant Quad& q [[buffer(0)]],
                              texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]]) {
    SmoothEffect effect{tex, s, q.output.xy, q.textureSize.zw, float4(${coordinates.replace("TexCoord.xy", "in.uv")}, 0, 0)};
    return effect.shade();
}
""".trimIndent()
        return PictureEffects.Program("xBRZ", shader)
    }

    /** Original xBR vertex offsets are affine in UV, so the fragment can derive the same interpolated taps. */
    fun xbr(source: String): PictureEffects.Program {
        val stage = source.substringAfter("#elif defined(FRAGMENT)", "")
        require(stage.isNotEmpty()) { "Unknown xBR fragment" }
        val fields = Regex("\\bIN\\s+(vec[234])\\s+(\\w+)\\s*;").findAll(stage).toList()
        require(fields.size == 8) { "Unknown xBR varyings" }
        val vertex = source.substringBefore("#elif defined(FRAGMENT)").substringAfter("void main()")
            .substringAfter('{').substringBeforeLast('}')
            .replace(Regex("^\\s*(gl_Position|color)\\s*=.*;", RegexOption.MULTILINE), "")
        val at = stage.indexOf("#ifdef PARAMETER_UNIFORM")
        require(at >= 0) { "Unknown xBR defaults" }
        val body = stage.substring(at).substringBeforeLast("#endif").replace("void main()", "void shade()")
        val prefix = source.substringBefore("#if defined(VERTEX)")
            .replace(Regex("^\\s*#version[^\\n]*", RegexOption.MULTILINE), "")
            .replace(Regex("^\\s*#pragma parameter[^\\n]*", RegexOption.MULTILINE), "")
        val shader = """
$prefix
#define vec2 float2
#define vec3 float3
#define vec4 float4
#define mat4x3 float4x3
#define notEqual(a,b) ((a)!=(b))
#define tex2D sample
struct SmoothEffect {
    texture2d<float> decal;
    sampler s;
    float2 OutputSize;
    float2 TextureSize;
    float4 FragColor;
    ${fields.joinToString("\n") { "${it.groupValues[1]} ${it.groupValues[2]};" }}
    float4 sample(texture2d<float> t, float2 uv) {
        return t.sample(s, clamp(uv * TextureSize, float2(0.5), TextureSize - 0.5) / float2(t.get_width(), t.get_height()));
    }
    void coordinates(vec2 TexCoord) {
        $vertex
    }
    $body
};
fragment float4 game_fragment(V in [[stage_in]], constant Quad& q [[buffer(0)]],
                              texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]]) {
    SmoothEffect effect{tex, s, q.output.xy, q.textureSize.zw};
    effect.coordinates(in.uv);
    effect.shade();
    return float4(effect.FragColor.rgb, 1.0);
}
""".trimIndent()
        return PictureEffects.Program("xBR", shader)
    }

}
