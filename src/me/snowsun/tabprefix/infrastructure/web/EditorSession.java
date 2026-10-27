package me.snowsun.tabprefix.infrastructure.web;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import me.snowsun.tabprefix.infrastructure.media.ProcessedMedia;

public final class EditorSession {
  public final UUID id = UUID.randomUUID(), owner;
  public final String player, group, origin;
  public final long expiresAt;
  public final AtomicBoolean busy = new AtomicBoolean();
  public volatile boolean revoked;
  public volatile boolean prefixes = true, design, preferences;
  public volatile Path upload;
  public volatile ProcessedMedia preview;
  public volatile String uploadFormat,uploadSource,previewSource,preparedKey;
  public volatile me.snowsun.tabprefix.domain.EditorOptions previewOptions;
  public volatile UUID preparedAsset,openedAsset;
  public final java.util.Set<UUID> ownedAssets=java.util.concurrent.ConcurrentHashMap.newKeySet();
  public final java.util.Map<String,UUID> preparedImages=new java.util.concurrent.ConcurrentHashMap<>();
  private long window = System.currentTimeMillis();
  private int requests, mutations, liveMutations;

  EditorSession(UUID owner, String player, String group, long expiresAt, String origin) {
    this.owner = owner;
    this.player = player;
    this.group = group;
    this.expiresAt = expiresAt;
    this.origin = origin;
  }

  synchronized boolean permit(long now,boolean mutation){return permit(now,mutation,false);}

  synchronized boolean permit(long now, boolean mutation, boolean live) {
    if (now - window >= 60000) {
      window = now;
      requests = 0;
      mutations = 0;liveMutations=0;
    }
    return ++requests <= 1200 && (!mutation || (live?++liveMutations<=120:++mutations<=30));
  }
}
