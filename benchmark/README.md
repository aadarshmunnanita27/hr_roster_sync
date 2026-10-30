# Benchmark tools

Generate and run the 200,000-row diff benchmark:

```powershell
python benchmark\generate-snapshot.py --rows 200000
python scripts\make-token.py --role HR_SOURCE --tenant demo-tenant
$env:ROSTER_HR_TOKEN = "<paste the generated short-lived token>"
.\benchmark\run-diff.ps1
```

Measure event endpoint throughput and latency:

```powershell
python benchmark\run-event-load.py --requests 10000 --workers 32
```

The scripts report observations from the current host; they do not claim a
result until run. Capture JVM heap and PostgreSQL settings alongside each run.
Use a fresh tenant and a complete roster when measuring a full reconciliation.
