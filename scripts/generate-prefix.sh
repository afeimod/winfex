[[ -z $1 ]] && { echo "没有指定目录"; exit 1; }
targetPfx="$1"
dir=(
  home
  opt
  usr/etc
  usr/bin
  usr/lib
  usr/share
  usr/libexec
  usr/include
  usr/games
  usr/src
  usr/sbin
  usr/tmp
  usr/var/run
)
for i in ${dir[@]}; do
  mkdir -p "${targetPfx}/$i"
done

cd "${targetPfx}"
ln -sf usr/bin
ln -sf usr/lib
ln -sf usr/etc
ln -sf usr/tmp
ln -sf usr/var
cd -