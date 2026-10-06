#!/bin/bash
# Quickstart Installer & Test Harness for VortexPCE Controller Engine
# Author: Dr. Omar Y. Tahboub (2026)

set -euo pipefail

echo "=================================================================================="
echo "   VORTEXPCE CONTROLLER ENGINE - QUICKSTART INSTALLER & BUILD HARNESS             "
echo "=================================================================================="
echo ""

# Auto-clone repository if executed outside a cloned source tree
if [ ! -d "src/main/java" ]; then
    echo "[*] Cloning VortexPCE repository from GitHub..."
    git clone --quiet https://github.com/otahboub/vortex-pce.git
    cd vortex-pce
fi

echo "[*] Checking Maven and Java installations..."
if ! command -v mvn &> /dev/null; then
    echo "[-] Apache Maven with OpenJDK 17+ is required."
    exit 1
fi

echo "[*] Compiling VortexPCE Java Engine..."
mvn -B -DskipTests package dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/dependency

echo "[*] Installing Python SDK (crp_pce)..."
# Previously both commands discarded their output and forced success, then the script printed
# "Build Complete!" regardless. A failed Python build must be visible.
if ! python3 -m pip install --quiet setuptools setuptools_scm; then
    echo "[-] Failed to install Python build dependencies."
    exit 1
fi
if ! python3 setup.py build_py --quiet; then
    echo "[-] Failed to build the Python SDK (crp_pce)."
    exit 1
fi

echo ""
echo "=================================================================================="
echo "🎉 VortexPCE Build Complete!"
echo "   - Java Core Controller: target/classes/net/dcn/pce/Main.class"
echo "   - Launch HTTP Server  : java -cp 'target/classes:target/dependency/*' net.dcn.pce.Main --server"
echo "   - Health Checks       : http://localhost:8080/healthz"
echo "   - Compute API         : POST http://localhost:8080/api/v1/solve"
echo "=================================================================================="
