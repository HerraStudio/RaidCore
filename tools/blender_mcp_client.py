"""Use Blender Lab's running MCP socket bridge, without altering Blender preferences.

The official add-on listens on localhost:9876 using NUL-delimited execute requests.
This client forwards a saved Python authoring script and returns its strict JSON result.
"""
import argparse
import json
import socket
from pathlib import Path


def execute(code, timeout=45):
    message = json.dumps({"type": "execute", "code": code, "strict_json": True}).encode("utf-8") + b"\0"
    with socket.create_connection(("127.0.0.1", 9876), timeout=timeout) as connection:
        connection.settimeout(timeout)
        connection.sendall(message)
        data = bytearray()
        while b"\0" not in data:
            chunk = connection.recv(65536)
            if not chunk:
                raise RuntimeError("Blender MCP closed without a complete response")
            data.extend(chunk)
        response = json.loads(data.split(b"\0", 1)[0])
    return response


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("script", type=Path)
    parser.add_argument("--timeout", type=float, default=45)
    args = parser.parse_args()
    reply = execute(args.script.read_text(encoding="utf-8"), args.timeout)
    print(json.dumps(reply, ensure_ascii=False, indent=2))
    if reply.get("status") != "ok":
        raise SystemExit(1)
