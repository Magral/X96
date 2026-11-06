package ru.x96.local;
import static ru.x96.local.CoreTests.*;
public final class CacheTests {
 static final class Tv implements TvShell {
  RepositoryTests.MemoryStore store=new RepositoryTests.MemoryStore();RepositoryTests.FakeTv base=new RepositoryTests.FakeTv(store);
  String free="500000",after="2000000";int trims;boolean fail;
  public String shell(String c) throws Exception{
   if(c.equals("df -k /data | tail -n 1"))return "/dev/data 60000000 1000000 "+free+" 2% /data";
   if(c.equals("pm trim-caches 1G")){trims++;if(fail)throw new java.io.IOException("lost");free=after;return "";}
   return base.shell(c);
  }
 }
 public static void main(String[] args)throws Exception{
  Tv tv=new Tv();TvRepository r=new TvRepository(tv,tv.store);r.trimCache();check(tv.trims==1,"low space runs one trim");check(r.cacheResult.contains("После: 1.91"),"before after result");
  r.trimCache();check(tv.trims==1&&r.cacheResult.contains("не требуется"),"enough space skips command");
  tv.free="bad";rejects(()->r.trimCache());check(tv.trims==1&&r.cacheResult.isEmpty(),"unknown space cannot clean or reuse result");
  tv.free="0";tv.after="0";r.trimCache();check(r.cacheResult.contains("не стало больше"),"no improvement reported honestly");
  tv.free="500000";tv.after="bad";r.trimCache();check(r.cacheResult.contains("недоступен"),"missing final measurement not success volume");
  tv.free="500000";tv.fail=true;int n=tv.trims;rejects(()->r.trimCache());check(tv.trims==n+1&&r.cacheResult.isEmpty(),"failure not retried");
  System.out.println("CacheTests: "+checks+" checks passed");
 }
}
