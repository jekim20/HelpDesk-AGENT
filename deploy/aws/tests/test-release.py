#!/usr/bin/env python3
"""Exercise the release scripts against a disposable local Compose project.

Needs Docker Desktop or an ARM64 Docker Engine and cached postgres:16-alpine
and postgres:17-alpine images. No registry, SSM or S3 requests are sent.
"""
import fcntl
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import uuid


def shim(name, args):
    if name == "findmnt":
        print(os.environ["TEST_MOUNT"])
        return 0
    if name == "flock":
        try:
            fcntl.flock(int(args[-1]), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return 1
        return 0
    if name == "aws":
        if args[:2] == ["ssm", "get-parameter"]:
            print("disposable-local-test-password")
        elif args[:2] == ["ecr", "get-login-password"]:
            if os.environ.get("TEST_FAILURE") == "login":
                return 1
            print("stub-token")
        elif args[:2] == ["s3", "cp"]:
            return int(os.environ.get("TEST_FAILURE") == "backup")
        else:
            raise AssertionError("Unexpected AWS command")
        return 0
    if name == "mv":
        if (os.environ.get("TEST_FAILURE") == "publish"
                and args[-1] == os.environ["ACTIVE_IMAGES_FILE"]):
            return 1
        return subprocess.call([os.environ["TEST_REAL_MV"], *args])
    docker = os.environ["TEST_REAL_DOCKER"]
    if args[0] in ("login", "logout"):
        if args[0] == "login":
            return int(not sys.stdin.read().strip())
        return 0
    if args[0] == "compose" and "pull" in args:
        if os.environ.get("TEST_FAILURE") == "pull":
            return 1
        variables = {
            "app": "HELPDESK_APP_IMAGE",
            "frontend": "HELPDESK_FRONTEND_IMAGE",
            "postgres": "HELPDESK_POSTGRES_IMAGE",
        }
        for service in args[args.index("--quiet") + 1:]:
            with open(os.devnull, "w") as sink:
                if subprocess.call([docker, "image", "inspect", os.environ[variables[service]]],
                                   stdout=sink, stderr=sink):
                    return 1
        return 0
    if args[0] == "compose" and "up" in args and args[-2:] == ["app", "frontend"]:
        failure = os.environ.get("TEST_FAILURE")
        pin = os.environ.get("TEST_FAILURE_IMAGE")
        if pin and os.environ["HELPDESK_APP_IMAGE"] == pin:
            if failure in ("health", "recovery"):
                blocked = ("16 17" if failure == "recovery" else
                           ("16" if pin == os.environ["TEST_PG16"] else "17"))
                Path(os.environ["TEST_GATE"]).write_text(blocked)
            if failure == "partial-up":
                subprocess.run([docker, *args[:-1]], check=True)
                return 1
    return subprocess.call([docker, *args])


if len(sys.argv) > 1 and sys.argv[1] == "--shim":
    sys.exit(shim(sys.argv[2], sys.argv[3:]))


def run_suite():
    root = Path(__file__).resolve().parents[3]
    scripts = root / "deploy/aws/scripts"
    docker = shutil.which("docker")
    assert docker, "Docker is required"
    # Read the local socket from the selected context and pin it through the auth shim.
    context = subprocess.check_output([docker, "context", "show"], text=True).strip()
    endpoint = subprocess.check_output([docker, "context", "inspect", context,
                                        "--format", "{{.Endpoints.docker.Host}}"], text=True).strip()
    assert endpoint.startswith("unix://"), "Use a local Unix-socket Docker daemon"
    env = {k: v for k, v in os.environ.items() if k not in
           {"DOCKER_CONTEXT", "DOCKER_CONFIG", "COMPOSE_FILE", "COMPOSE_PROJECT_NAME"}}
    env["DOCKER_HOST"] = endpoint

    def run(args, **kwargs):
        return subprocess.run(args, text=True, capture_output=True, env=kwargs.pop("env", env),
                              check=kwargs.pop("check", True), **kwargs)

    def reference(tag):
        digest, arch = run([docker, "image", "inspect", "--format",
                            "{{index .RepoDigests 0}} {{.Architecture}}", tag]).stdout.split()
        assert arch == "arm64", f"{tag} must be cached for ARM64"
        return "docker.io/library/" + digest

    image_a, image_b = reference("postgres:16-alpine"), reference("postgres:17-alpine")
    absent = "docker.io/library/postgres@sha256:" + "0" * 64
    with tempfile.TemporaryDirectory(prefix="helpdesk-release-", dir="/tmp") as directory:
        temp = Path(directory).resolve()
        state, data, bin_dir = (temp / name for name in ("state", "data", "bin"))
        for path in (state, data, bin_dir):
            path.mkdir()
        gate = temp / "blocked-majors"
        gate.touch()
        for name in ("docker", "aws", "findmnt", "flock", "mv"):
            wrapper = bin_dir / name
            wrapper.write_text(f'#!/bin/bash\nexec "{sys.executable}" "{Path(__file__).resolve()}" --shim {name} "$@"\n')
            wrapper.chmod(0o700)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        active, previous = state / "active.env", state / "previous.env"
        pending = state / "active.env.pending"
        project = "helpdesk-release-test-" + uuid.uuid4().hex[:10]
        config, compose_file = temp / "config.env", temp / "compose.yaml"
        env.update(PATH=str(bin_dir) + os.pathsep + env["PATH"], TEST_REAL_DOCKER=docker,
                   TEST_REAL_MV=shutil.which("mv"), TEST_MOUNT=str(data), TEST_GATE=str(gate),
                   TEST_PG16=image_a, TMPDIR=directory, XDG_RUNTIME_DIR=directory,
                   ACTIVE_IMAGES_FILE=str(active), PREVIOUS_IMAGES_FILE=str(previous),
                   AWS_DEPLOY_CONFIG=str(config), COMPOSE_PROJECT_NAME=project,
                   FIXTURE_DATA=str(data), FIXTURE_GATE=str(gate), FIXTURE_PORT=str(port))
        response_script = temp / "response.sh"
        response_script.write_text(
            "#!/bin/sh\n"
            'while IFS= read -r line; do [ "$line" = "$(printf \'\\r\')" ] && break; done\n'
            "printf 'HTTP/1.1 200 OK\\r\\nContent-Length: 2\\r\\nConnection: close\\r\\n\\r\\nok'\n"
        )
        response_script.chmod(0o755)
        env["FIXTURE_RESPONSE"] = str(response_script)
        # PostgreSQL is real. App and frontend are lightweight HTTP/health fixtures
        # with distinct cached image digests. Their DB is never a development DB.
        compose_file.write_text("""services:
  postgres:
    image: ${HELPDESK_POSTGRES_IMAGE}
    environment:
      POSTGRES_PASSWORD: disposable-local-test-password
      POSTGRES_DB: helpdesk
      POSTGRES_USER: helpdesk
    volumes: ["${FIXTURE_DATA}:/var/lib/postgresql/data"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U helpdesk -d helpdesk"]
      interval: 1s
      timeout: 2s
      retries: 3
  app:
    image: ${HELPDESK_APP_IMAGE}
    stop_grace_period: 1s
    entrypoint: ["sh", "-c", "exec sleep 3600"]
    volumes: ["${FIXTURE_GATE}:/gate:ro"]
    healthcheck:
      test: ["CMD-SHELL", '! grep -qw "$$PG_MAJOR" /gate']
      interval: 1s
      timeout: 2s
      retries: 1
  frontend:
    image: ${HELPDESK_FRONTEND_IMAGE}
    stop_grace_period: 1s
    entrypoint: ["sh", "-c", "while :; do nc -l -p 8080 -e /response.sh; done"]
    volumes: ["${FIXTURE_RESPONSE}:/response.sh:ro"]
    ports: ["127.0.0.1:${FIXTURE_PORT}:8080"]
    healthcheck:
      test: ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:8080/"]
      interval: 1s
      timeout: 2s
      retries: 3
""")
        config.write_text(f"""AWS_REGION=fixture
COMPOSE_FILE={compose_file}
HELPDESK_PGDATA_PATH={data}
HELPDESK_EBS_MOUNT={data}
ECR_APP_REPOSITORY=docker.io/library/postgres
ECR_FRONTEND_REPOSITORY=docker.io/library/postgres
PARAM_DB_PASSWORD=/fixture/db
PARAM_USER1_PASSWORD=/fixture/user1
PARAM_USER2_PASSWORD=/fixture/user2
PARAM_ADMIN_PASSWORD=/fixture/admin
BACKUP_BUCKET=fixture-local-stub
DEMO_PORT={port}
HEALTH_TIMEOUT_SECONDS=8
""")

        def release(name, image, pg=image_b):
            path = temp / (name + ".env")
            path.write_text(f"HELPDESK_APP_IMAGE={image}\nHELPDESK_FRONTEND_IMAGE={image}\nHELPDESK_POSTGRES_IMAGE={pg}\n")
            return path

        a, b = release("a", image_a), release("b", image_b)
        missing = release("missing", absent)
        pg_change = release("pg-change", image_a, pg=image_a)
        compose = [docker, "compose", "-p", project, "--env-file", str(a), "-f", str(compose_file)]

        def service(name):
            ids = run([*compose, "ps", "-a", "-q", name]).stdout.split()
            if not ids:
                return None
            assert len(ids) == 1
            return tuple(run([docker, "inspect", "--format",
                              "{{.Id}} {{.Config.Image}} {{.State.Status}} {{.State.Health.Status}}",
                              ids[0]]).stdout.split())

        def call(script, arg=None, failure="", pin="", expect=0, message=None):
            args = [str(scripts / script)]
            if arg:
                args.append(str(arg))
            result = run(args, check=False, env=dict(env, TEST_FAILURE=failure, TEST_FAILURE_IMAGE=pin))
            assert result.returncode == expect, result.stdout + result.stderr
            if message:
                assert message in (result.stdout + result.stderr), result.stdout + result.stderr
            return result.stdout + result.stderr

        def state_matches(active_release, previous_release, image):
            for file, original in [(active, active_release), (previous, previous_release)]:
                if original:
                    assert file.read_bytes() == original.read_bytes()
                    assert file.stat().st_mode & 0o777 == 0o600
                else:
                    assert not file.exists()
            assert not pending.exists()
            for name in ("app", "frontend"):
                status = service(name)
                if image:
                    assert status and status[1:] == (image, "running", "healthy"), (name, status)
                else:
                    assert not status or status[2] == "exited", (name, status)

        def sql(query):
            return run([*compose, "exec", "-T", "postgres", "psql", "-U", "helpdesk",
                        "-d", "helpdesk", "-Atqc", query]).stdout.strip()

        try:
            call("deploy.sh", a, failure="login", expect=1, message="ECR login failed")
            state_matches(None, None, None)
            assert service("postgres") is None
            call("deploy.sh", missing, expect=1, message="candidate image pull failed")
            state_matches(None, None, None)
            assert service("postgres") is None
            print("PASS first login / pull failure leaves no manifest or containers", flush=True)

            first_failure = call("deploy.sh", a, failure="health", pin=image_a, expect=1,
                                 message="Initial deployment did not activate")
            state_matches(None, None, None)
            assert service("postgres"), first_failure
            pg_id = service("postgres")[0]
            assert (data / "PG_VERSION").is_file()
            sql("create table recovery_probe(value int); insert into recovery_probe values (42);")
            gate.write_text("")
            call("deploy.sh", a)
            state_matches(a, None, image_a)
            print("PASS failed first health check stops app/frontend and permits same-DB retry", flush=True)

            stable = [service(name) for name in ("postgres", "app", "frontend")]
            call("deploy.sh", b, failure="pull", expect=1, message="candidate image pull failed")
            call("deploy.sh", b, failure="backup", expect=1, message="S3 upload failed")
            call("deploy.sh", pg_change, expect=1, message="PostgreSQL image changes")
            state_matches(a, None, image_a)
            assert stable == [service(name) for name in ("postgres", "app", "frontend")]
            print("PASS pull / backup / database-digest rejection preserves running containers", flush=True)

            call("deploy.sh", b, failure="partial-up", pin=image_b, expect=1, message="restored and verified healthy")
            state_matches(a, None, image_a)
            print("PASS partial Compose up restores the original images", flush=True)
            call("deploy.sh", b, failure="health", pin=image_b, expect=1, message="restored and verified healthy")
            state_matches(a, None, image_a)
            gate.write_text("")
            print("PASS candidate health failure restores containers and manifests", flush=True)

            call("deploy.sh", b, failure="publish", expect=1, message="Could not publish the active manifest")
            state_matches(a, None, image_a)
            call("deploy.sh", b)
            state_matches(b, a, image_b)
            call("deploy.sh", a, failure="publish", expect=1, message="Could not publish the active manifest")
            state_matches(b, a, image_b)
            print("PASS manifest write failure restores containers and previous manifest", flush=True)

            # The prior release references an unavailable image. Restore the saved
            # fixture manifest after observing that rollback cannot pull it.
            previous.write_bytes(missing.read_bytes())
            stable = [service(name) for name in ("postgres", "app", "frontend")]
            call("rollback.sh", expect=1, message="candidate image pull failed")
            state_matches(b, missing, image_b)
            assert stable == [service(name) for name in ("postgres", "app", "frontend")]
            previous.write_bytes(a.read_bytes())
            print("PASS rollback target pull failure keeps the running release", flush=True)

            call("rollback.sh", failure="health", pin=image_a, expect=1, message="restored and verified healthy")
            state_matches(b, a, image_b)
            gate.write_text("")
            call("rollback.sh", failure="login")
            state_matches(a, b, image_a)
            assert service("postgres")[0] == pg_id and sql("select value from recovery_probe") == "42"
            print("PASS failed rollback restores original; cached rollback works without ECR", flush=True)

            # Verify overlapping operations are rejected before touching images.
            with (state / "active.env.lock").open() as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                call("deploy.sh", b, expect=1, message="holds the deployment lock")
            state_matches(a, b, image_a)
            print("PASS overlapping deployment rejected", flush=True)

            gate.write_text("16")
            deadline = time.monotonic() + 8
            while service("app")[3] != "unhealthy":
                assert time.monotonic() < deadline, "original app did not become unhealthy"
                time.sleep(0.2)
            call("rollback.sh")
            state_matches(b, a, image_b)
            print("PASS manual rollback can replace an unhealthy active application", flush=True)
            gate.write_text("")

            call("rollback.sh", failure="recovery", pin=image_a, expect=1, message="Release recovery failed")
            assert pending.exists()
            assert active.read_bytes() == b.read_bytes() and previous.read_bytes() == a.read_bytes()
            assert (pending / "original.env").read_bytes() == b.read_bytes()
            assert (pending / "candidate.env").read_bytes() == a.read_bytes()
            assert all(service(name)[2] == "exited" for name in ("app", "frontend"))
            assert service("postgres")[0] == pg_id and sql("select value from recovery_probe") == "42"
            call("deploy.sh", b, expect=1, message="unfinished release requires manual recovery")
            print("PASS failed recovery stops app/frontend, preserves DB, and blocks a new change", flush=True)
        finally:
            run([*compose, "down", "--timeout", "2"], check=False)


if __name__ == "__main__":
    run_suite()
