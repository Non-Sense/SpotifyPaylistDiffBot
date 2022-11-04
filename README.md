# SpotifyPlaylistDiffBot  
深夜のノリで半日くらいで作ったdiscord bot  
Spotifyのプレイリストの差分を定期的にDiscordのチャンネルにお知らせする。  

## できること  
1. SpotifyのAPIをぶっ叩いてプレイリストを取得  
2. データベースに突っ込む
3. 前回取得分と差分を取って新規追加分の曲情報をDiscordのチャットに投げる  
4. ついでにタイトルで検索をかけて曲が重複している可能性をお知らせ  

## まだできないこと  
複数のプレイリストの監視。めんどくさいので

## 使い方
`java -jar SpotifyPaylistDiffBot.jar "データベースのファイルパス" --playlistId "spotifyのプレイリストID" --discordToken "Discordのbotトークン" --spotifyClientId "spotifyクライアントID" --spotifyClientSecret "spotifyクライアントシークレット"`  

データベースはSQLiteです。ファイルパスは適当に指定してください。

`--interval`オプションを指定することで取得周期を指定できます。(分単位, デフォルト10分)  

jar起動後にプレイリストの初回取得を行います。 
初回取得中もBOTがオンラインとなりますが、このタイミングでテキストチャンネルにBOTを追加するとプレイリストが既に曲が追加されている場合にとんでもない量のメッセージが飛ぶことになります。  
コンソール上に`Done!!!!`と表示されたことを確認してから、通知を投げたいDiscordテキストチャンネルで**BOTにメンションで**`!here`と入力してください。  
通知解除する場合は同様にBOTにメンションで`!bye`を送信します。
