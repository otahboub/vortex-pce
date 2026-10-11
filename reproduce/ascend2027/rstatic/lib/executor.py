"""Where a benchmark command runs, so a study is not written against one host.

Every harness in this repository so far executes `docker exec <container> ...` against containers
on a Linux bridge, and reads the bottleneck's qdisc counters to learn what happened. Neither
survives a move to real instances: between two cloud regions there is no container to exec into
and no qdisc on the path to read.

That is a portability problem and a measurement problem at once, and the second matters more. A
router-side byte counter reports bytes dequeued, not transfers finished, so a study built on it
cannot answer whether a flow arrived complete and on time even on the host where it does work.

An executor is the seam. A study says "run this argv on the host playing role X" and does not know
whether that is a local container or an SSH connection to a remote host.
"""
import shlex
import subprocess
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence


@dataclass
class CommandResult:
    argv: Sequence[str]
    returncode: int
    stdout: str
    stderr: str

    @property
    def ok(self) -> bool:
        return self.returncode == 0

    def require(self, what: str) -> "CommandResult":
        """Raises unless the command succeeded, with enough context to diagnose it."""
        if not self.ok:
            raise RuntimeError(
                f"{what} failed (exit {self.returncode}): {' '.join(map(str, self.argv))}\n"
                f"stdout: {self.stdout.strip()}\nstderr: {self.stderr.strip()}")
        return self


class Executor:
    """Runs commands on one host."""

    name: str = "unnamed"
    #: Address other hosts should use to reach this one. On a bridge that is the container IP; on
    #: cloud instances it is usually the private-network address rather than the public one.
    reachable_at: str = ""

    def run(self, argv: Sequence[str], timeout: Optional[float] = None) -> CommandResult:
        raise NotImplementedError

    def popen(self, argv: Sequence[str]) -> subprocess.Popen:
        raise NotImplementedError

    def kill_matching(self, pattern: str) -> None:
        """Kills processes on the host, not merely a local handle to them.

        Every harness in this repository has been bitten by the difference. `Popen.kill()` reaps
        the local `docker exec` or `ssh` wrapper and leaves the remote process running, which then
        holds a port, keeps sending, or keeps rewriting a rate limit into the next measurement.
        """
        self.run(["pkill", "-f", pattern])


@dataclass
class LocalDockerExecutor(Executor):
    """A container on this host. What the existing studies use."""

    container: str
    reachable_at: str = ""
    name: str = field(default="", init=False)

    def __post_init__(self):
        self.name = self.container

    def _wrap(self, argv: Sequence[str]) -> List[str]:
        return ["docker", "exec", self.container, *map(str, argv)]

    def run(self, argv, timeout=None) -> CommandResult:
        completed = subprocess.run(self._wrap(argv), capture_output=True, text=True,
                                   timeout=timeout)
        return CommandResult(argv, completed.returncode, completed.stdout, completed.stderr)

    def popen(self, argv) -> subprocess.Popen:
        return subprocess.Popen(self._wrap(argv), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True)


@dataclass
class SshExecutor(Executor):
    """A remote instance: a cloud VM, or anything reachable by SSH.

    Options are chosen so a study cannot hang on a prompt: batch mode fails rather than asking for
    a password, and a connect timeout bounds an unreachable host. `StrictHostKeyChecking=accept-new`
    trusts a key on first sight, which is appropriate for instances created for a benchmark run and
    is stated here rather than hidden.
    """

    host: str
    user: str = "root"
    identity: Optional[str] = None
    reachable_at: str = ""
    connect_timeout: int = 10
    name: str = field(default="", init=False)

    def __post_init__(self):
        self.name = f"{self.user}@{self.host}"
        if not self.reachable_at:
            self.reachable_at = self.host

    def _wrap(self, argv: Sequence[str]) -> List[str]:
        ssh = ["ssh", "-o", "BatchMode=yes",
               "-o", "StrictHostKeyChecking=accept-new",
               "-o", f"ConnectTimeout={self.connect_timeout}"]
        if self.identity:
            ssh += ["-i", self.identity]
        # Quoted as one remote command: the remote shell re-parses it, so an unquoted argument
        # containing a space would silently become two.
        return ssh + [f"{self.user}@{self.host}", " ".join(shlex.quote(str(a)) for a in argv)]

    def run(self, argv, timeout=None) -> CommandResult:
        completed = subprocess.run(self._wrap(argv), capture_output=True, text=True,
                                   timeout=timeout)
        return CommandResult(argv, completed.returncode, completed.stdout, completed.stderr)

    def popen(self, argv) -> subprocess.Popen:
        return subprocess.Popen(self._wrap(argv), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True)


def from_inventory(inventory: Dict) -> Dict[str, Executor]:
    """Builds executors from a JSON inventory, so the same study runs anywhere.

    Local:  {"kind": "docker", "hosts": {"tx1": {"container": "bench-s1", "address": "172.31.0.2"}}}
    Remote: {"kind": "ssh",    "hosts": {"tx1": {"host": "203.0.113.2", "user": "root",
                                                  "address": "10.1.0.2", "identity": "~/.ssh/id"}}}

    The address is what *other* hosts use to reach this one. On cloud instances that should be the
    private-network address: measuring across the public interface measures the provider's egress
    path and, on most providers, is also billed.
    """
    kind = inventory.get("kind", "docker")
    executors: Dict[str, Executor] = {}
    for role, spec in inventory["hosts"].items():
        if kind == "docker":
            executors[role] = LocalDockerExecutor(container=spec["container"],
                                                  reachable_at=spec.get("address", ""))
        elif kind == "ssh":
            executors[role] = SshExecutor(host=spec["host"], user=spec.get("user", "root"),
                                          identity=spec.get("identity"),
                                          reachable_at=spec.get("address", spec["host"]))
        else:
            raise ValueError(f"unknown inventory kind: {kind!r}")
    return executors
