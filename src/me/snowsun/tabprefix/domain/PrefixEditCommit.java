package me.snowsun.tabprefix.domain;

public final class PrefixEditCommit {
  public final GraphicSnapshot graphics;
  public final DisplayDesign design;
  public final int displayRevision;
  public final boolean changed;
  public PrefixEditCommit(GraphicSnapshot graphics,DisplayDesign design,int revision,boolean changed){
    this.graphics=graphics;this.design=design;this.displayRevision=revision;this.changed=changed;
  }
}
