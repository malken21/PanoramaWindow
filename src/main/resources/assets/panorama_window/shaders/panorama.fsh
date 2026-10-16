#version 330
#define PI 3.14159265

in vec2 TexCoord;

// The six faces are captured world-axis-aligned (front = north view / shader +Z, right = east / +X,
// back = south / -Z, left = west / -X, top = +Y, bottom = -Y). They no longer follow the player, so
// turning the view is just a longitude offset (uYaw) applied here per frame instead of re-rendering
// the world.
uniform sampler2D texFront;
uniform sampler2D texRight;
uniform sampler2D texBack;
uniform sampler2D texLeft;
uniform sampler2D texBottom;
uniform sampler2D texTop;

// View rotation in radians, tracking the player. uYaw = 0 puts the "front" (north) face at the
// window centre; uPitch = 0 keeps the horizon level. Turning the view is just these two rotations
// applied here per frame instead of moving the cameras / re-rendering the world.
uniform float uYaw;
uniform float uPitch;

out vec4 FragColor;

// Equirectangular pixel -> unit direction in the world-aligned cube frame.
vec3 genSphereVector() {
    vec2 source = -vec2(PI) + TexCoord * (2.0 * PI);
    float lat = source.y * 0.5;               // -PI/2 .. PI/2
    float cosLat = cos(lat);
    vec3 d = vec3(cosLat * sin(source.x), sin(lat), cosLat * cos(source.x));

    // Pitch about the view's right axis (X), then yaw about world up (Y). Doing pitch first keeps
    // it an FPS-style look (up stays up) rather than tilting the horizon as the view turns.
    float cp = cos(uPitch), sp = sin(uPitch);
    d = vec3(d.x, d.y * cp - d.z * sp, d.y * sp + d.z * cp);

    float cy = cos(uYaw), sy = sin(uYaw);
    d = vec3(d.x * cy + d.z * sy, d.y, -d.x * sy + d.z * cy);
    return d;
}

void main() {
    vec3 d = genSphereVector();
    vec3 ad = abs(d);
    vec2 uv;
    vec4 color;

    // Pick the single dominant axis (like a hardware cubemap) and gnomonic-project onto that face.
    // The per-face uv expressions match the previous multi-sample shader exactly, so orientation is
    // unchanged - but each pixel now comes from one face only, so there are no blend seams.
    if (ad.x >= ad.y && ad.x >= ad.z) {
        if (d.x > 0.0) {                                                   // +X  east / right
            uv = vec2((1.0 - d.z / ad.x) * 0.5, (d.y / ad.x + 1.0) * 0.5);
            color = texture(texRight, uv);
        } else {                                                          // -X  west / left
            uv = vec2((d.z / ad.x + 1.0) * 0.5, (d.y / ad.x + 1.0) * 0.5);
            color = texture(texLeft, uv);
        }
    } else if (ad.y >= ad.z) {
        if (d.y > 0.0) {                                                   // +Y  up / top
            uv = vec2((d.x / ad.y + 1.0) * 0.5, (1.0 - d.z / ad.y) * 0.5);
            color = texture(texTop, uv);
        } else {                                                          // -Y  down / bottom
            uv = vec2((d.x / ad.y + 1.0) * 0.5, (d.z / ad.y + 1.0) * 0.5);
            color = texture(texBottom, uv);
        }
    } else {
        if (d.z > 0.0) {                                                   // +Z  north / front
            uv = vec2((d.x / ad.z + 1.0) * 0.5, (d.y / ad.z + 1.0) * 0.5);
            color = texture(texFront, uv);
        } else {                                                          // -Z  south / back
            uv = vec2((1.0 - d.x / ad.z) * 0.5, (d.y / ad.z + 1.0) * 0.5);
            color = texture(texBack, uv);
        }
    }

    FragColor = vec4(color.rgb, 1.0);
}
