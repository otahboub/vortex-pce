import pathlib
import re

from setuptools import setup, find_packages

POM = pathlib.Path(__file__).parent / "pom.xml"


def read_version():
    """Read the version from pom.xml, the single source of truth for this project.

    Keeping a second literal here is how the Java runtime, the Python SDK, and the
    published image drift apart; a released artifact whose reported version does not
    match its contents cannot be traced back during an incident.
    """
    text = POM.read_text(encoding="utf-8")
    # The first <version> after </artifactId> at project level is the project version;
    # dependency versions live inside <dependencies>, which appears later.
    match = re.search(
        r"<artifactId>dcn-pce-controller</artifactId>\s*<version>([^<]+)</version>", text
    )
    if not match:
        raise RuntimeError("Unable to read the project version from pom.xml")
    # PEP 440 has no -SNAPSHOT; map it to a development release.
    return match.group(1).strip().replace("-SNAPSHOT", ".dev0")


setup(
    name="crp-pce",
    version=read_version(),
    description="Constraint Relaxation Problem (CRP) & Data Flow Equilibrium (DFE) Powered PCE Engine",
    author="Dr. Omar Y. Tahboub and Dr. Javed I. Khan",
    url="https://github.com/otahboub/vortex-pce",

    packages=find_packages(),
    # CI and release exercise 3.10 only. Claiming 3.8 invites an installation that was never
    # tested; widen this again alongside a version matrix, not ahead of one.
    python_requires=">=3.10",
    entry_points={
        "console_scripts": [
            "crp-pce=crp_pce.cli:main",
        ],
    },
    classifiers=[
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.10",
        "License :: OSI Approved :: MIT License",
        "Operating System :: OS Independent",
        "Topic :: System :: Networking",
    ],
)
