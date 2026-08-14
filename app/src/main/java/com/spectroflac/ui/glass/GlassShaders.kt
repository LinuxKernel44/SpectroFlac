package com.spectroflac.ui.glass

/**
 * AGSL sources for the liquid glass look (Android 13+).
 *
 * The backdrop is generated analytically rather than captured, so a glass panel can evaluate the
 * exact same function at refracted coordinates. That is what gives real refraction — the panel
 * bends the light of the background instead of just blurring a screenshot of it.
 *
 * Everything is computed in `float` and converted to `half` only in the return statement: SkSL
 * will not implicitly narrow, and a type error here would only surface at runtime.
 */
internal object GlassShaders {

    /** Shared by both shaders: everything needed to evaluate the animated backdrop. */
    private const val COMMON = """
        uniform float2 uResolution;
        uniform float uTime;

        float3 blob(float2 uv, float2 center, float3 color, float radius) {
            float d = distance(uv, center);
            float f = exp(-(d * d) / (radius * radius));
            return color * f;
        }

        float3 backdrop(float2 screenPos) {
            float h = max(uResolution.y, 1.0);
            float2 uv = screenPos / h;
            float w = uResolution.x / h;
            float t = uTime;

            // Four glows drifting over a near-black base. Radii are deliberately small: the glass
            // needs deep shadow to refract against, not a wash of colour.
            float3 col = float3(0.014, 0.016, 0.040);
            col += blob(uv, float2(w * 0.14 + 0.05 * sin(t * 0.31), 0.10 + 0.04 * cos(t * 0.27)),
                        float3(0.30, 0.24, 0.82), 0.26);
            col += blob(uv, float2(w * 0.97 + 0.05 * cos(t * 0.23), 0.40 + 0.05 * sin(t * 0.19)),
                        float3(0.05, 0.52, 0.66), 0.23);
            col += blob(uv, float2(w * 0.30 + 0.06 * sin(t * 0.17), 0.96 + 0.04 * cos(t * 0.21)),
                        float3(0.48, 0.22, 0.74), 0.28);
            col += blob(uv, float2(w * 0.88 + 0.04 * cos(t * 0.13), 0.76 + 0.04 * sin(t * 0.15)),
                        float3(0.04, 0.09, 0.42), 0.30);

            // Vignette: keeps the corners dark so panels read as lit from within.
            float2 q = screenPos / uResolution;
            float vig = smoothstep(1.05, 0.15, distance(q, float2(0.5, 0.5)));
            return col * (0.45 + 0.55 * vig);
        }

        float hash(float2 p) {
            return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
        }
    """

    /** Full-screen animated backdrop. */
    val BACKDROP = COMMON + """
        half4 main(float2 fragCoord) {
            float3 col = backdrop(fragCoord);
            // A touch of noise keeps wide gradients from banding on 8-bit panels.
            float grain = (hash(fragCoord) - 0.5) * 0.014;
            col += float3(grain, grain, grain);
            return half4(half(col.r), half(col.g), half(col.b), half(1.0));
        }
    """

    /**
     * One glass panel. Coordinates arrive in panel space; uOrigin places the panel on the backdrop
     * so the refracted sample lines up with what is behind it.
     */
    val GLASS = COMMON + """
        uniform float2 uOrigin;
        uniform float2 uSize;
        uniform float uRadius;
        uniform float uRefraction;
        uniform float uDispersion;
        uniform float uTint;
        uniform float uGlow;

        float sdRoundBox(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + float2(r, r);
            return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
        }

        half4 main(float2 fragCoord) {
            float2 halfSize = uSize * 0.5;
            float2 p = fragCoord - halfSize;
            float radius = min(uRadius, min(halfSize.x, halfSize.y));
            float sd = sdRoundBox(p, halfSize, radius);

            float alpha = 1.0 - smoothstep(-0.75, 0.75, sd);
            if (alpha <= 0.002) {
                return half4(0.0, 0.0, 0.0, 0.0);
            }

            float e = 1.0;
            float dx = sdRoundBox(p + float2(e, 0.0), halfSize, radius) -
                       sdRoundBox(p - float2(e, 0.0), halfSize, radius);
            float dy = sdRoundBox(p + float2(0.0, e), halfSize, radius) -
                       sdRoundBox(p - float2(0.0, e), halfSize, radius);
            float2 n = normalize(float2(dx, dy) + float2(0.00001, 0.00001));

            // 0 in the middle of the panel, 1 right at the rim: the lens is thickest at the edge.
            float edge = clamp(1.0 + sd / max(uRefraction, 1.0), 0.0, 1.0);
            float bend = edge * edge * edge * uRefraction;

            float2 screenPos = uOrigin + fragCoord;
            float3 col = float3(0.0, 0.0, 0.0);
            col.r = backdrop(screenPos + n * bend).r;
            col.g = backdrop(screenPos + n * bend * (1.0 + uDispersion)).g;
            col.b = backdrop(screenPos + n * bend * (1.0 + 2.0 * uDispersion)).b;

            col = mix(col, float3(1.0, 1.0, 1.0), uTint);
            col *= 1.0 + uGlow * (1.0 - edge);

            float2 lightDir = normalize(float2(-0.55, -0.84));
            float rim = pow(edge, 5.0);
            float spec = pow(max(dot(n, lightDir), 0.0), 3.0) * rim * 0.55;
            float shade = pow(max(dot(n, -lightDir), 0.0), 3.0) * rim * 0.10;
            float hairline = (1.0 - smoothstep(0.0, 1.6, abs(sd + 0.8))) * 0.22;
            float delta = spec - shade + hairline;
            col += float3(delta, delta, delta);

            return half4(half(col.r * alpha), half(col.g * alpha), half(col.b * alpha), half(alpha));
        }
    """
}
