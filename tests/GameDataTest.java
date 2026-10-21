import java.io.*;
import java.nio.file.*;
import java.util.*;
import shiritori.web.GameData;
import shiritori.web.GameData.*;
public class GameDataTest {
    static int count;
    static void ok(boolean b,String label) { if(!b)throw new AssertionError(label);System.out.println("PASS "+(++count)+" "+label); }
    static Match match(GameData d,Player p,String difficulty) {
        Match m=new Match();m.playerId=p.id;m.genre="FISH";m.difficulty=difficulty;m.dictionaryVersion=d.dictionaryVersion;d.startClock(m);return m;
    }
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("shiritori-web-model-");Path csv=Path.of("src/main/webapp/WEB-INF/words.csv");
        try {
            GameData d=new GameData(Files.newInputStream(csv),dir);
            ok(d.words.size()==270,"辞書270件");
            ok(GameData.GENRES.keySet().stream().allMatch(g->d.all(g).size()>=30),"全5ジャンル");
            Word k=d.find("FISH","カツオ").get(0),o=d.find("FISH","オイカワ").get(0),w=d.find("FISH","ワカサギ").get(0);
            ok(d.find("FISH","鰹").get(0)==k,"別名は同一ID");
            ok(d.find("FISH","しゃけ").size()==1,"複数別名");
            ok(d.find("MOVIE","クレイマー、クレイマー").size()==1,"CSV引用符");
            ok(GameData.last("すたー")=='た'&&GameData.last("にゃ")=='や',"長音と小文字");
            Player a=new Player();a.name="A";d.db.players.add(a);Player b=new Player();b.name="B";d.db.players.add(b);
            Match m=match(d,a,"NORMAL");m.moves.add(new Move("PLAYER",k.id,false));m.moves.add(new Move("CPU",o.id,false));a.answered.put("FISH",new HashSet<>(Set.of(k.id)));
            d.pass(m);ok(m.passCount()==1&&m.answerCount()==3,"パスとCPU回答を別集計");
            ok(a.used("FISH").equals(Set.of(k.id)),"パスは累積に含めない");
            Match n=match(d,a,"NORMAL");ok(d.check(n,a,k).contains("過去"),"過去回答を拒否");
            ok(d.check(match(d,b,"NORMAL"),b,k)==null,"別プレイヤーに影響しない");
            ok(d.check(match(d,a,"EASY"),a,k)==null,"やさしいで再使用可");
            Match saved=match(d,a,"NORMAL");saved.moves.add(new Move("PLAYER",k.id,false));saved.moves.add(new Move("CPU",o.id,false));saved.moves.add(new Move("PLAYER",null,true));saved.moves.add(new Move("CPU",w.id,false));saved.savedAt="test";d.validate(saved,a);
            d.db.saves.put(a.id,d.copy(saved));d.persist();GameData re=new GameData(Files.newInputStream(csv),dir);
            ok(re.player(a.id).used("FISH").contains(k.id),"JSONで累積回答保持");
            Match loaded=re.db.saves.get(a.id);re.validate(loaded,re.player(a.id));ok(loaded.passCount()==1&&re.next(loaded)=='ぎ',"パス履歴保存再開");
            d.finish(saved,true,"test");d.finish(saved,true,"test");ok(d.db.scores.size()==1,"戦績二重計上防止");
            ok(!d.db.saves.containsKey(a.id),"終了済みセーブを削除");
            Match poison=match(d,b,"EASY");d.answer(poison,b,d.find("FISH","ニシン").get(0));ok(poison.finished&&!poison.playerWon,"んで敗北");
            ok(b.used("FISH").contains(d.find("FISH","ニシン").get(0).id),"んの回答も記録");
            Match expired=match(d,b,"EASY");expired.deadline=System.currentTimeMillis()-1;ok(d.remainingMillis(expired)==0,"期限のサーバー判定値");
            saved.dictionaryVersion="old";boolean refused=false;try { d.validate(saved,a); }catch(IllegalArgumentException e){refused=true;}ok(refused,"古い辞書を拒否");
            String originalHash=d.dictionaryVersion;
            Word added=d.addWord("MOVIE","WORK","検証映画","ケンショウエイガ","検証別名|検証略称");
            ok(added.reading.equals("けんしょうえいが"),"追加語のカタカナ読みを正規化");
            ok(d.find("MOVIE","検証別名").get(0).id.equals(added.id),"追加語の別名検索");
            boolean duplicate=false;try{d.addWord("MOVIE","WORK","検証映画","べつよみ","");}catch(IllegalArgumentException e){duplicate=true;}ok(duplicate,"追加語の重複拒否");
            boolean invalid=false;try{d.addWord("MOVIE","WORK","無効読み","test123","");}catch(IllegalArgumentException e){invalid=true;}ok(invalid,"英数字の読みを拒否");
            invalid=false;try{d.addWord("MOVIE","SINGER","無効種別","むこう","");}catch(IllegalArgumentException e){invalid=true;}ok(invalid,"ジャンルと種別の不一致を拒否");
            d.addWord("SHOWA","SONG","検証同名","けんしょうどうめい","");d.addWord("SHOWA","SINGER","検証同名","けんしょうどうめい","");
            ok(d.find("SHOWA","検証同名").size()==2,"曲名と歌手名の同名候補");
            Match beforeAdd=match(d,a,"NORMAL");beforeAdd.moves.add(new Move("PLAYER",k.id,false));beforeAdd.moves.add(new Move("CPU",o.id,false));
            d.validate(beforeAdd,a);ok(d.dictionaryVersion.equals(originalHash),"追加で初期辞書ハッシュを変えない");
            Word trigger=d.addWord("FISH","FISH","検証開始語","てすとぬ","");Word only=d.addWord("FISH","FISH","検証CPU語","ぬてすとえ","");
            Match customMatch=match(d,b,"NORMAL");d.answer(customMatch,b,trigger);
            ok(customMatch.moves.get(1).wordId.equals(only.id),"CPUが追加語を候補に使用");
            d.persist();GameData addedReload=new GameData(Files.newInputStream(csv),dir);
            ok(addedReload.find("MOVIE","検証映画").size()==1&&addedReload.db.customWords.size()==5,"再起動後も追加語保持");
            addedReload.validate(beforeAdd,addedReload.player(a.id));ok(true,"追加後も中断データを検証できる");
            com.google.gson.JsonObject legacy=d.gson.toJsonTree(d.db).getAsJsonObject();legacy.remove("customWords");Files.writeString(dir.resolve("web-data.json"),legacy.toString());
            GameData oldData=new GameData(Files.newInputStream(csv),dir);ok(oldData.db.customWords.isEmpty()&&oldData.words.size()==270,"旧版JSONを移行せず読込");
            Files.writeString(dir.resolve("web-data.json"),"{broken");refused=false;try{new GameData(Files.newInputStream(csv),dir);}catch(IOException e){refused=true;}ok(refused,"破損データ拒否");
            System.out.println("ALL PASSED: "+count);
        }finally { try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);} }
    }
}
