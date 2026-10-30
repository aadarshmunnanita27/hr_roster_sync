#!/usr/bin/env python3
"""One-command benchmark for HR Roster Sync (Python 3.8+, standard library only).

    python benchmark/run_all.py                 # starts docker compose, runs everything
    python benchmark/run_all.py --no-compose    # app already running on --base-url
    python benchmark/run_all.py --rows 50000 --requests 2000   # smaller smoke run

It measures, against the running service:
  1. HR event throughput and latency (p50 / p99), counting only responses whose
     per-record status is APPLIED or DUPLICATE (HTTP 200 alone is not success).
  2. Full snapshot reconciliation of N rows (default 200,000): first load into an empty
     tenant, then a second identical upload (every row unchanged), timing each.
  3. Peak JVM heap used (sampled from /actuator/prometheus every 250 ms during the runs).
  4. Machine / Docker / PostgreSQL details.

Results are written to benchmark/results/RESULTS.md. Nothing is estimated: if a value
cannot be read it is written as "not available".
"""
import argparse
import base64
import concurrent.futures
import csv
import hashlib
import hmac
import json
import os
import platform
import re
import shutil
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SECRET = "local-only-change-this-secret-before-deployment"
TENANT = "bench-tenant"


# ------------------------------------------------------------------ helpers
def b64(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def make_token(secret, role, tenant, source_system=None, subject="benchmark"):
    now = int(time.time())
    claims = {"sub": subject, "tenant_id": tenant, "roles": [role], "iat": now, "exp": now + 7200}
    if source_system:
        claims["source_system"] = source_system
    head = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    body = b64(json.dumps(claims, separators=(",", ":")).encode())
    sig = hmac.new(secret.encode(), f"{head}.{body}".encode("ascii"), hashlib.sha256).digest()
    return f"{head}.{body}.{b64(sig)}"


def http(method, url, token=None, body=None, headers=None, timeout=120):
    data = body if isinstance(body, (bytes, type(None))) else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method)
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    if isinstance(body, (dict, list)):
        request.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def percentile(sorted_values, fraction):
    if not sorted_values:
        return float("nan")
    index = min(len(sorted_values) - 1, int(round((len(sorted_values) - 1) * fraction)))
    return sorted_values[index]


def run(command, check=False):
    try:
        done = subprocess.run(command, capture_output=True, text=True, timeout=600, check=check)
        return done.stdout.strip()
    except Exception:  # noqa: BLE001 - best-effort environment probing
        return ""


class HeapSampler(threading.Thread):
    """Samples sum(jvm_memory_used_bytes{area="heap"}) so we can report the peak."""

    pattern = re.compile(r'^jvm_memory_used_bytes\{[^}]*area="heap"[^}]*\}\s+([0-9.eE+]+)', re.M)

    def __init__(self, base_url):
        super().__init__(daemon=True)
        self.url = f"{base_url}/actuator/prometheus"
        self.peak = 0.0
        self.samples = 0
        self._stop_event = threading.Event()

    def run(self):
        while not self._stop_event.is_set():
            try:
                _, payload = http("GET", self.url, timeout=5)
                total = sum(float(v) for v in self.pattern.findall(payload.decode("utf-8", "replace")))
                if total > 0:
                    self.peak = max(self.peak, total)
                    self.samples += 1
            except Exception:  # noqa: BLE001
                pass
            self._stop_event.wait(0.25)

    def stop(self):
        self._stop_event.set()
        self.join(timeout=5)


def wait_healthy(base_url, seconds=240):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            status, _ = http("GET", f"{base_url}/actuator/health", timeout=3)
            if status == 200:
                return
        except Exception:  # noqa: BLE001
            pass
        time.sleep(3)
    raise SystemExit("Service did not become healthy in time.")


# ------------------------------------------------------------------ benchmarks
def event_benchmark(args, token):
    def send(number):
        event = {
            "employeeId": f"LOAD-{number:08d}", "sourceSystem": "load-test", "sourceVersion": 1,
            "eventType": "CREATE",
            "data": {"name": f"Load Worker {number}", "email": f"load-{number}@example.invalid",
                     "phone": f"+100{number:08d}", "officeCode": "BLR01", "shift": "DAY"},
        }
        started = time.perf_counter()
        try:
            status, payload = http("POST", f"{args.base_url}/api/v1/hr/events", token, event, timeout=30)
            ok = status == 200 and json.loads(payload)[0]["status"] in ("APPLIED", "DUPLICATE")
        except Exception:  # noqa: BLE001
            ok = False
        return time.perf_counter() - started, ok

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(send, range(1, args.requests + 1)))
    elapsed = time.perf_counter() - started
    latencies = sorted(d for d, _ in results)
    good = sum(1 for _, ok in results if ok)
    return {
        "requests": args.requests, "workers": args.workers, "successful": good,
        "failed": args.requests - good, "seconds": elapsed,
        "throughput": args.requests / elapsed,
        "p50_ms": percentile(latencies, 0.50) * 1000, "p99_ms": percentile(latencies, 0.99) * 1000,
        "mean_ms": statistics.fmean(latencies) * 1000,
    }


