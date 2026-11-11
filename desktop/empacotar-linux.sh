#!/usr/bin/env bash
# Gera os instaladores .deb (Ubuntu/Debian) das duas edições do StreamBox Desktop.
# Uso: ./empacotar-linux.sh              -> pecbox e comunidade
#      ./empacotar-linux.sh comunidade   -> só uma
# Saída: ../dist/desktop/*.deb
set -euo pipefail
cd "$(dirname "$0")"
EDICOES="${1:-pecbox comunidade}"
OUT=../dist/desktop
mkdir -p "$OUT"

for ed in $EDICOES; do
  echo ">> Empacotando edição $ed…"
  ./gradlew --console=plain -q -Pedicao="$ed" packageDeb
  deb=$(ls -t build/"$ed"/compose/binaries/main/deb/*.deb | head -1)

  # O Compose não deixa declarar dependências do .deb: acrescenta o libmpv2 (o player) no control.
  tmp=$(mktemp -d)
  fakeroot -- bash -c "
    dpkg-deb -R '$deb' '$tmp/pkg'
    sed -i -E 's/^Depends: (.*)$/Depends: \1, libmpv2 | libmpv1/' '$tmp/pkg/DEBIAN/control'
    grep -q '^Depends:' '$tmp/pkg/DEBIAN/control' || echo 'Depends: libmpv2 | libmpv1' >> '$tmp/pkg/DEBIAN/control'
    # O jpackage copia os nomes de pacote da máquina do build (Ubuntu 24.04: libasound2t64, libglib2.0-0t64...).
    # Ubuntu 22.04, Mint 21 e Debian 12 usam os nomes antigos: aceita os dois.
    sed -i -E '/^Depends:/ s/([a-z0-9.+-]+)t64\b/\1t64 | \1/g' '$tmp/pkg/DEBIAN/control'
    sed -i -E '/^Depends:/ s/libjpeg-turbo8\b/libjpeg-turbo8 | libjpeg62-turbo/' '$tmp/pkg/DEBIAN/control'
    # Atalho no menu: se o xdg-desktop-menu falhar (sem ambiente gráfico), não deixa a instalação pela metade
    for f in postinst prerm; do
      [ -f '$tmp/pkg/DEBIAN/'\$f ] && sed -i -E 's/^(xdg-desktop-menu .*)$/\1 || true/' '$tmp/pkg/DEBIAN/'\$f
    done
    dpkg-deb -Zxz -b '$tmp/pkg' '$OUT/$(basename "$deb")' >/dev/null
  "
  rm -rf "$tmp"
  echo ">> $OUT/$(basename "$deb")"
done
echo ">> Instalar: sudo apt install ./arquivo.deb   (o apt instala o libmpv2 junto)"
