#!/usr/bin/env bash
# tmux 세션에 입력창을 비우고 프롬프트를 주입한 뒤 Enter
# usage: tmux_send.sh <target-session> <message-file>
set -e
T="$1"
MSGFILE="$2"

# 1) 기존 입력 비우기 (백스페이스 120회)
for i in $(seq 1 120); do
  tmux send-keys -t "$T" BSpace
done
sleep 0.5

# 2) 메시지를 buffer에 적재 후 paste (멀티바이트/특수문자 안전)
MSG="$(cat "$MSGFILE")"
tmux set-buffer -- "$MSG"
tmux paste-buffer -t "$T"
sleep 0.3

# 3) 제출
tmux send-keys -t "$T" Enter
sleep 0.5
tmux capture-pane -t "$T" -p | tail -8
