from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import json
import re
import shutil
import socket
import struct
import subprocess
import time
from typing import Any

from mcp.server.fastmcp import FastMCP


REPO_ROOT = Path(__file__).resolve().parents[2]
PLUGIN_DIR = REPO_ROOT / "plugin"
TEST_SERVER_DIR = REPO_ROOT / "test-server"
SCRIPTS_DIR = REPO_ROOT / "scripts" / "ci" / "sim"
LOG_PATH = TEST_SERVER_DIR / "logs" / "latest.log"

DEFAULT_BUILD_TIMEOUT_SECONDS = 600
DEFAULT_TEST_TIMEOUT_SECONDS = 900
DEFAULT_FAST_PLAYTEST_TIMEOUT_SECONDS = 1800
DEFAULT_SCENARIO_TIMEOUT_SECONDS = 2400

UUID_RE = re.compile(r"\b[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\b", re.IGNORECASE)
RECEIPT_RE = re.compile(r"\[STRUCT\]\[RECEIPT\]\s+(\S+)\s+@\s+\((-?\d+),(-?\d+),(-?\d+)")
RECEIPT_FALLBACK_RE = re.compile(r"\[STRUCT\]\s+receipt:\s+id=(\S+)\s+bounds=\[(-?\d+)\.\.(-?\d+),(-?\d+)\.\.(-?\d+),(-?\d+)\.\.(-?\d+)\]")
FIXED_LAYOUT_RECEIPTS_RE = re.compile(r"\[STRUCT\]\[TEST\] Fixed layout receipts=([0-9]+)", re.IGNORECASE)
SITE_REJECT_RE = re.compile(r"\[SITE-REJECT\]")
PATH_FAIL_RE = re.compile(r"\[PATH\].*failed|A\* failed", re.IGNORECASE)
ZERO_PLACEMENT_RE = re.compile(r"ZERO-PLACEMENT", re.IGNORECASE)
PATH_COVERAGE_RE = re.compile(r"PATH-COVERAGE.*?(?:connectivity|coverage)=([0-9]+)", re.IGNORECASE)
PATH_NETWORK_CONNECTIVITY_RE = re.compile(r"\[PATH\]\s+network:.*connectivity=([0-9]+)%", re.IGNORECASE)


mcp = FastMCP(
    "SpecBillineirePlaytest",
    instructions=(
        "Workspace-local tools for Gradle builds, test execution, Paper test-server deployment, "
        "RCON commands, and headless Minecraft plugin playtests in the spec-billineire repository."
    ),
)


@dataclass
class CommandResult:
    command: list[str]
    cwd: str
    exit_code: int
    duration_seconds: float
    stdout_tail: str
    stderr_tail: str


@dataclass
class PlaytestResult:
    command: list[str]
    exit_code: int
    duration_seconds: float
    village_ids: list[str]
    structure_receipts: int
    site_rejections: int
    path_failures: int
    zero_placement_events: int
    path_connectivity: int | None
    snapshot_file: str | None
    latest_log_path: str | None
    notable_lines: list[str]
    output_tail: str
    log_tail: str


def _tail_text(text: str, max_chars: int = 12000) -> str:
    if len(text) <= max_chars:
        return text
    return text[-max_chars:]


def _sanitize_text(text: str) -> str:
    if not text:
        return ""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", text)
    return text


def _powershell_exe() -> str:
    for name in ("pwsh", "powershell"):
        if shutil.which(name):
            return name
    raise RuntimeError("Neither pwsh nor powershell is available on PATH.")


def _run_command(command: list[str], cwd: Path, timeout_seconds: int) -> CommandResult:
    started = time.time()
    try:
        completed = subprocess.run(
            command,
            cwd=str(cwd),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout_seconds,
            check=False,
        )
        duration = round(time.time() - started, 2)
        return CommandResult(
            command=command,
            cwd=str(cwd),
            exit_code=completed.returncode,
            duration_seconds=duration,
            stdout_tail=_tail_text(_sanitize_text(completed.stdout)),
            stderr_tail=_tail_text(_sanitize_text(completed.stderr)),
        )
    except subprocess.TimeoutExpired as exc:
        duration = round(time.time() - started, 2)
        stdout = _sanitize_text(exc.stdout or "")
        stderr = _sanitize_text(exc.stderr or "")
        timeout_message = f"Command timed out after {timeout_seconds}s"
        stdout_tail = _tail_text(f"{stdout}\n{timeout_message}" if stdout else timeout_message)
        stderr_tail = _tail_text(stderr)
        return CommandResult(
            command=command,
            cwd=str(cwd),
            exit_code=124,
            duration_seconds=duration,
            stdout_tail=stdout_tail,
            stderr_tail=stderr_tail,
        )


