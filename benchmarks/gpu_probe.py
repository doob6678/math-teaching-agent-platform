"""Record a real Windows NVIDIA/PyTorch compatibility probe for benchmark handoffs."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path


def main() -> None:
    result: dict[str, object] = {"python": sys.executable}
    try:
        output = subprocess.run(
            ["nvidia-smi", "--query-gpu=name,memory.total,memory.free,driver_version", "--format=csv,noheader"],
            capture_output=True,
            text=True,
            check=True,
            timeout=20,
        )
        result["nvidiaSmi"] = output.stdout.strip()
    except Exception as exc:
        result["nvidiaSmiError"] = f"{type(exc).__name__}: {exc}"
    try:
        import torch

        result["torch"] = torch.__version__
        result["torchCudaAvailable"] = bool(torch.cuda.is_available())
        result["torchCudaVersion"] = torch.version.cuda
        result["torchArchList"] = list(torch.cuda.get_arch_list()) if torch.cuda.is_available() else []
        if torch.cuda.is_available():
            result["deviceName"] = torch.cuda.get_device_name(0)
            try:
                value = torch.randn(64, 64, device="cuda") @ torch.randn(64, 64, device="cuda")
                torch.cuda.synchronize()
                result["kernelProbe"] = {"ok": True, "mean": float(value.mean()), "memoryAllocated": int(torch.cuda.memory_allocated(0))}
            except Exception as exc:
                result["kernelProbe"] = {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
    except Exception as exc:
        result["torchError"] = f"{type(exc).__name__}: {exc}"
    output_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("output") / "benchmarks" / "gpu-probe.json"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
