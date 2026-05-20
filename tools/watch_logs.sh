#!/bin/bash
# HudImpulse – recebe logs em tempo real via ntfy.sh
# Uso: bash tools/watch_logs.sh
# Requer: curl e python3 (já instalados no Mac)

TOPIC="hud-impulse-raf28"
echo "============================================"
echo "  HudImpulse Log Monitor"
echo "  Aguardando logs do carro..."
echo "  Ctrl+C para parar"
echo "============================================"

curl -s "https://ntfy.sh/${TOPIC}/json" | while IFS= read -r line; do
  MSG=$(echo "$line" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('message',''))" 2>/dev/null)
  if [ -n "$MSG" ]; then
    echo "[$(date '+%H:%M:%S')] $MSG"
  fi
done