def _latest_plugin_jar() -> Path:
    build_libs = PLUGIN_DIR / "build" / "libs"
    jars = sorted(
        [
            path
            for path in build_libs.glob("*.jar")
            if not path.name.endswith("-sources.jar") and not path.name.endswith("-javadoc.jar")
        ],
        key=lambda item: item.stat().st_mtime,
        reverse=True,
    )
    if not jars:
        raise FileNotFoundError(f"No plugin jar found in {build_libs}")
    return jars[0]


def _deploy_plugin_jar_internal() -> dict[str, str]:
    source = _latest_plugin_jar()
    plugins_dir = TEST_SERVER_DIR / "plugins"
    plugins_dir.mkdir(parents=True, exist_ok=True)
    destination = plugins_dir / "VillageOverhaul.jar"
    shutil.copy2(source, destination)

    remapped_dir = plugins_dir / ".paper-remapped"
    if remapped_dir.exists():
        for child in remapped_dir.iterdir():
            if child.is_dir():
                shutil.rmtree(child, ignore_errors=True)
            else:
                child.unlink(missing_ok=True)

    return {
        "source": str(source),
        "destination": str(destination),
    }


def _read_latest_log(lines: int = 300) -> str:
    if not LOG_PATH.exists():
        return ""
    content = LOG_PATH.read_text(encoding="utf-8", errors="replace")
    log_lines = content.splitlines()
    return _sanitize_text("\n".join(log_lines[-lines:]))


def _read_server_properties() -> dict[str, str]:
    properties_path = TEST_SERVER_DIR / "server.properties"
    if not properties_path.exists():
        raise FileNotFoundError(f"Missing server properties: {properties_path}")

    values: dict[str, str] = {}
    for line in properties_path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def _send_rcon(command: str, host: str = "127.0.0.1", port: int | None = None) -> str:
    properties = _read_server_properties()
    password = properties.get("rcon.password")
    if not password:
        raise RuntimeError("rcon.password is not configured in test-server/server.properties")

    actual_port = port or int(properties.get("rcon.port", "25575"))

    def send_packet(sock: socket.socket, request_id: int, packet_type: int, body: str) -> None:
        body_bytes = body.encode("ascii", errors="ignore")
        packet = struct.pack("<iii", 10 + len(body_bytes), request_id, packet_type) + body_bytes + b"\x00\x00"
        sock.sendall(packet)

    def read_packet(sock: socket.socket) -> tuple[int, int, str]:
        size_data = sock.recv(4)
        if len(size_data) != 4:
            raise RuntimeError("Failed to read RCON packet size")
        size = struct.unpack("<i", size_data)[0]
        payload = bytearray()
        while len(payload) < size:
            chunk = sock.recv(size - len(payload))
            if not chunk:
                break
            payload.extend(chunk)
        if len(payload) < 8:
            raise RuntimeError("Failed to read complete RCON payload")
        request_id, response_type = struct.unpack("<ii", payload[:8])
        body = bytes(payload[8:-2]).decode("utf-8", errors="replace") if len(payload) > 10 else ""
        return request_id, response_type, body

    with socket.create_connection((host, actual_port), timeout=10) as sock:
        send_packet(sock, 1, 3, password)
        auth_id, _, _ = read_packet(sock)
        if auth_id == -1:
            raise RuntimeError("RCON authentication failed")

        send_packet(sock, 2, 2, command)
        response_id, _, body = read_packet(sock)
        if response_id == -1:
            raise RuntimeError("RCON command execution failed")
        return _sanitize_text(body)


