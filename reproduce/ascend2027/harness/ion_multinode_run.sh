#!/usr/bin/env bash
# Runs inside the ION container with /work = generated dir. Starts every node, receives at each
# destination, injects per inject.tsv at wall offsets from START (epoch), logs deliveries.
set -u
cd /work; export ION_NODE_LIST_DIR=/work; rm -f ion_nodes; killm >/dev/null 2>&1
T_START=$(date +%s)
for n in $(cat nodes.txt); do (cd $n && bash ionstart >/dev/null 2>&1) || echo "start fail $n"; done
echo "started $(wc -l < nodes.txt) nodes in $(( $(date +%s) - T_START )) s; START=$START now=$(date +%s)"
echo "start_slack_s $(( START - $(date +%s) ))" > /work/health.txt
mkdir -p /work/rx
for d in $(cut -f3 inject.tsv | sort -u); do (cd $d && bprecvfile ipn:$d.1 > /work/rx/recv_$d.log 2>&1 &); done
# payload files
while IFS=$'\t' read -r off src dst bytes ttl cos id; do
  f=/work/payload_$id; { echo "$id"; head -c "$bytes" </dev/zero | tr '\0' 'x'; } > $f
done < inject.tsv
while IFS=$'\t' read -r off src dst bytes ttl cos id; do
  # printf, not print: awk's default %.6g format rounds epoch seconds by up to thousands of seconds.
  w=$(awk -v s="$START" -v o="$off" -v n="$(date +%s.%N)" 'BEGIN{d=s+o-n; printf "%.3f", (d>0)?d:0}'); sleep "$w"
  # One send at a time per node: ION lets one process hold an endpoint, and a send that fails
  # mid-transaction can leave the node's SDR unusable. Sends queue on the node's lock instead.
  (cd $src && { flock /work/$src/.sendlock bpsendfile ipn:$src.1 ipn:$dst.1 /work/payload_$id 0.$cos $ttl \
      >/dev/null 2>&1 || echo "$id" >> /work/sendfail.txt; } &)
done < inject.tsv
END=$((START + WALL))
while [ "$(date +%s)" -lt "$END" ]; do
  sleep 5
  for d in $(cut -f3 inject.tsv | sort -u); do
    for f in /work/$d/testfile*; do [ -f "$f" ] || continue
      grep -q "^$(basename $f)	" /work/rx/seen_$d 2>/dev/null && continue
      printf "%s\t%s\t%s\n" "$(basename $f)" "$(head -1 $f)" "$(stat -c %Y $f)" >> /work/rx/seen_$d
    done
  done
done
cat /work/rx/seen_* 2>/dev/null | awk -F'\t' '{print $2"\t"$3}' > /work/delivered.tsv
echo "delivered $(wc -l < /work/delivered.tsv) bundles"
# A run is valid only if every node started before the plan, every send went out and no node's
# SDR failed; the harness discards and reruns cells that fail this.
echo "send_failures $(cat /work/sendfail.txt 2>/dev/null | wc -l)" >> /work/health.txt
echo "sdr_failures $(cat /work/*/ion.log 2>/dev/null | grep -c -E 'Assertion failed|Transaction aborted|can.t begin transaction')" >> /work/health.txt
cat /work/health.txt
killm >/dev/null 2>&1; echo RUN_DONE
