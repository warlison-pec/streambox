# StreamBox Desktop (Windows e Linux)

Cliente Jellyfin para computador com o mesmo visual do app Android (preto e laranja). O player é o **libmpv**, que
toca o **arquivo original sem transcodificação**: MKV, HEVC/H.265 10 bits, AV1, VP9, DTS, TrueHD, E-AC3, legendas
ASS/SSA com estilo completo e PGS, com decodificação por hardware quando a placa suporta.

> Prova de conceito (v0.1.4). Já tem: login (usuário/senha e conexão rápida), início, bibliotecas, busca, detalhes com
> temporadas e episódios, retomada, progresso enviado ao Jellyfin, legendas externas, próximo episódio automático,
> busca com STRM Indexer ("Disponível para solicitar") e Seerr ("Pedir no Seerr"), Ajustes e TV ao vivo (canais do
> Jellyfin e "Minha lista" M3U + guia XMLTV, com troca de canal por ↑/↓ e PgUp/PgDn dentro do player).

## Duas edições, como no APK

| Edição | Login | Instalada como |
|---|---|---|
| `pecbox` (padrão) | servidor fixo, só usuário/senha; mostra o WhatsApp do administrador | `StreamBox` |
| `comunidade` | a pessoa digita o endereço do próprio Jellyfin | `StreamBox Comunidade` |

As duas podem ficar instaladas juntas (pacotes, pastas de dados e preferências separados). Os dados de cada edição
ficam em `build.gradle.kts` (mapa `edicoes`), equivalente aos *product flavors* do Android.

## Rodar durante o desenvolvimento

Precisa de JDK 17 e, no Linux, do libmpv (`sudo apt install libmpv2`).

```bash
./gradlew run                       # edição PecBox
./gradlew run -Pedicao=comunidade   # edição Comunidade
```

No Windows, `gradlew.bat run` baixa sozinho a `libmpv-2.dll` (do projeto shinchiro/mpv-winbuild-cmake, ~116 MB)
para `resources/windows/` na primeira vez. Para baixar de novo: `gradlew.bat baixarLibmpv -PforcarLibmpv`.

## Instaladores

**Linux (.deb, Ubuntu 22.04+ / Debian 12+):**

```bash
./empacotar-linux.sh                # as duas edições -> ../dist/desktop/*.deb
sudo apt install ./streambox_0.1.4-1_amd64.deb   # o apt instala o libmpv2 junto
```

**Windows (.msi):** o `.msi` só pode ser gerado no Windows. O workflow `.github/workflows/desktop-windows.yml` faz
isso no GitHub: aba *Actions* → *StreamBox Desktop (Windows)* → *Run workflow* (ou crie uma tag `desktop-v*`).
Ele baixa a `libmpv-2.dll` e publica os `.msi` das duas edições como artefatos.

## Como funciona

```
desktop/src/main/kotlin/com/pecbox/streambox/desktop/
├── Main.kt                 janela, cache de imagens, modo de diagnóstico --testar-mpv
├── data/                   Session (preferências), JellyfinApi
├── player/                 Mpv.kt (libmpv via JNA), PlayerController (sessão, progresso, próximo episódio)
└── ui/                     Theme, Components, Login, Início, Biblioteca/Busca, Detalhes, Shell (menu lateral)
```

- `Models.kt` e `Net.kt` **não são copiados**: o Gradle compila os mesmos arquivos de `app/src/main/java`.
- Ao tocar, o app pede o `PlaybackInfo` com um perfil que diz "toco tudo" (o servidor nunca oferece conversão) e
  abre `/Videos/{id}/stream?static=true` no mpv. Funciona também para itens `.strm`.
- O vídeo toca dentro da janela do app, ocupando a janela inteira como no Android (opção `wid` do mpv). Os
  controles são desenhados pelo próprio mpv com `src/main/resources/streambox_osc.lua`, no visual do StreamBox:
  voltar, título, barra laranja, ±10 s, áudio, legenda, tela cheia e, ao vivo, "AO VIVO" e troca de canal.
  Atalhos: `Esc`/`Backspace` voltam (Esc sai da tela cheia primeiro), espaço pausa, `F`/`F11`/duplo clique tela
  cheia, setas avançam/voltam (ao vivo ↑/↓ trocam de canal), `#` troca o áudio, `J` troca a legenda.
- No Linux com Wayland o app roda via XWayland e o mpv usa X11 para se encaixar na janela.
- Faixas preferidas: áudio e legenda em português; a legenda não liga sozinha quando o áudio já está em português.
- Log do player (para diagnóstico): `~/.local/share/<edição>/player.log` no Linux, `%APPDATA%\<edição>\player.log`
  no Windows. Quem quiser pode colocar `mpv.conf`, `input.conf` ou scripts na pasta `mpv/` ao lado.

Diagnóstico sem login (testa se o libmpv carrega e decodifica um arquivo):

```bash
"/opt/streambox/bin/StreamBox" --testar-mpv /caminho/do/video.mkv
```

## Próximos passos

1. Player: menus de áudio/legenda em lista (hoje os botões alternam), "Pular abertura" e "Próximo episódio".
2. Reorganizar em Kotlin Multiplatform (módulo `shared` com API e modelos para Android e desktop).
3. Troca de fonte .strm pelo indexador quando uma não toca, avisos e atualização automática.
4. Instalador menor (ProGuard e ícones só os usados).
