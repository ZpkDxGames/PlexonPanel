"""Exercise stdin attach and clean stop/start on a disposable, local Docker Engine."""

import json
import os
import re
import socket
import subprocess
import time
import uuid
from urllib.parse import urlparse


def docker(*arguments):
    return subprocess.check_output(["docker", *arguments], timeout=15)


def attach(socket_path, container, command):
    with socket.socket(socket.AF_UNIX) as connection:
        connection.settimeout(5)
        connection.connect(socket_path)
        request = (
            f"POST /v1.40/containers/{container}/attach?stream=1&stdin=1&stdout=0&stderr=0&logs=0 HTTP/1.1\r\n"
            "Host: docker\r\nConnection: Upgrade\r\nUpgrade: tcp\r\nContent-Length: 0\r\n\r\n"
        )
        connection.sendall(request.encode("ascii"))
        response = bytearray()
        while not response.endswith(b"\r\n\r\n"):
            chunk = connection.recv(1)
            if not chunk or len(response) >= 8192:
                raise RuntimeError("Docker attach response was incomplete or oversized")
            response.extend(chunk)
        status = bytes(response).split(b"\r\n", 1)[0]
        if status not in (b"HTTP/1.1 101 UPGRADED", b"HTTP/1.1 101 Switching Protocols"):
            raise RuntimeError("Docker did not accept the attach upgrade")
        connection.sendall((command + "\n").encode("ascii"))


def fixture_state(container):
    return json.loads(docker("inspect", container))[0]


def main():
    target = os.environ.get("DOCKER_HOST")
    if target is None:
        context = json.loads(docker("context", "inspect"))[0]
        target = context["Endpoints"]["docker"]["Host"]
    endpoint = urlparse(target)
    if endpoint.scheme != "unix" or endpoint.netloc or not endpoint.path.startswith("/"):
        raise RuntimeError("Use a disposable, local Unix-socket Docker context")

    name = "plexon-bedrock-attach-fixture-" + uuid.uuid4().hex[:12]
    container = docker(
        "run", "--pull", "never", "--network", "none", "--restart", "unless-stopped", "--name", name, "-dit",
        "alpine:3.22", "sh", "-c",
        'trap "exit 0" TERM; '
        'while IFS= read -r line; do printf "fixture-read:%s\\n" "$line"; done',
    ).decode("ascii").strip()
    if not re.fullmatch("[0-9a-f]{64}", container):
        raise RuntimeError("Docker returned an invalid disposable fixture ID")
    try:
        launch = fixture_state(container)
        attach(endpoint.path, container, "list")
        attach(endpoint.path, container, "allowlist list")
        expected = [b"fixture-read:list\r\n", b"fixture-read:allowlist list\r\n"]
        for _ in range(40):
            lines = docker("logs", container)
            if all(line in lines for line in expected):
                break
            time.sleep(0.05)
        else:
            raise RuntimeError("Fixture did not read both console lines")
        if lines.index(expected[0]) >= lines.index(expected[1]) or not fixture_state(container)["State"]["Running"]:
            raise RuntimeError("Fixture lost command order or stdin")
        print("Docker attach delivered both fixed lines in order and kept stdin open.")

        docker("stop", "--time", "5", container)
        stopped = fixture_state(container)
        if stopped["State"]["Running"] or stopped["State"]["ExitCode"] != 0 or stopped["State"]["OOMKilled"]:
            raise RuntimeError("Docker fixture graceful stop was not verified")
        docker("start", container)
        started = fixture_state(container)
        if (not started["State"]["Running"] or started["Id"] != launch["Id"]
                or started["State"]["StartedAt"] == launch["State"]["StartedAt"]
                or started["HostConfig"]["RestartPolicy"]["Name"] != "unless-stopped"):
            raise RuntimeError("Docker fixture did not start a new run with existing identity and policy")
        print("Docker stop reached exit 0 and start produced a new run with the original identity and policy.")
    finally:
        docker("rm", "-f", container)


if __name__ == "__main__":
    main()
