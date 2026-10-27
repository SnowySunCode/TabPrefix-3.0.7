package me.snowsun.tabprefix.infrastructure.resourcepack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import me.snowsun.tabprefix.domain.MinecraftVersion;

/** Preserve native fog, grayscale, GUI and OIT variants while filtering private RGBA glyphs. */
final class HudFragment {
  private HudFragment() {}
  static void install(Map<String,byte[]> entries,MinecraftVersion version,String source,boolean locations)throws IOException {
    String declarations=(locations?"layout(location = 4) ":"")+"flat in int tabprefixKind;\n"
        +(locations?"layout(location = 5) ":"")+"flat in ivec4 tabprefixBounds;\n"
        +(locations?"layout(location = 6) ":"")+"flat in ivec4 tabprefixCore;\n";
    source=source.replace("void main() {",declarations+HudShader.resource("sample.glsl")+"\nvoid main() {");
    // Private RGBA glyphs must not enter vanilla's red-channel-only grayscale conversion.
    source=source.replace("texture(Sampler0, texCoord0).rrrr","(tabprefixKind==0?texture(Sampler0, texCoord0).rrrr:tabprefix_color())");
    source=source.replace("= texture(Sampler0, texCoord0)","= (tabprefixKind==0?texture(Sampler0, texCoord0):tabprefix_color())");
    source=source.replace("color.a < 0.1","color.a < (tabprefixKind==0?0.1:0.002)");
    entries.put(HudShader.path(version)+".fsh",source.getBytes(StandardCharsets.UTF_8));
  }
}
