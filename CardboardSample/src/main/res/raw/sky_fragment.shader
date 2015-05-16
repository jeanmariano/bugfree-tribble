precision mediump float;
varying vec4 v_Color;
varying vec3 v_Grid;

uniform sampler2D u_TextureSky;
varying vec2 v_TexCoordinateSky;

void main() {
    float depth = gl_FragCoord.z / gl_FragCoord.w; // Calculate world-space distance.
    //gl_FragColor = v_Color;
    gl_FragColor = v_Color + texture2D(u_TextureSky, v_TexCoordinateSky);
}
