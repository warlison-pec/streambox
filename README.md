# StreamBox

Cliente Android leve para Jellyfin, com **um único APK para celular e TV** (Android TV, Fire TV, Google TV) e integração nativa com o [STRM Indexer](../strm-indexer) para **solicitar** filmes e séries direto da busca.

- Kotlin + Jetpack Compose, sem SDK pesado: OkHttp + kotlinx.serialization
- Player Media3/ExoPlayer: toca o arquivo original e, se o aparelho não suportar o formato, pede conversão HLS ao servidor
- Interface adaptativa: menu lateral e foco por controle remoto na TV, barra inferior no celular
- Tema preto e laranja

## Funções (v0.3)

| Tela | O que faz |
|---|---|
| Login | Endereço do servidor + usuário/senha do Jellyfin |
| Início | Destaque, Continuar assistindo, Próximos episódios, Adicionados recentemente por biblioteca |
| Filmes / Séries | Grade paginada com ordenação (A–Z, recentes, lançamento, avaliação) |
| Detalhes | Fundo, pôster, sinopse, Continuar / Do início, temporadas e episódios com progresso |
| Buscar | Resultados da biblioteca + **Disponível para solicitar** (STRM Indexer) com acompanhamento do pedido |
| Ajustes | Servidor, usuário, endereço da ponte de solicitações, limpar cache, sair |
| Player | Retomada, progresso enviado ao servidor a cada 10 s; botões **Legendas** (embutidas, do servidor, baixar da internet), **Áudio** e **Qualidade** (Original/1080p/720p/480p/360p); botão Menu do controle abre as opções |
| TV ao vivo | Canais do Jellyfin (Live TV) e **Minha lista**: M3U + guia EPG/XMLTV opcionais, com grupos, "Agora/Depois" e progresso |

## Compilar (no servidor, via Docker)

```bash
./build.sh
```

- A primeira execução baixa o Android SDK para dentro de uma imagem Docker (alguns minutos). As próximas usam cache.
- Na primeira vez também é criada a chave de assinatura `keystore/release.jks`. **Guarde esse arquivo**: sem ele não é possível atualizar o app instalado (o Android exige a mesma assinatura).
- Senha da chave: `streambox` por padrão, ou `KS_PASS=suasenha ./build.sh`.
- Saída:
  - `dist/StreamBox-<versão>.apk`: versão com servidor fixo (ex.: PecBox);
  - `dist/comunidade/StreamBox-Comunidade-<versão>.apk`: versão aberta.
- Para compilar só uma delas: `./build.sh pecbox` ou `./build.sh comunidade`.

## Instalar

- **Celular:** copie o APK e abra (permita "instalar apps desconhecidos").
- **Fire TV / Android TV:** use o app *Downloader* com o link do APK, ou `adb install -r dist/StreamBox-<versão>.apk`.

## Duas versões do mesmo app

O projeto gera dois APKs (*product flavors* em `app/build.gradle.kts`):

| Versão | Para quem | Login |
|---|---|---|
| `pecbox` | usuários de um servidor específico | servidor fixo; pede só usuário/senha ou código de conexão rápida, e mostra o contato do administrador |
| `comunidade` | qualquer pessoa com Jellyfin | a pessoa digita o endereço do próprio servidor |

As duas podem ficar instaladas juntas: a da comunidade usa o pacote `com.pecbox.streambox.comunidade`.

Para montar a versão do **seu** servidor, edite o bloco `create("pecbox")`:

```kotlin
buildConfigField("String", "DEFAULT_SERVER", "\"https://jellyfin.seudominio.com\"")
buildConfigField("String", "DEFAULT_INDEXER", "\"\"")      // ponte do STRM Indexer (opcional)
buildConfigField("String", "DEFAULT_SEERR", "\"\"")        // Seerr/Jellyseerr (opcional)
buildConfigField("boolean", "LOCK_SERVER", "true")
buildConfigField("String", "ADMIN_WHATSAPP", "\"55DDNUMERO\"") // "" esconde o contato
buildConfigField("String", "ADMIN_WHATSAPP_LABEL", "\"(DD) 9XXXX-XXXX\"")
```

No endereço do servidor, a versão da comunidade aceita:
- `https://...` ou `http://...`;
- só `ip:porta`: tenta https e depois http;
- o `/api` do StreamBox Web.

## Estrutura

```
app/src/main/java/com/pecbox/streambox/
├── App.kt, MainActivity.kt
├── data/      JellyfinApi, IndexerApi, Session, Models
├── ui/        AppShell (navegação), Loader (cache), theme/, components/, screens/
└── player/    PlayerActivity (Media3)
```

## Próximos passos

- Quick Connect (login por código de 6 dígitos)
- Pular abertura (Intro Skipper) e próximo episódio automático
- Favoritos / Minha lista
- Downloads offline no celular
