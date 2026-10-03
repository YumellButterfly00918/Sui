#!/sbin/sh
MODDIR=${0%/*}
MODULES=$(dirname "$MODDIR")

uninstall() {
  chmod 700 "$MODDIR"/bin/uninstall
  "$MODDIR"/bin/uninstall "$MODDIR"
  rm -rf "/data/adb/sui"
  rm -f /data/system/sui/sui.db /data/system/sui/sui.db-wal /data/system/sui/sui.db-shm /data/system/sui/sui.db-journal
}

if [ -d "$MODULES/riru_sui" ] && [ -d "$MODULES/zygisk_sui" ]; then
  if [ -f "$MODULES/riru_sui/remove" ] && [ -f "$MODULES/zygisk_sui/remove" ]; then
    uninstall
  fi
else
  uninstall
fi
