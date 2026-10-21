package shiritori.web;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.text.Normalizer;
import java.util.*;
import com.google.gson.*;

/** データ型・辞書・対戦ルール・JSON保存をまとめる。Javaファイル数を抑えるため内部クラスを用いる。 */
public class GameData {
    public static final Map<String,String> GENRES = new LinkedHashMap<>();
    static {
        GENRES.put("MOVIE","映画のタイトル"); GENRES.put("HISTORY","歴史上の人物"); GENRES.put("FISH","魚の名前");
        GENRES.put("ROBOT_ANIME","日本のロボットアニメ"); GENRES.put("SHOWA","昭和のヒット曲・歌手名");
    }
    public static class Word implements java.io.Serializable {
        private static final long serialVersionUID=1L;
        public String id, genre, type, name, reading;
        public List<String> aliases = new ArrayList<>();
        public String label() { return "［" + switch(type) { case "SONG" -> "曲"; case "SINGER" -> "歌手"; default -> "言葉"; } + "］" + name + "（" + reading + "）"; }
    }
    public static class Player {
        public String id = UUID.randomUUID().toString();
        public String name;
        public Map<String,Set<String>> answered = new HashMap<>();
        public Set<String> used(String genre) { return answered.getOrDefault(genre, Collections.emptySet()); }
    }
    public static class Move implements java.io.Serializable {
        private static final long serialVersionUID=1L;
        public String actor, wordId;
        public boolean pass;
        public Move(String actor, String wordId, boolean pass) { this.actor=actor;this.wordId=wordId;this.pass=pass; }
    }
    public static class Match implements java.io.Serializable {
        private static final long serialVersionUID=1L;
        public int version=1, turn=0;
        public String id=UUID.randomUUID().toString(), playerId, genre, difficulty, dictionaryVersion, savedAt;
        public List<Move> moves=new ArrayList<>();
        public long deadline;
        public boolean finished, playerWon;
        public String reason;
        public int answerCount() { int n=0;for(Move m:moves)if(!m.pass)n++;return n; }
        public int passCount() { int n=0;for(Move m:moves)if(m.pass)n++;return n; }
    }
    public static class Score {
        public String matchId, playerId, genre, reason;
        public boolean won;
        public int words, answers;
    }
    public static class Database {
        public int version=1;
        public List<Player> players=new ArrayList<>();
        public Map<String,Match> saves=new HashMap<>();
        public List<Score> scores=new ArrayList<>();
        public List<Word> customWords=new ArrayList<>();
    }
    public Database db=new Database();
    public final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    public final Map<String,Word> words=new LinkedHashMap<>();
    private final Map<String,Word> baseWords=new LinkedHashMap<>();
    public final String dictionaryVersion;
    public final Path file;
    public final int seconds;
    private final Random random=new Random();