def write_snapshot_csv(path, rows):
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(("employeeId", "sourceVersion", "name", "email", "phone",
                         "managerId", "officeCode", "shift", "active"))
        for n in range(1, rows + 1):
            writer.writerow((f"EMP-{n:06d}", 1, f"Example Worker {n}", f"worker-{n}@example.invalid",
                             f"+100{n:08d}", "", "BLR01", "DAY", "true"))


def multipart(fields, file_field, file_name, file_bytes):
    boundary = uuid.uuid4().hex
    parts = []
    for key, value in fields.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
    parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="{file_field}"; '
                  f'filename="{file_name}"\r\nContent-Type: text/csv\r\n\r\n').encode())
    parts.append(file_bytes)
    parts.append(f"\r\n--{boundary}--\r\n".encode())
    return b"".join(parts), {"Content-Type": f"multipart/form-data; boundary={boundary}"}


def upload_snapshot(args, token, csv_path):
    with open(csv_path, "rb") as handle:
        payload = handle.read()
    cutoff = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    body, headers = multipart({"sourceSystem": "benchmark", "snapshotCutoff": cutoff},
                              "file", "snapshot.csv", payload)
    started = time.perf_counter()
    status, response = http("POST", f"{args.base_url}/api/v1/hr/snapshots", token, body, headers, timeout=3600)
    elapsed = time.perf_counter() - started
    if status >= 300:
        raise SystemExit(f"Snapshot upload failed: HTTP {status}")
    run_info = json.loads(response)
    return elapsed, run_info


