package me.snowsun.tabprefix.presentation.display;

import me.snowsun.tabprefix.domain.AssetDescriptor;

public final class ChatPrefix {
  public final String text, graphic, frozenText, frozenGraphic;
  public final AssetDescriptor asset;
  public final java.util.List<Segment> parts;
  public static final class Segment {
    public final String text,graphic,frozenText,frozenGraphic;public final AssetDescriptor asset;
    public Segment(String text,String graphic,String frozenText,String frozenGraphic,AssetDescriptor asset){this.text=text;this.graphic=graphic;this.frozenText=frozenText;this.frozenGraphic=frozenGraphic;this.asset=asset;}
  }

  public ChatPrefix(String text, String graphic, AssetDescriptor asset) {
    this(text,graphic,asset,text,graphic);
  }

  public ChatPrefix(String text,String graphic,AssetDescriptor asset,String frozenText,String frozenGraphic) {
    this(text,graphic,asset,frozenText,frozenGraphic,java.util.Collections.emptyList());
  }
  public ChatPrefix(String text,String graphic,AssetDescriptor asset,String frozenText,String frozenGraphic,java.util.List<Segment> parts){
    this.parts=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(parts));
    this.frozenText=frozenText;this.frozenGraphic=frozenGraphic;
    this.text = text;
    this.graphic = graphic;
    this.asset = asset;
  }
}