def _extract_playtest_summary(command: list[str], exit_code: int, duration_seconds: float, output_text: str, snapshot_file: str | None) -> PlaytestResult:
    log_tail = _read_latest_log(lines=400)
    combined = _sanitize_text(output_text + "\n" + log_tail)

    village_ids = sorted(set(match.group(0).lower() for match in UUID_RE.finditer(combined)))

    receipts: set[str] = set()
    for match in RECEIPT_RE.finditer(log_tail):
        receipts.add(f"{match.group(1)}@{match.group(2)},{match.group(3)},{match.group(4)}")
    for match in RECEIPT_FALLBACK_RE.finditer(log_tail):
        receipts.add(f"{match.group(1)}@{match.group(2)},{match.group(4)},{match.group(6)}")

    fixed_layout_receipts = 0
    fixed_layout_match = FIXED_LAYOUT_RECEIPTS_RE.search(combined)
    if fixed_layout_match:
        fixed_layout_receipts = int(fixed_layout_match.group(1))

    path_connectivity: int | None = None
    connectivity_match = PATH_COVERAGE_RE.search(combined)
    if connectivity_match:
        path_connectivity = int(connectivity_match.group(1))
    else:
        network_match = PATH_NETWORK_CONNECTIVITY_RE.search(combined)
        if network_match:
            path_connectivity = int(network_match.group(1))

    notable_markers = (
        "[STRUCT]",
        "[PATH]",
        "[GEN-QUEUE]",
        "[SITE-REJECT]",
        "PATH-COVERAGE",
        "ZERO-PLACEMENT",
        "Village placement complete",
        "Successfully generated village",
        "A* failed",
    )
    notable_lines = [
        line
        for line in combined.splitlines()
        if any(marker in line for marker in notable_markers)
    ][-60:]

    return PlaytestResult(
        command=command,
        exit_code=exit_code,
        duration_seconds=duration_seconds,
        village_ids=village_ids,
        structure_receipts=max(len(receipts), fixed_layout_receipts),
        site_rejections=len(SITE_REJECT_RE.findall(combined)),
        path_failures=len(PATH_FAIL_RE.findall(combined)),
        zero_placement_events=len(ZERO_PLACEMENT_RE.findall(combined)),
        path_connectivity=path_connectivity,
        snapshot_file=snapshot_file,
        latest_log_path=str(LOG_PATH) if LOG_PATH.exists() else None,
        notable_lines=notable_lines,
        output_tail=_tail_text(_sanitize_text(output_text)),
        log_tail=_tail_text(log_tail),
    )


@mcp.tool()
def workspace_status() -> dict[str, Any]:
    """Return high-level status for the local build and playtest environment."""
    return {
        "repoRoot": str(REPO_ROOT),
        "pluginDir": str(PLUGIN_DIR),
        "testServerDir": str(TEST_SERVER_DIR),
        "latestLogPath": str(LOG_PATH),
        "paths": {
            "gradlew": str(PLUGIN_DIR / "gradlew.bat"),
            "buildAndTest": str(SCRIPTS_DIR / "build-and-test.ps1"),
            "fastVillageGeneration": str(SCRIPTS_DIR / "test-village-generation.ps1"),
            "runScenario": str(SCRIPTS_DIR / "run-scenario.ps1"),
        },
        "exists": {
            "pluginDir": PLUGIN_DIR.exists(),
            "testServerDir": TEST_SERVER_DIR.exists(),
            "latestLog": LOG_PATH.exists(),
            "serverProperties": (TEST_SERVER_DIR / "server.properties").exists(),
        },
        "commands": {
            "pwsh": shutil.which("pwsh") is not None,
            "powershell": shutil.which("powershell") is not None,
            "java": shutil.which("java") is not None,
            "uv": shutil.which("uv") is not None,
            "git": shutil.which("git") is not None,
        },
        "timeouts": {
            "build": DEFAULT_BUILD_TIMEOUT_SECONDS,
            "test": DEFAULT_TEST_TIMEOUT_SECONDS,
            "fastPlaytest": DEFAULT_FAST_PLAYTEST_TIMEOUT_SECONDS,
            "scenario": DEFAULT_SCENARIO_TIMEOUT_SECONDS,
        },
    }


