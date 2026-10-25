#!/usr/bin/env bash
# Reproduce the reported "only the first 12 seconds are seekable" with real H.264/AAC TS.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
FFMPEG=${FFMPEG:-ffmpeg}
FFPROBE=${FFPROBE:-ffprobe}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
"$FFMPEG" -hide_banner -loglevel error \
  -f lavfi -i 'testsrc2=size=160x90:rate=25' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000' \
  -t 12 -c:v libx264 -preset ultrafast -g 50 -bf 2 -c:a aac -b:a 96k -f mpegts "$WORK/segment.ts"
python3 - "$WORK" <<'PY'
import pathlib, sys
work = pathlib.Path(sys.argv[1])
(work / 'joined.ts').write_bytes((work / 'segment.ts').read_bytes() * 3)
PY
cat > "$WORK/Normalize.java" <<'JAVA'
import java.io.File;
import com.github.tvbox.osc.util.TsTimestampNormalizer;
public class Normalize {
    public static void main(String[] args) throws Exception {
        int resets = TsTimestampNormalizer.normalize(new File(args[0]), new File(args[1]));
        if (resets != 2) throw new AssertionError("Expected 2 clock resets, got " + resets);
    }
}
JAVA
javac -d "$WORK/classes" "$ROOT/app/src/main/java/com/github/tvbox/osc/util/TsTimestampNormalizer.java" "$WORK/Normalize.java"
java -cp "$WORK/classes" Normalize "$WORK/joined.ts" "$WORK/fixed.ts"
python3 - "$WORK" "$FFPROBE" "$FFMPEG" <<'PY'
import json, pathlib, subprocess, sys
work, probe, ffmpeg = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
def info(name):
    return json.loads(subprocess.check_output([probe, '-v', 'error', '-select_streams', 'v:0',
        '-count_frames', '-show_entries', 'stream=duration,nb_read_frames', '-of', 'json', str(work / name)]))['streams'][0]
before, after = info('joined.ts'), info('fixed.ts')
assert 11 < float(before['duration']) < 13, before
assert 35 < float(after['duration']) < 37, after
assert int(after['nb_read_frames']) == 900, after
progress = subprocess.check_output([ffmpeg, '-hide_banner', '-loglevel', 'error', '-skip_frame', 'nokey', '-ss', '24',
    '-i', str(work / 'fixed.ts'), '-map', '0:v:0', '-t', '4', '-progress', 'pipe:1', '-nostats', '-f', 'null', '-'], text=True)
frames = [int(line.split('=', 1)[1]) for line in progress.splitlines() if line.startswith('frame=')]
assert frames and max(frames) > 0, 'Seeking past 12 seconds did not decode any frames'
print('PASS: raw concatenation reports 12s; repaired TS reports 36s / 900 video frames; seek to 24s decodes successfully.')
PY
