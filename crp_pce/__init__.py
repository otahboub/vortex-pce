"""
CRP-PCE: Constraint Relaxation Problem (CRP) & Data Flow Equilibrium (DFE) Powered PCE Engine
Open-Source Canonical Reference Implementation (Tahboub & Khan, 2009-2026)
"""

__version__ = "1.0.0"
__author__ = "Dr. Omar Y. Tahboub and Dr. Javed I. Khan"

from .dfe import DFEEngine, SpaceTimeLedger
from .planner import FourStagePCEPlanner
from .pcep import PCEPStatefulServer
from .adapters import MPLSSRv6PCEPAdapter, SDNHypervisorAdapter, LinuxKernelEBPFAdapter

__all__ = [
    "DFEEngine",
    "SpaceTimeLedger",
    "FourStagePCEPlanner",
    "PCEPStatefulServer",
    "MPLSSRv6PCEPAdapter",
    "SDNHypervisorAdapter",
    "LinuxKernelEBPFAdapter",
]
