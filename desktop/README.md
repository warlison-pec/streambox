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
  voltar, título, barra laranja (com abertura/créditos marcados), ±10 s, legendas, áudio, episódios, tela cheia e,
  ao vivo, "AO VIVO", troca de canal e lista de canais por grupo.
- Mouse e teclado são da janela do app, não do mpv (embutido no Windows o mpv desativa a própria janela e não recebe
  cliques; no X11 ele roda com `input-cursor=no` para os cliques chegarem ao app). A área de vídeo repassa
  movimento, cliques e rolagem ao script por `script-message`; o script devolve as escolhas (`sb-escolha`) e o
  `PlayerController` monta os menus (faixas, aparência da legenda, legendas da internet, episódios, canais).
- Como no Android: menus de legenda (desligar, faixas, baixar da internet pelo Jellyfin, aparência: tamanho, cor e
  fundo) e de áudio em lista; a escolha é lembrada por série; "Pular abertura/resumo/créditos" e cartão de
  "Próximo episódio" com contagem de 10 s; número do canal pelo teclado no ao vivo.
- Mouse: clique pausa (ao vivo mostra/esconde os controles), duplo clique tela cheia, rolagem muda o volume (ou rola
  o menu aberto). Atalhos: `Esc` fecha o menu, sai da tela cheia e depois volta; `Backspace` volta; espaço pausa;
  `F`/`F11` tela cheia; ←/→ ±10 s; ↑/↓ ±1 min (ao vivo trocam de canal); `S`/`J` legendas; `A`/`#` áudio;
  `E` episódios; `C` canais; `M` sem som; nos menus, setas, Enter e PgUp/PgDn.
- No Linux com Wayland o app roda via XWayland e o mpv usa X11 para se encaixar na janela.
- Faixas preferidas: áudio e legenda em português; a legenda não liga sozinha quando o áudio já está em português.
- Log do player (para diagnóstico): `~/.local/share/<edição>/player.log` no Linux, `%APPDATA%\<edição>\player.log`
  no Windows. Quem quiser pode colocar `mpv.conf`, `input.conf` ou scripts na pasta `mpv/` ao lado.

Diagnóstico sem login (testa se o libmpv carrega e decodifica um arquivo):

```bash
"/opt/streambox/bin/StreamBox" --testar-mpv /caminho/do/video.mkv
```

## Próximos passos

1. Reorganizar em Kotlin Multiplatform (módulo `shared` com API e modelos para Android e desktop).
2. Troca de fonte .strm pelo indexador quando uma não toca, avisos e atualização automática.
3. Instalador menor (ProGuard e ícones só os usados).
