import argparse
import base64
import hashlib
import hmac
import json
import os
import time


def encode_segment(value):
    raw = json.dumps(value, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def main():
    parser = argparse.ArgumentParser(description="Create a short-lived local development JWT.")
    parser.add_argument("--role", required=True,
                        choices=("HR_SOURCE", "TRANSPORT_ADMIN", "OPERATOR", "EMPLOYEE"))
    parser.add_argument("--tenant", default="demo-tenant")
    parser.add_argument("--subject", default="local-demo-user")
    parser.add_argument("--source-system", default="demo-hr")
    parser.add_argument("--employee-id")
    arguments = parser.parse_args()

    secret = os.environ.get("ROSTER_JWT_SECRET")
    if not secret or len(secret.encode("utf-8")) < 32:
        raise SystemExit("Set ROSTER_JWT_SECRET to the same 32+ byte secret used by the app.")

    now = int(time.time())
    header = encode_segment({"alg": "HS256", "typ": "JWT"})
    claims = {
        "sub": arguments.subject,
        "tenant_id": arguments.tenant,
        "roles": [arguments.role],
        "iat": now,
        "exp": now + 3600,
    }
    if arguments.role == "HR_SOURCE":
        claims["source_system"] = arguments.source_system
    if arguments.employee_id:
        claims["employee_id"] = arguments.employee_id

    payload = encode_segment(claims)
    unsigned = f"{header}.{payload}"
    signature = hmac.new(secret.encode("utf-8"), unsigned.encode("ascii"), hashlib.sha256).digest()
    encoded_signature = base64.urlsafe_b64encode(signature).rstrip(b"=").decode("ascii")
    print(f"{unsigned}.{encoded_signature}")


if __name__ == "__main__":
    main()
