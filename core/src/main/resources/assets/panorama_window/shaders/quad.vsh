// #version は ShaderProgram が付ける (GLSL 1.50 か 1.20)
in vec3 inPosition;
in vec2 inTexCoord;

out vec2 TexCoord;

void main()
{
   gl_Position = vec4(inPosition, 1.0);
   TexCoord = inTexCoord;
}
