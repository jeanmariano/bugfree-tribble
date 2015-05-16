precision mediump float;
varying vec4 v_Color;
varying vec3 v_Grid;
uniform sampler2D u_Texture1;
uniform sampler2D u_Texture2;
varying vec2 v_TexCoordinate;

void main() {
    float depth = gl_FragCoord.z / gl_FragCoord.w; // Calculate world-space distance.
    //gl_FragColor = v_Color + texture2D(u_Texture1, v_TexCoordinate);
    gl_FragColor = v_Color + mix(texture2D(u_Texture1, v_TexCoordinate),texture2D(u_Texture2, v_TexCoordinate),.25);
}
