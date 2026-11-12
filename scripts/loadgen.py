#!/usr/bin/env python3
"""Minimal HTTP load generator (standard library only), used by load-test.sh when `hey` is not installed.

Each worker thread sends requests in a loop on a new connection each time (no keep-alive), so the
Kubernetes Service spreads them over all pods. Prints a summary in the spirit of `hey`.

Example:
    py -3 scripts/loadgen.py --url http://127.0.0.1:54321/api/info -c 4 -q 10 -z 120
    py -3 scripts/loadgen.py --url http://127.0.0.1:54321/api/scan -m POST \
        -d '{"url":"http://shop.example.net/item?id=123"}' -c 4 -q 10 -n 2400
"""
import argparse
import collections
import statistics
import threading
import time
import urllib.error
import urllib.request


def worker(args, deadline, counter, lock, results):
    interval = 1.0 / args.qps if args.qps > 0 else 0.0
    data = args.data.encode() if args.data else None
    while True:
        with lock:
            if (args.requests and counter[0] >= args.requests) or time.monotonic() >= deadline:
                return
            counter[0] += 1
        started = time.monotonic()
        req = urllib.request.Request(args.url, data=data, method=args.method)
        if data:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=args.timeout) as resp:
                resp.read()
                status = str(resp.status)
        except urllib.error.HTTPError as e:
            status = str(e.code)
        except Exception as e:  # timeouts, refused connections
            status = "error:" + type(e).__name__
        elapsed = time.monotonic() - started
        with lock:
            results.append((status, elapsed))
        if interval:
            time.sleep(max(0.0, interval - elapsed))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", required=True)
    p.add_argument("-m", "--method", default="GET")
    p.add_argument("-d", "--data", default="")
    p.add_argument("-c", "--concurrency", type=int, default=4)
    p.add_argument("-q", "--qps", type=float, default=10, help="rate limit per worker (0 = none)")
    p.add_argument("-n", "--requests", type=int, default=0, help="total requests (0 = use duration)")
    p.add_argument("-z", "--duration", type=float, default=0, help="seconds to run (0 = use requests)")
    p.add_argument("-t", "--timeout", type=float, default=10)
    args = p.parse_args()
    if not args.requests and not args.duration:
        p.error("give -n or -z")

    deadline = time.monotonic() + (args.duration if args.duration else 10 ** 9)
    counter, lock, results = [0], threading.Lock(), []
    start = time.monotonic()
    threads = [threading.Thread(target=worker, args=(args, deadline, counter, lock, results), daemon=True)
               for _ in range(args.concurrency)]
    for t in threads:
        t.start()
    try:
        for t in threads:
            t.join()
    except KeyboardInterrupt:
        print("interrupted")
    total = time.monotonic() - start

    latencies = sorted(r[1] for r in results)
    print("Summary:")
    print(f"  Total:        {total:.2f} secs")
    print(f"  Requests:     {len(results)}")
    print(f"  Requests/sec: {len(results) / total:.2f}" if total else "")
    if latencies:
        q = statistics.quantiles(latencies, n=100) if len(latencies) > 1 else latencies * 99
        print(f"  Latency p50 {q[49]:.3f}s  p90 {q[89]:.3f}s  p99 {q[98]:.3f}s  max {latencies[-1]:.3f}s")
    print("\nStatus code distribution:")
    for status, n in sorted(collections.Counter(r[0] for r in results).items()):
        print(f"  [{status}] {n} responses")


if __name__ == "__main__":
    main()