@mcp.tool()
def gradle_build(skip_tests: bool = False, clean: bool = True, timeout_seconds: int = DEFAULT_BUILD_TIMEOUT_SECONDS) -> CommandResult:
    """Run the plugin Gradle build from the plugin directory."""
    command = [str(PLUGIN_DIR / "gradlew.bat"), "--console=plain"]
    if clean:
        command.append("clean")
    command.append("build")
    if skip_tests:
        command.extend(["-x", "test"])
    return _run_command(command, cwd=PLUGIN_DIR, timeout_seconds=timeout_seconds)


@mcp.tool()
def gradle_test(suite: str = "all", timeout_seconds: int = DEFAULT_TEST_TIMEOUT_SECONDS) -> CommandResult:
    """Run Gradle tests: all, unit, or integration."""
    suite_map = {
        "all": "test",
        "unit": "testUnit",
        "integration": "testIntegration",
    }
    if suite not in suite_map:
        raise ValueError("suite must be one of: all, unit, integration")
    command = [str(PLUGIN_DIR / "gradlew.bat"), "--console=plain", suite_map[suite]]
    return _run_command(command, cwd=PLUGIN_DIR, timeout_seconds=timeout_seconds)


@mcp.tool()
def deploy_plugin_jar() -> dict[str, str]:
    """Copy the newest built plugin jar into the Paper test server plugins directory."""
    return _deploy_plugin_jar_internal()


@mcp.tool()
def run_fast_village_generation(
    seed: int = 12345,
    culture: str = "roman",
    village_name: str = "AIAudit",
    max_wait_seconds: int = 60,
    expected_structures: int = 5,
    min_expected_structures: int = 1,
    max_bounds_radius_blocks: int = 0,
    existing_village_fill_in: bool = False,
    build_first: bool = True,
    build_timeout_seconds: int = DEFAULT_BUILD_TIMEOUT_SECONDS,
    timeout_seconds: int = DEFAULT_FAST_PLAYTEST_TIMEOUT_SECONDS,
) -> PlaytestResult:
    """Build, deploy, and run the fast village generation harness."""
    if build_first:
        build_result = gradle_build(skip_tests=False, clean=True, timeout_seconds=build_timeout_seconds)
        if build_result.exit_code != 0:
            return _extract_playtest_summary(
                build_result.command,
                build_result.exit_code,
                build_result.duration_seconds,
                build_result.stdout_tail + "\n" + build_result.stderr_tail,
                snapshot_file=None,
            )

    _deploy_plugin_jar_internal()

    script_path = SCRIPTS_DIR / "test-village-generation.ps1"
    command = [
        _powershell_exe(),
        "-NoProfile",
        "-File",
        str(script_path),
        "-Seed",
        str(seed),
        "-Culture",
        culture,
        "-VillageName",
        village_name,
        "-MaxWaitSeconds",
        str(max_wait_seconds),
        "-ExpectedStructures",
        str(expected_structures),
        "-MinExpectedStructures",
        str(min_expected_structures),
    ]
    if max_bounds_radius_blocks > 0:
        command.extend(["-MaxBoundsRadiusBlocks", str(max_bounds_radius_blocks)])
    if existing_village_fill_in:
        command.append("-ExistingVillageFillIn")

    result = _run_command(command, cwd=REPO_ROOT, timeout_seconds=timeout_seconds)
    return _extract_playtest_summary(
        result.command,
        result.exit_code,
        result.duration_seconds,
        result.stdout_tail + "\n" + result.stderr_tail,
        snapshot_file=None,
    )