def environment(args):
    def read_cpu():
        if os.path.exists("/proc/cpuinfo"):
            with open("/proc/cpuinfo", encoding="utf-8", errors="replace") as h:
                for line in h:
                    if line.lower().startswith("model name"):
                        return line.split(":", 1)[1].strip()
        if platform.system() == "Darwin":
            return run(["sysctl", "-n", "machdep.cpu.brand_string"]) or "not available"
        if platform.system() == "Windows":
            return run(["powershell", "-NoProfile", "-Command",
                        "(Get-CimInstance Win32_Processor).Name"]) or "not available"
        return platform.processor() or "not available"

    def read_ram():
        if os.path.exists("/proc/meminfo"):
            with open("/proc/meminfo", encoding="utf-8") as h:
                kb = int(h.readline().split()[1])
                return f"{kb / 1024 / 1024:.1f} GiB"
        if platform.system() == "Darwin":
            raw = run(["sysctl", "-n", "hw.memsize"])
            return f"{int(raw) / 1024 ** 3:.1f} GiB" if raw.isdigit() else "not available"
        if platform.system() == "Windows":
            raw = run(["powershell", "-NoProfile", "-Command",
                       "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory"])
            return f"{int(raw) / 1024 ** 3:.1f} GiB" if raw.isdigit() else "not available"
        return "not available"

    docker_info = run(["docker", "info", "--format", "{{.NCPU}} CPUs / {{.MemTotal}} bytes (Docker VM)"])
    pg_version = run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "roster", "-d", "roster",
                      "-tAc", "show server_version"]) if not args.no_compose else ""
    jvm = run(["docker", "compose", "exec", "-T", "app", "java", "-version"]) if not args.no_compose else ""
    return {
        "os": platform.platform(), "cpu": read_cpu(), "cores": os.cpu_count(), "ram": read_ram(),
        "docker": docker_info or "not available", "postgres": pg_version or "not available (see compose file: postgres:16.4)",
        "python": platform.python_version(),
        "jvm_flags": "default (no -Xmx/-XX flags are set in Dockerfile or docker-compose.yml)",
        "jvm": jvm or "see Dockerfile (eclipse-temurin:21-jre)",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--rows", type=int, default=200_000)
    parser.add_argument("--requests", type=int, default=10_000)
    parser.add_argument("--workers", type=int, default=32)
    parser.add_argument("--no-compose", action="store_true", help="do not run docker compose up")
    parser.add_argument("--secret", default=os.environ.get("ROSTER_JWT_SECRET", DEFAULT_SECRET))
    args = parser.parse_args()

    if not args.no_compose:
        if not shutil.which("docker"):
            raise SystemExit("docker not found. Install Docker or start the app yourself and use --no-compose.")
        print("Starting docker compose (build may take a few minutes)...")
        subprocess.run(["docker", "compose", "up", "-d", "--build"], cwd=ROOT, check=True)
    wait_healthy(args.base_url)

    admin = make_token(args.secret, "TRANSPORT_ADMIN", TENANT)
    status, _ = http("PUT", f"{args.base_url}/api/v1/admin/offices", admin,
                     {"officeCode": "BLR01", "officeName": "Benchmark office", "active": True})
    if status >= 300:
        raise SystemExit(f"Could not create the benchmark office (HTTP {status}); check ROSTER_JWT_SECRET.")

    sampler = HeapSampler(args.base_url)
    sampler.start()
    print(f"Event load: {args.requests} requests, {args.workers} workers...")
    events = event_benchmark(args, make_token(args.secret, "HR_SOURCE", TENANT, "load-test"))
    heap_after_events = sampler.peak

    csv_path = os.path.join(ROOT, "benchmark", "results", f"snapshot-{args.rows}.csv")
    os.makedirs(os.path.dirname(csv_path), exist_ok=True)
    print(f"Generating {args.rows} rows...")
    write_snapshot_csv(csv_path, args.rows)
    hr_token = make_token(args.secret, "HR_SOURCE", TENANT, "benchmark")
    sampler.peak = 0.0
    print("Snapshot 1/2 (initial load of every row)...")
    first_seconds, first = upload_snapshot(args, hr_token, csv_path)
    print("Snapshot 2/2 (identical file; every row unchanged)...")
    second_seconds, second = upload_snapshot(args, hr_token, csv_path)
    sampler.stop()
    os.remove(csv_path)

    env = environment(args)
    mib = lambda b: f"{b / 1024 / 1024:.0f} MiB" if b else "not available"  # noqa: E731
    out = os.path.join(ROOT, "benchmark", "results", "RESULTS.md")
    with open(out, "w", encoding="utf-8") as handle:
        handle.write(f"""# Benchmark results

Measured {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')} by `benchmark/run_all.py`.

## Machine

| Item | Value |
|---|---|
| OS | {env['os']} |
| CPU | {env['cpu']} |
| Logical cores | {env['cores']} |
| RAM | {env['ram']} |
| Docker resources | {env['docker']} |
| PostgreSQL | {env['postgres']} |
| JVM flags | {env['jvm_flags']} |
| Load generator | Python {env['python']} (urllib, {args.workers} threads) on the same machine as the service |

## HR event endpoint (single-record CREATE, one HTTP request per event)

| Metric | Result |
|---|---|
| Requests (successful / failed) | {events['requests']} ({events['successful']} / {events['failed']}) |
| Concurrency | {events['workers']} workers |
| Throughput | {events['throughput']:.1f} requests/s |
| Latency p50 | {events['p50_ms']:.1f} ms |
| Latency p99 | {events['p99_ms']:.1f} ms |
| Latency mean | {events['mean_ms']:.1f} ms |
| JVM heap peak after event load | {mib(heap_after_events)} |

## Snapshot reconciliation, {args.rows:,} rows (upload + stage + diff + apply, synchronous)

| Run | Wall-clock time | State | Uploaded rows | New | Updated | Unchanged | Missing |
|---|---|---|---|---|---|---|---|
| 1. initial load (empty tenant) | {first_seconds:.1f} s | {first.get('state')} | {first.get('uploadedRowCount')} | {first.get('newEmployeeCount')} | {first.get('updatedEmployeeCount')} | {first.get('unchangedEmployeeCount')} | {first.get('missingEmployeeCount')} |
| 2. identical re-upload (pure diff) | {second_seconds:.1f} s | {second.get('state')} | {second.get('uploadedRowCount')} | {second.get('newEmployeeCount')} | {second.get('updatedEmployeeCount')} | {second.get('unchangedEmployeeCount')} | {second.get('missingEmployeeCount')} |

Peak JVM heap used during both snapshot runs: **{mib(sampler.peak)}** ({sampler.samples} samples at 250 ms from `/actuator/prometheus`).
Target from the case study: diff < 60 s.

Caveats: client and service share one machine, so the load generator competes for CPU; the
Python client is likely to under-report the service's ceiling. Numbers describe this machine
only.
""")
    print(open(out, encoding="utf-8").read())
    print(f"Wrote {out}")


if __name__ == "__main__":
    sys.exit(main())
