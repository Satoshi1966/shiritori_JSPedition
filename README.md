# ジャンルしりとり Servlet JSP版

プロジェクト名はGenreShiritoriWebである。Java 17、Tomcat 9、javax.servletで動作する。
Jakarta Servletは使用しない。Eclipse 2023のEnterprise Java and Web Developers版、
またはWeb Tools Platformを導入したEclipseを使用する。

## 最初の実行

1. ZIPを展開する。
2. Eclipseの［ファイル］→［インポート］→［一般］→［既存プロジェクトをワークスペースへ］を選択する。
3. ルートディレクトリーに展開したGenreShiritoriWebを指定し、プロジェクトを取り込む。
4. プロジェクトのJREシステム・ライブラリーをJavaSE-17に指定する。
5. ［ウィンドウ］→［設定］→［サーバー］→［ランタイム環境］でTomcat 9を登録する。
   Tomcatの実行用JREもJDK 17を選択する。
6. プロジェクトの［プロパティー］→［ターゲット・ランタイム］でTomcat 9を選択する。
   プロジェクト・ファセットはJava 17、動的Webモジュール4.0とする。
7. ［プロジェクト］→［クリーン］から再ビルドする。
8. GenreShiritoriWebを右クリックし、［実行］→［サーバーで実行］を選択する。
   Tomcat 9を選択して完了する。index.jspを右クリックしてサーバーで実行しても起動できる。

標準のURLは http://localhost:8080/GenreShiritoriWeb/ である。
直接指定する場合は http://localhost:8080/GenreShiritoriWeb/index.jsp を開く。
index.jspは初期表示時にServletへ移動し、必要なデータを用意してからメニューを表示する。
URL中のポート番号はTomcatの設定に従う。

［サーバーで実行］がない場合は、EclipseのWeb開発機能の有無を確認する。
404の場合はServersビューでGenreShiritoriWebがTomcatへ追加されているか、
コンテキスト・ルートがGenreShiritoriWebかを確認する。
Tomcat 10・11ではなく、javax.servletを提供するTomcat 9を選択する。

## 少数ファイルの構成

| ファイル | 役割 |
|---|---|
| src/main/java/shiritori/web/GameServlet.java | 画面遷移、HTTPセッション、入力検査、保存の確定 |
| src/main/java/shiritori/web/GameData.java | データ型、辞書、しりとり判定、CPU、JSON保存 |
| src/main/webapp/index.jsp | メニュー、プレイヤー管理、戦績、累積履歴 |
| src/main/webapp/WEB-INF/game.jsp | 対戦、結果、対戦履歴 |
| src/main/webapp/css/style.css | 全JSP共通のCSS |
| src/main/webapp/js/game.js | 残り10秒の通知、時間切れ表示、確認メッセージ |
| src/main/webapp/images/logo.svg | 共通ロゴ。画像の追加先もimagesである |
| src/main/webapp/WEB-INF/words.csv | 初期辞書270件 |
| src/main/webapp/WEB-INF/web.xml | Servletの割当、index.jspの開始設定 |
| src/main/webapp/WEB-INF/lib/gson-2.13.2.jar | JSON保存ライブラリ |

主要Javaソースは2ファイル、JSPは2ファイルである。
関連データ型はGameDataの内部クラスPlayer、Word、Move、Match、Score、Databaseにまとめた。
Java側のパッケージ名はshiritori.webである。
ファイル数を抑えるため、初学者向けに通常分離するデータ型と保存処理を1ファイルへまとめている。
画面の入力はServlet、表示はJSP、対戦の処理はGameDataへ分けている。

providedのServlet/JSP API JARはEclipseのコンパイル用であり、WARには含めない。
実行時はTomcat 9側のAPIを使用する。追加ダウンロードやMavenは不要である。

## フォームから辞書へ追加する

Web 1.1では、メニュー下部の「言葉を辞書に追加」から登録できる。
ジャンル、正式名称、読みを入力する。昭和ジャンルだけ曲名・歌手名の種別も選ぶ。
読みはひらがな・カタカナと長音で指定し、英数字・記号・空白を含めない。
別名・略称は任意であり、複数ある場合は | で区切る（10件まで）。
同じジャンル・種別の同一名称や登録済み別名は重複として拒否する。
曲名と歌手名に同じ名称がある場合は別項目にでき、回答時に候補を選択する。
追加語はプレイヤー共通でCPUの候補にも反映し、JSONへ保存する。上限は5000件である。
登録しただけではゲームの回答や累積履歴に記録しない。

対戦中は「未登録の言葉を辞書に追加する」からフォームを開ける。
直前に入力した言葉と対戦ジャンルを初期表示する。登録中も対戦時間は進むため、
時間が必要なら先に「保存してメニューへ」を使い、登録後に対戦を再開する。
追加語の編集・削除、実在性の自動判定は今回の追加範囲外である。
Web上へ公開する手順と永続ディスクの設定はDEPLOY.mdを参照する。

## 操作と引き継いだルール