    public GameData(InputStream dictionary, Path directory) throws IOException {
        if(dictionary==null)throw new IOException("辞書がない。");
        byte[] bytes;
        try(dictionary) { bytes=dictionary.readAllBytes(); }
        try { dictionaryVersion=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException e) { throw new IOException(e); }
        List<String> lines=new String(bytes,StandardCharsets.UTF_8).lines().toList();
        for(int i=1;i<lines.size();i++) {
            if(lines.get(i).isBlank())continue;
            try {
                List<String> c=csv(lines.get(i));if(c.size()!=6)throw new IllegalArgumentException("列数");
                Word w=new Word();w.id=c.get(0);w.genre=c.get(1);w.type=c.get(2);w.name=c.get(3);w.reading=normalize(c.get(4));
                if(!GENRES.containsKey(w.genre)||w.name.isBlank()||!w.reading.matches("[ぁ-ゖー]+")||w.reading.startsWith("ー")||words.containsKey(w.id))throw new IllegalArgumentException("辞書形式");
                if(!c.get(5).isBlank())w.aliases.addAll(Arrays.asList(c.get(5).split("\\|")));
                words.put(w.id,w);
            }catch(RuntimeException e) { throw new IOException("辞書"+(i+1)+"行目が不正である。",e); }
        }
        for(String g:GENRES.keySet())if(all(g).isEmpty())throw new IOException("ジャンルの辞書がない: "+g);
        baseWords.putAll(words);
        file=directory.resolve("web-data.json");
        seconds=Math.max(1,Math.min(300,Integer.getInteger("shiritori.thinkingSeconds",30)));
        if(Files.exists(file)) {
            try {
                if(Files.size(file)>30_000_000)throw new IOException("保存ファイルが大きすぎる。");
                Database loaded=gson.fromJson(Files.readString(file,StandardCharsets.UTF_8),Database.class);
                if(loaded==null||loaded.version!=1||loaded.players==null||loaded.saves==null||loaded.scores==null)throw new IOException("JSONの形式が異なる。");
                Set<String> ids=new HashSet<>();
                for(Player p:loaded.players) {
                    if(p==null||p.id==null||p.name==null||p.name.isBlank()||p.answered==null||!ids.add(p.id))throw new IOException("プレイヤー情報が不正である。");
                    for(var entry:p.answered.entrySet())if(!GENRES.containsKey(entry.getKey())||entry.getValue()==null||entry.getValue().contains(null))throw new IOException("回答履歴が不正である。");
                }
                for(var entry:loaded.saves.entrySet())if(entry.getValue()==null||!Objects.equals(entry.getKey(),entry.getValue().playerId)||!ids.contains(entry.getKey()))throw new IOException("セーブが不正である。");
                for(Score s:loaded.scores)if(s==null||s.matchId==null||s.playerId==null||!ids.contains(s.playerId))throw new IOException("戦績が不正である。");
                if(loaded.customWords==null)loaded.customWords=new ArrayList<>();
                db=loaded;
                try { refreshWords(); }catch(IllegalArgumentException e){throw new IOException("追加辞書が不正である。",e);}
            }catch(JsonParseException|IllegalStateException e) { throw new IOException("保存JSONが破損している。退避して確認する。",e); }
        }
    }
    /** 初期CSVと追加語を合成する。初期CSVのハッシュは追加時に変更しないため、旧セーブも再開できる。 */
    public void refreshWords() {
        Map<String,Word> merged=new LinkedHashMap<>(baseWords);
        for(Word w:db.customWords) {
            if(w==null||w.id==null||!w.id.matches("custom_[0-9a-f-]{36}")||merged.containsKey(w.id))throw new IllegalArgumentException("追加語のIDが不正である。");
            validateWord(w);
            merged.put(w.id,w);
        }
        words.clear();words.putAll(merged);
    }
    private void validateWord(Word w) {
        if(!GENRES.containsKey(w.genre)||w.type==null||w.name==null||w.reading==null||w.aliases==null)
            throw new IllegalArgumentException("ジャンル・名称・読みを入力する。");
        String expected=switch(w.genre){case "MOVIE","ROBOT_ANIME"->"WORK";case "HISTORY"->"PERSON";case "FISH"->"FISH";default->w.type;};
        if(!expected.equals(w.type)||!Set.of("WORK","PERSON","FISH","SONG","SINGER").contains(w.type)
                ||(w.genre.equals("SHOWA")&&!Set.of("SONG","SINGER").contains(w.type)))throw new IllegalArgumentException("ジャンルと種別が一致しない。");
        if(w.name.isBlank()||w.name.length()>200||w.name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("名称は改行を含まない1～200文字とする。");
        if(w.reading.length()>200||!w.reading.matches("[ぁ-ゖー]+")||w.reading.startsWith("ー"))throw new IllegalArgumentException("読みはひらがな・カタカナで入力する。空白・記号・英数字は使えない。");
        if(w.aliases.size()>10)throw new IllegalArgumentException("別名は10件以内とする。");
        for(String alias:w.aliases)if(alias==null||alias.isBlank()||alias.length()>100||alias.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("別名は各1～100文字とする。");
    }
    public Word addWord(String genre,String type,String name,String reading,String aliases) {
        if(db.customWords.size()>=5000)throw new IllegalArgumentException("追加語の上限5000件に達している。");
        Word w=new Word();w.id="custom_"+UUID.randomUUID();w.genre=genre;w.type=type;w.name=name.strip();w.reading=normalize(reading);
        if(aliases.length()>1100)throw new IllegalArgumentException("別名の入力が長すぎる。");
        if(!aliases.isBlank())for(String a:aliases.split("\\|",-1)) {
            String alias=a.strip();if(!alias.isBlank()&&!w.aliases.contains(alias))w.aliases.add(alias);
        }
        validateWord(w);
        String key=normalize(w.name);
        for(Word existing:all(genre))if(existing.type.equals(type)&&(normalize(existing.name).equals(key)
                ||existing.aliases.stream().anyMatch(a->normalize(a).equals(key))))throw new IllegalArgumentException("この名称はすでに登録されている。読みや別名で検索する。");
        db.customWords.add(w);words.put(w.id,w);return w;
    }
    public void persist() throws IOException {
        Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"save-",".tmp");
        try {
            Files.writeString(temp,gson.toJson(db),StandardCharsets.UTF_8);
            try { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
        }finally { Files.deleteIfExists(temp); }
    }
    public Match copy(Match m) { return m==null?null:gson.fromJson(gson.toJson(m),Match.class); }
    public Player player(String id) { for(Player p:db.players)if(p.id.equals(id))return p;return null; }
    public List<Word> all(String genre) { List<Word> list=new ArrayList<>();for(Word w:words.values())if(w.genre.equals(genre))list.add(w);return list; }
    public List<Word> find(String genre,String input) {
        String key=normalize(input);List<Word> found=new ArrayList<>();
        for(Word w:all(genre)) {
            if(normalize(w.name).equals(key)||w.reading.equals(key)||w.aliases.stream().anyMatch(a->normalize(a).equals(key)))found.add(w);
        }return found;
    }
    public Set<String> used(Match m) { Set<String> used=new HashSet<>();for(Move move:m.moves)if(!move.pass)used.add(move.wordId);return used; }
    public Word last(Match m) { for(int i=m.moves.size()-1;i>=0;i--)if(!m.moves.get(i).pass)return words.get(m.moves.get(i).wordId);return null; }
    public Character next(Match m) { Word w=last(m);return w==null?null:last(w.reading); }
    public boolean normal(Match m) { return m.difficulty.equals("NORMAL"); }
    public int remaining(Player p,String genre) { int n=0;for(Word w:all(genre))if(!p.used(genre).contains(w.id))n++;return n; }
    public int remaining(Player p,Match m) {
        if(!normal(m))return all(m.genre).size();Set<String> banned=used(m);banned.addAll(p.used(m.genre));
        int n=0;for(Word w:all(m.genre))if(!banned.contains(w.id))n++;return n;
    }
    public void startClock(Match m) { m.deadline=System.currentTimeMillis()+seconds*1000L; }
    public long remainingMillis(Match m) { return m.finished?0:Math.max(0,m.deadline-System.currentTimeMillis()); }
    public String check(Match m,Player p,Word w) {
        if(w==null||!w.genre.equals(m.genre))return "選択ジャンルの辞書にない言葉である。";
        Character c=next(m);if(c!=null&&first(w.reading)!=c)return "「"+c+"」から始まる言葉が必要である。";
        if(normal(m)) {
            if(used(m).contains(w.id))return "この対戦ですでに使われている。";
            if(p.used(m.genre).contains(w.id))return "過去に自分が回答した言葉である。ふつうでは再使用できない。";
        }return null;
    }
    public void answer(Match m,Player p,Word w) {
        String error=check(m,p,w);if(error!=null)throw new IllegalArgumentException(error);
        p.answered.computeIfAbsent(m.genre,k->new HashSet<>()).add(w.id);
        m.moves.add(new Move("PLAYER",w.id,false));
        if(last(w.reading)=='ん')finish(m,false,"「ん」で終わる言葉を回答した。");else cpu(m);
    }
    public void pass(Match m) { m.moves.add(new Move("PLAYER",null,true));cpu(m); }
    public void cpu(Match m) {
        Character c=next(m);Set<String> banned=normal(m)?used(m):Collections.emptySet();List<Word> candidates=new ArrayList<>();
        for(Word w:all(m.genre))if(!banned.contains(w.id)&&(c==null||first(w.reading)==c))candidates.add(w);
        if(candidates.isEmpty()) { finish(m,true,"CPUが答えられる言葉がなくなった。");return; }
        if(normal(m)) {
            List<Word> safe=new ArrayList<>();for(Word w:candidates)if(last(w.reading)!='ん')safe.add(w);if(!safe.isEmpty())candidates=safe;
        }
        Word w=candidates.get(random.nextInt(candidates.size()));m.moves.add(new Move("CPU",w.id,false));
        if(last(w.reading)=='ん')finish(m,true,"CPUが「ん」で終わる言葉を回答した。");else { m.turn++;startClock(m); }
    }
    public boolean completed(String id) { for(Score s:db.scores)if(s.matchId.equals(id))return true;return false; }
    public void finish(Match m,boolean won,String reason) {
        m.finished=true;m.playerWon=won;m.reason=reason;m.deadline=0;
        if(!completed(m.id)) {
            Score s=new Score();s.matchId=m.id;s.playerId=m.playerId;s.genre=m.genre;s.reason=reason;s.won=won;s.words=m.answerCount();
            for(Move move:m.moves)if(move.actor.equals("PLAYER")&&!move.pass)s.answers++;
            db.scores.add(s);
        }
        Match saved=db.saves.get(m.playerId);if(saved!=null&&saved.id.equals(m.id))db.saves.remove(m.playerId);
    }
    public void validate(Match m,Player p) {
        if(m==null||m.version!=1||!Objects.equals(m.dictionaryVersion,dictionaryVersion)||!Objects.equals(m.playerId,p.id)
                ||!GENRES.containsKey(m.genre)||!Set.of("EASY","NORMAL").contains(m.difficulty)||m.finished||completed(m.id)
                ||m.moves==null||m.moves.size()%2!=0)throw new IllegalArgumentException("保存形式・辞書が一致しないか、対戦が終了している。");
        Set<String> used=new HashSet<>();Character required=null;int i=0;
        for(Move move:m.moves) {
            if(move==null||!Objects.equals(move.actor,i++%2==0?"PLAYER":"CPU"))throw new IllegalArgumentException("保存履歴の手番が不正である。");
            if(move.pass) { if(!move.actor.equals("PLAYER")||move.wordId!=null)throw new IllegalArgumentException("パス履歴が不正である。");continue; }
            Word w=words.get(move.wordId);
            if(w==null||!w.genre.equals(m.genre)||last(w.reading)=='ん'||(required!=null&&first(w.reading)!=required)||(normal(m)&&!used.add(w.id)))
                throw new IllegalArgumentException("保存履歴が不正である。");
            required=last(w.reading);
        }
    }
    public static String normalize(String text) {
        StringBuilder out=new StringBuilder();for(char c:Normalizer.normalize(text.strip(),Normalizer.Form.NFKC).toCharArray())out.append(c>='ァ'&&c<='ヶ'?(char)(c-0x60):c);
        return out.toString().toLowerCase(Locale.ROOT);
    }
    private static char large(char c) { int i="ぁぃぅぇぉゃゅょっゎゕゖ".indexOf(c);return i<0?c:"あいうえおやゆよつわかけ".charAt(i); }
    public static char first(String r) { return large(r.charAt(0)); }
    public static char last(String r) { int i=r.length()-1;while(i>0&&r.charAt(i)=='ー')i--;return large(r.charAt(i)); }
    private static List<String> csv(String line) {
        List<String> fields=new ArrayList<>();StringBuilder b=new StringBuilder();boolean quote=false;
        for(int i=0;i<line.length();i++) { char c=line.charAt(i);if(c=='"') { if(quote&&i+1<line.length()&&line.charAt(i+1)=='"') { b.append('"');i++; }else quote=!quote; }
            else if(c==','&&!quote) { fields.add(b.toString());b.setLength(0); }else b.append(c); }
        if(quote)throw new IllegalArgumentException("引用符");fields.add(b.toString());return fields;
    }
}
