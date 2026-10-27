package me.snowsun.tabprefix.application.port;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import me.snowsun.tabprefix.domain.*;

public interface GraphicRepository {
  default CompletableFuture<PrefixEditCommit> edit(UUID actor,String group,UUID asset,String text,TextFormat format,String expected,PrefixDecoration decoration,int start,int end,long now) {
    if(decoration==null)return edit(actor,group,asset,text,format,expected,start,end,now);
    CompletableFuture<PrefixEditCommit> result=new CompletableFuture<>();result.completeExceptionally(new UnsupportedOperationException("Compound decorations unavailable"));return result;
  }
  default CompletableFuture<PrefixEditCommit> edit(UUID actor,String group,UUID asset,String text,TextFormat format,String expected,int start,int end,long now) {
    CompletableFuture<PrefixEditCommit> result=new CompletableFuture<>();result.completeExceptionally(new UnsupportedOperationException("Direct prefix editing unavailable"));return result;
  }
  default CompletableFuture<GraphicSnapshot> assignAsset(UUID asset, int start, int end) {
    CompletableFuture<GraphicSnapshot> f = new CompletableFuture<>();
    f.completeExceptionally(new UnsupportedOperationException("Standalone images unavailable"));
    return f;
  }

  CompletableFuture<GraphicSnapshot> load();

  CompletableFuture<Void> register(AssetDescriptor asset);

  CompletableFuture<Void> saveDraft(
      PrefixDraft draft, String hash, boolean singleUse, boolean bound);

  CompletableFuture<String> codeGroup(String hash);

  CompletableFuture<GraphicSnapshot> apply(
      String hash, UUID actor, String currentGroup, boolean manage, int start, int end, long now);

  CompletableFuture<GraphicSnapshot> remove(String group);

  CompletableFuture<List<UUID>> cleanup(long now);
}