メニューでプレイヤーを登録または選択し、ジャンルと難易度を選んで開始する。
映画、歴史上の人物、魚、日本のロボットアニメ、昭和の曲名・歌手名の全5ジャンルである。
初期辞書はCUI版と同じ270件であり、フォームで追加できる。ジャンル縛りなしやヒント機能はまだ追加していない。
正式名、かな読み、登録された別名を受け付ける。略称の接続は正式名の読みで判定する。
同じ入力に複数候補がある場合は候補を選択する。
「ん」で終わる有効回答は負けであり、累積履歴へ記録する。
小書き文字は通常の大きさに、末尾の長音は直前の文字に置き換えて判定する。濁点は区別する。

やさしいは再使用できる。ふつうは対戦内の全使用語と、自分の過去回答を再使用できない。
やさしいでの回答も個人の累積履歴に保存する。別プレイヤーの過去回答は影響しない。
CPUの過去回答は個人の累積履歴へ登録しない。

パスはボタンでいつでも使える。回答欄の/pass・pass・パスも有効である。
開始文字を保ってCPUへ回答権を渡し、パス自体は累積回答と回答数に含めない。
初手のパスではCPUが自由に回答する。パス回数には上限を設けない。
ギブアップは確認後に敗北となる。
入力ミスや未登録ワードは再入力である。やさしいでは対戦が長引く場合がある。

## タイマー

手番は30秒である。残り10秒の通知を一度だけ表示し、毎秒の表示で入力欄を動かさない。
30秒後は回答欄を隠し、パスまたはギブアップを選択する。
通知用の領域をあらかじめ確保し、通知で入力欄の位置がずれないようにしている。
期限はサーバーで保持するため、ページ更新、入力ミス、別タブ、JavaScriptの無効化で延長しない。
新しい手番と保存からの再開では30秒から開始する。
JavaScriptが無効でもサーバーは期限後の回答を拒否し、パスとギブアップのボタンは使用できる。
実行環境の時刻を基準とし、回答がサーバーへ到着した時点で受付を判定する。

Servlet内でThread.sleep()は使用しない。画面の通知はJavaScriptで行う。
動作検証で時間を短縮する場合だけ、TomcatのVM引数に-Dshiritori.thinkingSeconds=3などを指定できる。
通常は指定不要である。

## 保存先

JSONファイル1つで追加語・プレイヤー・累積回答・中断セーブ・戦績を保存する。
保存先は ${catalina.base}/genre-shiritori-data/web-data.json である。
EclipseのTomcatではcatalina.baseがワークスペース内の一時サーバー領域になる場合がある。
Tomcatの起動ログまたは実行構成でcatalina.baseを確認する。
保存先を固定する場合はTomcatのVM引数へ次のように指定する。

-Dshiritori.dataDir=C:/shiritori-data

フォルダーは自動作成する。TomcatのServersビューでサーバーをダブルクリックし、
［起動構成を開く］→［引数］→［VM引数］へ設定する。空白のあるパスは引用符で囲む。
Webアプリの配備フォルダー外へ保存するため、WARの入替でデータを消さない構成である。
データを残す場合は保存フォルダーをバックアップする。

有効な回答と戦績は自動保存する。対戦の続きは「保存してメニューへ」で保存する。
プレイヤーごとに1スロットであり、上書きにはチェックを要求する。
再開時に古い累積回答へ巻き戻さない。完了済みの対戦を再開することはできない。
保存に失敗した操作はメモリー上でも取り消す。ファイル不在は新規状態として開始し、
破損JSONは上書きせず起動エラーとする。

名前変更でもIDと履歴は維持される。同じ名前で新規登録すれば別のプレイヤーIDとなる。
履歴リセットは指定プレイヤーの指定ジャンルだけを対象とし、そのジャンルの中断セーブも削除する。
戦績と他ジャンルの履歴は残る。進行中の対戦は先に保存または終了する。

CUI版の.datファイルはWeb版JSONへ自動移行しない。辞書CSVは共通の形式である。
辞書を編集すると保存時のハッシュと異なるため、旧対戦の再開を拒否する。
ワードIDを維持した累積回答履歴は新規対戦でも使用できる。
このアプリはプレイヤー選択方式の学習用であり、パスワード認証は設けていない。
Webアプリは1インスタンスで使用する。同じ保存先へ複数のTomcatを同時起動しない。

## WARで実行する場合

同梱GenreShiritoriWeb.warを停止中のTomcat 9のwebappsへ配置し、Tomcatを起動する。
ブラウザーで /GenreShiritoriWeb/ を開く。Eclipseへ取り込まなくても使用できる。
再ビルドはJDK 17のPATHを設定し、Windowsはbuild-war.bat、macOS/Linuxはsh build-war.shを使う。
Javaソースを編集した後はEclipseで再配備するか、WARを再作成する。

## 検証

TEST_REPORT.mdとtests/model-test-output.txtを参照する。
テストで登録したプレイヤーや履歴は配布物へ含めない。
Eclipse 2023のGUIそのものは本環境では実行していない。Tomcat 9上でServletとJSPを実行して検証する。

参考となる公式仕様:
- https://tomcat.apache.org/tomcat-9.0-doc/servletapi/index.html
- https://tomcat.apache.org/migration-9.html

外部ライブラリとコンパイル用APIのライセンスはTHIRD_PARTY_NOTICES.txtを参照する。
