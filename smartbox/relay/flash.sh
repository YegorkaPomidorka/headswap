#!/bin/bash
# Заливка .swu в бокс по протоколу панели: downfile.cgi (куски) -> submition.cgi -> reboot.cgi
set -u
SWU="$1"; NAME="$2"; MD5="$3"
HOST="http://192.168.1.101"
W=/c/Users/POMIDO~1/AppData/Local/Temp/claude/c7b89d7028e1864f/56e5793a-59b8-41ed-8290-381d5d4cdae5/scratchpad
cd "$W"; rm -rf chunks; mkdir chunks; cd chunks
split -b 10240 "$SWU" c_
files=(c_*); n=${#files[@]}
echo "кусков: $n"
last=""
for i in "${!files[@]}"; do
  f=${files[$i]}
  if   [ "$i" -eq 0 ]; then fn="$NAME.0"
  elif [ "$i" -eq $((n-1)) ]; then fn="$NAME:$MD5.2"
  else fn="$NAME.1"; fi
  resp=$(curl -s -m 20 -F "chunk=@$f;filename=$fn" "$HOST/cgi-bin/downfile.cgi")
  last="$resp"
  if ! echo "$resp" | grep -qi success; then
    echo "СТОП на куске $((i+1))/$n ($fn): $resp"; exit 1
  fi
  if [ $(( (i+1) % 30 )) -eq 0 ] || [ "$i" -eq $((n-1)) ]; then echo "[$((i+1))/$n] ok"; fi
done
echo "=== ответ на последний кусок ==="; echo "$last"
if ! echo "$last" | grep -qi "file check success"; then
  echo "НЕТ 'file check success' — submition НЕ зову, прошивка не применена"; exit 2
fi
echo "=== submition.cgi (применение) ==="
sub=$(curl -s -m 20 "$HOST/cgi-bin/submition.cgi?filename=$NAME&"); echo "$sub"
if ! echo "$sub" | grep -qi "success download"; then
  echo "submition без 'success download' — ребут НЕ зову"; exit 3
fi
echo "=== reboot.cgi ==="
curl -s -m 10 "$HOST/cgi-bin/reboot.cgi"; echo "  [ребут отправлен]"
