package shiritori.web;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import shiritori.web.GameData.*;

/** 1つのServletで画面遷移を扱い、POST後はリダイレクトして再送信を防ぐ。 */
public class GameServlet extends HttpServlet {
    private static final long serialVersionUID=1L;
    public static final String VERSION="Web 1.1 / Tomcat 9 / Java 17";
    private GameData data;
    @Override public void init() throws ServletException {
        String directory=System.getProperty("shiritori.dataDir");
        if(directory==null||directory.isBlank())directory=Path.of(System.getProperty("catalina.base",System.getProperty("user.home")),"genre-shiritori-data").toString();
        try { data=new GameData(getServletContext().getResourceAsStream("/WEB-INF/words.csv"),Path.of(directory).toAbsolutePath()); }
        catch(IOException|RuntimeException e) { throw new ServletException("データまたは辞書を読み込めない。保存先: "+directory,e); }
    }
    private void headers(HttpServletResponse res) {
        res.setCharacterEncoding("UTF-8");res.setHeader("Cache-Control","no-store");
        res.setHeader("X-Content-Type-Options","nosniff");res.setHeader("Referrer-Policy","same-origin");
        res.setHeader("Content-Security-Policy","default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'");
    }
    private String token(HttpSession session) {
        String csrf=(String)session.getAttribute("csrf");if(csrf==null) { csrf=UUID.randomUUID().toString();session.setAttribute("csrf",csrf); }return csrf;
    }
    private Player player(HttpSession s) { return data.player((String)s.getAttribute("playerId")); }
    private Match match(HttpSession s) { return (Match)s.getAttribute("match"); }
    @Override protected void doGet(HttpServletRequest req,HttpServletResponse res) throws ServletException,IOException {
        headers(res);HttpSession session=req.getSession();
        synchronized(data) { synchronized(session) {
            token(session);Match m=match(session);Player p=player(session);
            if("status".equals(req.getParameter("action"))) {
                res.setContentType("application/json");
                if(m==null||p==null||!Objects.equals(m.id,req.getParameter("matchId"))) { res.setStatus(409);res.getWriter().write("{\"error\":\"対戦が切り替わっている\"}");return; }
                res.getWriter().write(data.gson.toJson(Map.of("remainingMs",data.remainingMillis(m),"turn",m.turn,"finished",m.finished)));return;
            }
            req.setAttribute("viewReady",true);req.setAttribute("data",data);req.setAttribute("player",p);req.setAttribute("match",m);
            Object flash=session.getAttribute("flash");session.removeAttribute("flash");req.setAttribute("message",flash);
            if(m!=null&&p!=null&&!"menu".equals(req.getParameter("screen")))req.getRequestDispatcher("/WEB-INF/game.jsp").forward(req,res);
            else req.getRequestDispatcher("/index.jsp").forward(req,res);
        }}
    }
    @Override protected void doPost(HttpServletRequest req,HttpServletResponse res) throws ServletException,IOException {
        long receivedAt=System.currentTimeMillis();req.setCharacterEncoding("UTF-8");headers(res);HttpSession session=req.getSession();
        synchronized(data) { synchronized(session) {
            if(!Objects.equals(token(session),req.getParameter("csrf"))) { res.sendError(403,"操作の有効期限が切れている。メニューから開き直す。");return; }
            String dbBefore=data.gson.toJson(data.db);Match before=data.copy(match(session));String playerBefore=(String)session.getAttribute("playerId");
            String action=param(req,"action");boolean menu=false;
            try {
                Player p=player(session);Match m=match(session);
                if(Set.of("answer","pass","giveup","save").contains(action)) {
                    require(p!=null&&m!=null&&!m.finished,"進行中の対戦がない。");
                    require(Objects.equals(m.playerId,p.id)&&Objects.equals(req.getParameter("matchId"),m.id)&&Objects.equals(req.getParameter("turn"),String.valueOf(m.turn)),"古い画面からの操作である。最新の画面を確認する。");
                }
                switch(action) {
                    case "addWord" -> {
                        String genre=param(req,"genre");
                        String type=switch(genre){case "MOVIE","ROBOT_ANIME"->"WORK";case "HISTORY"->"PERSON";case "FISH"->"FISH";default->param(req,"entryType");};
                        Word added=data.addWord(genre,type,param(req,"name"),param(req,"reading"),param(req,"aliases"));
                        menu=true;message(session,"「"+added.name+"（"+added.reading+"）」を辞書に追加した。CPUも使用できる。");
                    }
                    case "create" -> {
                        require(m==null||m.finished,"プレイヤーを変更する前に対戦を保存または終了する。");
                        String name=param(req,"name");require(!name.isBlank()&&name.length()<=30,"名前は1～30文字で入力する。");
                        Player created=new Player();created.name=name;data.db.players.add(created);session.setAttribute("playerId",created.id);session.removeAttribute("match");menu=true;message(session,"プレイヤーを登録した。");
                    }
                    case "select" -> {
                        require(m==null||m.finished,"対戦を保存または終了してからプレイヤーを切り替える。");
                        Player selected=data.player(param(req,"playerId"));require(selected!=null,"プレイヤーを選択する。");
                        session.setAttribute("playerId",selected.id);session.removeAttribute("match");menu=true;
                    }
                    case "new" -> {
                        require(p!=null,"プレイヤーを選択する。");require(m==null||m.finished,"進行中の対戦を先に保存または終了する。");
                        String genre=param(req,"genre"),difficulty=param(req,"difficulty");
                        require(GameData.GENRES.containsKey(genre)&&Set.of("EASY","NORMAL").contains(difficulty),"ジャンルと難易度を選択する。");
                        require(!difficulty.equals("NORMAL")||data.remaining(p,genre)>0,"未回答のワードがない。やさしい・別ジャンル・履歴リセットを選択する。");
                        Match started=new Match();started.playerId=p.id;started.genre=genre;started.difficulty=difficulty;started.dictionaryVersion=data.dictionaryVersion;data.startClock(started);session.setAttribute("match",started);
                    }
                    case "answer" -> {
                        if(receivedAt>=m.deadline) { message(session,"時間切れである。パスまたはギブアップを選択する。");break; }
                        String input=param(req,"word");require(!input.isBlank()&&input.length()<=200,"回答を1～200文字で入力する。");
                        if(Set.of("/pass","pass","パス").contains(input.toLowerCase(Locale.ROOT))) { data.pass(m);clearAnswer(session);break; }
                        if(Set.of("/giveup","ギブアップ").contains(input.toLowerCase(Locale.ROOT))) { data.finish(m,false,"プレイヤーがギブアップした。");clearAnswer(session);break; }
                        List<Word> found=data.find(m.genre,input);session.setAttribute("lastInput",input);
                        require(!found.isEmpty(),"辞書に登録されていない。かな表記または別の言葉で入力する。");
                        String chosen=param(req,"chosenId");Word word=found.get(0);
                        if(found.size()>1) {
                            if(chosen.isBlank()) { session.setAttribute("candidates",found);message(session,"同名の候補がある。候補を選択して回答する。");break; }
                            word=null;for(Word w:found)if(w.id.equals(chosen))word=w;require(word!=null,"候補を選択し直す。");
                        }
                        data.answer(m,p,word);clearAnswer(session);
                    }
                    case "pass" -> { data.pass(m);clearAnswer(session); }
                    case "giveup" -> { data.finish(m,false,"プレイヤーがギブアップした。");clearAnswer(session); }
                    case "save" -> {
                        require(!data.db.saves.containsKey(p.id)||"yes".equals(req.getParameter("overwrite")),"既存セーブの上書きを確認する。");
                        m.savedAt=java.time.LocalDateTime.now().toString();Match saved=data.copy(m);saved.deadline=0;data.db.saves.put(p.id,saved);session.removeAttribute("match");clearAnswer(session);message(session,"対戦を保存した。");menu=true;
                    }
                    case "resume" -> {
                        require(p!=null,"プレイヤーを選択する。");require(m==null||m.finished,"進行中の対戦を先に保存または終了する。");
                        Match saved=data.copy(data.db.saves.get(p.id));data.validate(saved,p);data.startClock(saved);session.setAttribute("match",saved);clearAnswer(session);
                    }
                    case "rename" -> {
                        require(p!=null&&(m==null||m.finished),"対戦を保存または終了してから名前を変更する。");String name=param(req,"name");require(!name.isBlank()&&name.length()<=30,"名前は1～30文字で入力する。");p.name=name;menu=true;message(session,"名前を変更した。IDと履歴は維持される。");
                    }
                    case "reset" -> {
                        require(p!=null&&(m==null||m.finished),"対戦を保存または終了してからリセットする。");String genre=param(req,"genre");
                        require(GameData.GENRES.containsKey(genre)&&"yes".equals(req.getParameter("confirm")),"対象ジャンルを選び、削除確認にチェックする。");
                        p.answered.remove(genre);Match saved=data.db.saves.get(p.id);if(saved!=null&&saved.genre.equals(genre))data.db.saves.remove(p.id);menu=true;message(session,"指定ジャンルの累積履歴と中断セーブをリセットした。戦績は残る。");
                    }
                    default -> throw new IllegalArgumentException("不明な操作である。");
                }
                data.persist();
            }catch(IllegalArgumentException e) { message(session,e.getMessage());menu=Set.of("create","select","new","resume","rename","reset","addWord").contains(action); }
            catch(IOException|RuntimeException e) {
                data.db=data.gson.fromJson(dbBefore,Database.class);data.refreshWords();session.setAttribute("match",before);session.setAttribute("playerId",playerBefore);
                message(session,"保存処理に失敗したため操作を取り消した。再試行する。");getServletContext().log("保存失敗",e);
            }
            res.setStatus(HttpServletResponse.SC_SEE_OTHER);res.setHeader("Location",req.getContextPath()+"/game"+(menu?"?screen=menu":""));
        }}
    }
    private static String param(HttpServletRequest r,String key) { String s=r.getParameter(key);return s==null?"":s.strip(); }
    private static void require(boolean condition,String message) { if(!condition)throw new IllegalArgumentException(message); }
    private static void message(HttpSession s,String text) { s.setAttribute("flash",text); }
    private static void clearAnswer(HttpSession s) { s.removeAttribute("lastInput");s.removeAttribute("candidates"); }
    public static String html(Object value) {
        return value==null?"":String.valueOf(value).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
}
