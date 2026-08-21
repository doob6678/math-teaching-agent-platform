def health_response(ready: bool) -> dict[str, str]:
    """Returns only the externally safe readiness state; model and device details remain internal."""
    return {
        "status": "UP" if ready else "DOWN",
        "service": "math-agent-rag-worker",
    }