@mcp.tool()
def run_headless_scenario(
    ticks: int = 3000,
    seed: int = 12345,
    snapshot_file: str = "state-snapshot.json",
    auto_commands: list[str] | None = None,
    stop_when: str = "",
    fixed_layout: bool = False,
    fixed_layout_count: int = 3,
    max_bounds_radius_blocks: int = 0,
    build_first: bool = True,
    build_timeout_seconds: int = DEFAULT_BUILD_TIMEOUT_SECONDS,
    timeout_seconds: int = DEFAULT_SCENARIO_TIMEOUT_SECONDS,
) -> PlaytestResult:
    """Build, deploy, and run the full headless Paper scenario harness."""
    if build_first:
        build_result = gradle_build(skip_tests=False, clean=True, timeout_seconds=build_timeout_seconds)
        if build_result.exit_code != 0:
            return _extract_playtest_summary(
                build_result.command,
                build_result.exit_code,
                build_result.duration_seconds,
                build_result.stdout_tail + "\n" + build_result.stderr_tail,
                snapshot_file=None,
            )

    _deploy_plugin_jar_internal()

    script_path = SCRIPTS_DIR / "run-scenario.ps1"
    snapshot_path = REPO_ROOT / snapshot_file
    command = [
        _powershell_exe(),
        "-NoProfile",
        "-File",
        str(script_path),
        "-Ticks",
        str(ticks),
        "-Seed",
        str(seed),
        "-SnapshotFile",
        str(snapshot_path),
    ]
    if stop_when:
        command.extend(["-StopWhen", stop_when])
    if fixed_layout:
        command.append("-FixedLayout")
        command.extend(["-FixedLayoutCount", str(fixed_layout_count)])
    if max_bounds_radius_blocks > 0:
        command.extend(["-MaxBoundsRadiusBlocks", str(max_bounds_radius_blocks)])
    if auto_commands:
        command.append("-AutoCommands")
        command.extend(auto_commands)

    result = _run_command(command, cwd=REPO_ROOT, timeout_seconds=timeout_seconds)
    return _extract_playtest_summary(
        result.command,
        result.exit_code,
        result.duration_seconds,
        result.stdout_tail + "\n" + result.stderr_tail,
        snapshot_file=str(snapshot_path) if snapshot_path.exists() else None,
    )


@mcp.tool()
def send_rcon_command(command: str, host: str = "127.0.0.1", port: int | None = None) -> dict[str, str]:
    """Send a direct RCON command to the running Paper test server."""
    response = _send_rcon(command=command, host=host, port=port)
    return {
        "command": command,
        "response": response,
    }


@mcp.tool()
def inspect_latest_playtest(lines: int = 300) -> dict[str, Any]:
    """Return a compact summary of the latest server log and recent playtest signals."""
    log_tail = _read_latest_log(lines=lines)
    village_ids = sorted(set(match.group(0).lower() for match in UUID_RE.finditer(log_tail)))
    fixed_layout_receipts = 0
    fixed_layout_match = FIXED_LAYOUT_RECEIPTS_RE.search(log_tail)
    if fixed_layout_match:
        fixed_layout_receipts = int(fixed_layout_match.group(1))

    path_connectivity: int | None = None
    path_coverage_match = PATH_COVERAGE_RE.search(log_tail)
    if path_coverage_match:
        path_connectivity = int(path_coverage_match.group(1))
    else:
        network_match = PATH_NETWORK_CONNECTIVITY_RE.search(log_tail)
        if network_match:
            path_connectivity = int(network_match.group(1))

    notable_markers = (
        "[STRUCT]",
        "[PATH]",
        "[GEN-QUEUE]",
        "[SITE-REJECT]",
        "PATH-COVERAGE",
        "ZERO-PLACEMENT",
        "Village placement complete",
        "Successfully generated village",
        "A* failed",
    )
    notable_lines = [
        line
        for line in log_tail.splitlines()
        if any(marker in line for marker in notable_markers)
    ][-60:]

    return {
        "latestLogPath": str(LOG_PATH) if LOG_PATH.exists() else None,
        "villageIds": village_ids,
        "structureReceipts": max(
            len(RECEIPT_RE.findall(log_tail)) + len(RECEIPT_FALLBACK_RE.findall(log_tail)),
            fixed_layout_receipts,
        ),
        "siteRejections": len(SITE_REJECT_RE.findall(log_tail)),
        "pathFailures": len(PATH_FAIL_RE.findall(log_tail)),
        "pathConnectivity": path_connectivity,
        "zeroPlacementEvents": len(ZERO_PLACEMENT_RE.findall(log_tail)),
        "notableLines": notable_lines,
        "logTail": _tail_text(log_tail),
    }


if __name__ == "__main__":
    mcp.run()