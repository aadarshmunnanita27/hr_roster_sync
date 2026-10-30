import argparse
import concurrent.futures
import json
import os
import platform
import statistics
import time
import urllib.error
import urllib.request


def send_event(arguments, employee_number):
    event = {
        "employeeId": f"LOAD-{employee_number:08d}",
        "sourceSystem": "load-test",
        "sourceVersion": 1,
        "eventType": "CREATE",
        "data": {
            "name": f"Load Test Worker {employee_number}",
            "email": f"load-{employee_number}@example.invalid",
            "phone": f"+100{employee_number:08d}",
            "officeCode": "BLR01",
            "shift": "DAY",
        },
    }
    request = urllib.request.Request(
        arguments.url,
        data=json.dumps(event).encode("utf-8"),
        headers={"Authorization": f"Bearer {arguments.token}",
                 "Content-Type": "application/json"},
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=arguments.timeout) as response:
            response.read()
            return time.perf_counter() - started, response.status < 400
    except (urllib.error.URLError, TimeoutError):
        return time.perf_counter() - started, False


def percentile(sorted_values, percent):
    if not sorted_values:
        return 0
    index = min(len(sorted_values) - 1, round((len(sorted_values) - 1) * percent))
    return sorted_values[index]


def main():
    parser = argparse.ArgumentParser(description="Measure HR event endpoint latency and throughput.")
    parser.add_argument("--url", default="http://localhost:8080/api/v1/hr/events")
    parser.add_argument("--requests", type=int, default=10_000)
    parser.add_argument("--workers", type=int, default=32)
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--token", default=os.environ.get("ROSTER_HR_TOKEN"))
    arguments = parser.parse_args()
    if not arguments.token:
        raise SystemExit("Provide --token or set ROSTER_HR_TOKEN.")

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=arguments.workers) as executor:
        results = list(executor.map(
            lambda number: send_event(arguments, number),
            range(1, arguments.requests + 1)))
    elapsed = time.perf_counter() - started
    latencies = sorted(duration for duration, _ in results)
    successes = sum(1 for _, success in results if success)

    print(f"requests={arguments.requests}")
    print(f"successful={successes}")
    print(f"failed={arguments.requests - successes}")
    print(f"throughput_requests_per_second={arguments.requests / elapsed:.2f}")
    print(f"latency_p50_ms={percentile(latencies, 0.50) * 1000:.2f}")
    print(f"latency_p99_ms={percentile(latencies, 0.99) * 1000:.2f}")
    print(f"machine_os={platform.platform()}")
    print(f"machine_cpu={platform.processor() or 'not reported by operating system'}")
    print(f"machine_logical_cores={os.cpu_count()}")
    print(f"machine_ram_bytes={available_memory_bytes()}")


def available_memory_bytes():
    if platform.system() == "Windows":
        import ctypes

        class MemoryStatus(ctypes.Structure):
            _fields_ = [
                ("length", ctypes.c_ulong),
                ("memory_load", ctypes.c_ulong),
                ("total_physical", ctypes.c_ulonglong),
                ("available_physical", ctypes.c_ulonglong),
                ("total_page_file", ctypes.c_ulonglong),
                ("available_page_file", ctypes.c_ulonglong),
                ("total_virtual", ctypes.c_ulonglong),
                ("available_virtual", ctypes.c_ulonglong),
                ("available_extended_virtual", ctypes.c_ulonglong),
            ]

        status = MemoryStatus()
        status.length = ctypes.sizeof(status)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
            return status.total_physical
    return "not reported by script"


if __name__ == "__main__":
    main()
