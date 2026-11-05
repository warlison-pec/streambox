#!/bin/sh
# Remove ícones/banners antigos que podem ter sobrado de versões anteriores.
# Rode na pasta do projeto (onde fica a pasta app/) depois de extrair o zip.
cd "$(dirname "$0")"
R=app/src/main/res
rm -fv $R/drawable/banner.xml $R/drawable/banner.png $R/drawable/ic_launcher.xml $R/drawable/ic_launcher_fg.xml \
       $R/drawable*/banner.png $R/drawable*/tv_banner.png
rm -rfv $R/drawable-anydpi-v26
echo "Pronto. Agora compile o APK normalmente."
