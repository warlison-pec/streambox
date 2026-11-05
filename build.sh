#!/usr/bin/env bash
# Compila o APK de release dentro do Docker (não precisa de Android Studio).
# Uso: ./build.sh              -> as duas versões (pecbox e comunidade)
#      ./build.sh pecbox       -> só a versão com o servidor PecBox fixo
#      ./build.sh comunidade   -> só a versão aberta (cada um configura o próprio Jellyfin)
#      KS_PASS=minhasenha ./build.sh   (senha padrão do keystore: streambox)
set -euo pipefail
cd "$(dirname "$0")"
KS_PASS="${KS_PASS:-streambox}"
FLAVOR="${1:-}"
case "$FLAVOR" in
  "") TASK=assembleRelease ;;
  pecbox) TASK=assemblePecboxRelease ;;
  comunidade) TASK=assembleComunidadeRelease ;;
  *) echo "Versão desconhecida: $FLAVOR (use pecbox ou comunidade)"; exit 1 ;;
esac
IMAGE=streambox-build

echo ">> Preparando ambiente de build (só demora na primeira vez)…"
docker build -t "$IMAGE" docker

if [ ! -f keystore/release.jks ]; then
  echo ">> Criando chave de assinatura em keystore/release.jks (guarde este arquivo!)"
  mkdir -p keystore
  docker run --rm -v "$PWD/keystore:/k" "$IMAGE" keytool -genkeypair -v \
    -keystore /k/release.jks -alias streambox -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" -dname "CN=StreamBox, O=PecBox, C=BR" >/dev/null
fi

echo ">> Compilando…"
docker run --rm \
  -v "$PWD:/src" \
  -v streambox-gradle-cache:/home/gradle/.gradle \
  -e KS_PASS="$KS_PASS" \
  "$IMAGE" gradle --no-daemon --console=plain "$TASK"

mkdir -p dist dist/comunidade
VERSION=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
OUT=app/build/outputs/apk
# PecBox fica na raiz de dist/ (aparece primeiro na página de downloads); a da comunidade numa subpasta
[ -f "$OUT/pecbox/release/app-pecbox-release.apk" ] && [ "$FLAVOR" != comunidade ] && \
  cp "$OUT/pecbox/release/app-pecbox-release.apk" "dist/StreamBox-$VERSION.apk" && echo ">> dist/StreamBox-$VERSION.apk"
[ -f "$OUT/comunidade/release/app-comunidade-release.apk" ] && [ "$FLAVOR" != pecbox ] && \
  cp "$OUT/comunidade/release/app-comunidade-release.apk" "dist/comunidade/StreamBox-Comunidade-$VERSION.apk" && echo ">> dist/comunidade/StreamBox-Comunidade-$VERSION.apk"
# Arquivos criados pelo container ficam com dono root; devolve para o usuário atual.
docker run --rm -v "$PWD:/src" "$IMAGE" chown -R "$(id -u):$(id -g)" /src/app/build /src/.gradle /src/dist /src/keystore 2>/dev/null || true
echo ">> Pronto."
