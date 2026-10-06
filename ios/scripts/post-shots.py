#!/usr/bin/env python3
"""The screenshots as notes on an "iOS screenshots" check run, a JPEG in base64 split into
parts of 60 KB, so they can be read back through GitHub's API (artifacts are served from a
host it can't reach). Notes made by a workflow command are cut to 4 KB; these aren't."""
import base64, json, os, subprocess, sys, urllib.request

shots = sys.argv[1] if len(sys.argv) > 1 else "shots"
repo, sha, token = os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_SHA"], os.environ["GITHUB_TOKEN"]

def call(method, path, body):
    req = urllib.request.Request(f"https://api.github.com/repos/{repo}{path}", data=json.dumps(body).encode(), method=method,
                                 headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)

notes = []
for name in sorted(os.listdir(shots)):
    if not name.endswith(".png"):
        continue
    jpg = os.path.join(shots, name[:-4] + ".jpg")
    subprocess.run(["sips", "-s", "format", "jpeg", "-s", "formatOptions", "60", "--resampleWidth", "1000",
                    os.path.join(shots, name), "--out", jpg], check=True, capture_output=True)
    data = base64.b64encode(open(jpg, "rb").read()).decode()
    parts = [data[i:i + 60000] for i in range(0, len(data), 60000)]
    for i, part in enumerate(parts):
        notes.append({"path": "ios/scripts/screenshots.sh", "start_line": 1, "end_line": 1, "annotation_level": "notice",
                      "title": f"shot {name[:-4]} {i + 1}/{len(parts)}", "message": part})

run = call("POST", "/check-runs", {"name": "iOS screenshots", "head_sha": sha, "status": "in_progress"})
for i in range(0, len(notes), 50):
    call("PATCH", f"/check-runs/{run['id']}", {"output": {"title": "Screenshots", "summary": f"{len(notes)} parts", "annotations": notes[i:i + 50]}})
call("PATCH", f"/check-runs/{run['id']}", {"status": "completed", "conclusion": "neutral",
                                           "output": {"title": "Screenshots", "summary": f"{len(notes)} parts"}})
print(f"posted {len(notes)} parts on check run {run['id']}")
