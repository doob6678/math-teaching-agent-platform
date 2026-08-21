"""Bounded, non-disclosing self-review controller for handout model nodes.

The controller deliberately owns no teaching semantics. Callers supply the candidate
validator and prompts; this module only enforces one strict candidate/review envelope,
turn budgets, and fixed diagnostic codes. Candidate bodies and review text never enter
the returned metadata, so checkpoint events remain operational rather than instructional.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Callable
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any, TypeVar

from pydantic import BaseModel, ConfigDict, Field, StrictBool, ValidationError, field_validator


T = TypeVar("T")


class ModelPatchOperation(BaseModel):
    """One deliberately small RFC 6901-style mutation to the prior candidate."""

    model_config = ConfigDict(extra="forbid")

    op: str
    path: str
    value: Any | None = None

    @field_validator("op")
    @classmethod
    def operation_must_be_supported(cls, operation: str) -> str:
        if operation not in {"add", "remove", "replace"}:
            raise ValueError("unsupported patch operation")
        return operation

    @field_validator("path")
    @classmethod
    def path_must_be_non_root_pointer(cls, path: str) -> str:
        if not path.startswith("/") or path == "/":
            raise ValueError("patch path must target a nested JSON pointer")
        return path


class ModelPatchEnvelope(BaseModel):
    """A repair response that edits the controller-held candidate instead of rewriting it."""

    model_config = ConfigDict(extra="forbid")

    mode: str
    base_candidate_hash: str = Field(alias="baseCandidateHash", min_length=64, max_length=64)
    operations: list[ModelPatchOperation] = Field(min_length=1, max_length=32)
    review: "ModelReviewDecision"

    @field_validator("mode")
    @classmethod
    def mode_must_be_patch(cls, mode: str) -> str:
        if mode != "patch":
            raise ValueError("repair response must use patch mode")
        return mode


class ModelFullEnvelope(BaseModel):
    """A complete candidate returned for the initial or forced-final review turn."""

    model_config = ConfigDict(extra="forbid")

    mode: str
    candidate: Any
    review: "ModelReviewDecision"

    @field_validator("mode")
    @classmethod
    def mode_must_be_full(cls, mode: str) -> str:
        if mode != "full":
            raise ValueError("candidate response must use full mode")
        return mode

    @field_validator("candidate")
    @classmethod
    def candidate_must_be_a_complete_json_container(cls, candidate: Any) -> Any:
        if not isinstance(candidate, (dict, list)) or not candidate:
            raise ValueError("candidate must be a non-empty JSON object or array")
        return candidate



@lru_cache(maxsize=1)
def load_model_review_policy() -> dict[str, Any]:
    """Loads the versioned local policy once; malformed policy is a startup programming error."""
    policy_path = Path(__file__).with_name("model_review_policy.json")
    policy = json.loads(policy_path.read_text(encoding="utf-8"))
    required = {"profiles"}
    if not isinstance(policy, dict) or not required.issubset(policy) or not isinstance(policy["profiles"], dict):
        raise RuntimeError("model review policy is incomplete")
    for name, profile in policy["profiles"].items():
        if not isinstance(name, str) or not isinstance(profile, dict):
            raise RuntimeError("model review policy profile is invalid")
        required_profile = {"budget", "nodes", "repair", "blocking", "tool", "feedbackCodes"}
        if not required_profile.issubset(profile):
            raise RuntimeError("model review policy profile is incomplete")
        budget = profile["budget"]
        if not isinstance(budget, dict) or budget.get("globalTurns") != len(profile["nodes"]) * budget.get("perNodeTurns", 0):
            raise RuntimeError("model review policy must allocate each profile budget exactly")
        if not all(isinstance(code, str) and code.isupper() for code in profile["feedbackCodes"]):
            raise RuntimeError("model review policy contains an unsafe feedback code")
    return policy


def _profile(profile: str) -> dict[str, Any]:
    selected = load_model_review_policy()["profiles"].get(profile)
    if not isinstance(selected, dict):
        raise ValueError("model review profile is not configured")
    return selected


def _safe_codes(profile: str) -> frozenset[str]:
    return frozenset(_profile(profile)["feedbackCodes"])


class ModelReviewDecision(BaseModel):
    """The only permitted self-review decision; free-form feedback is intentionally forbidden."""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    approved: StrictBool
    feedback_codes: list[str] = Field(default_factory=list, alias="feedbackCodes", max_length=6)

    @field_validator("feedback_codes")
    @classmethod
    def feedback_codes_must_be_policy_values(cls, codes: list[str], info: Any) -> list[str]:
        profile = str(info.context.get("profile", "handout")) if info.context else "handout"
        if any(code not in _safe_codes(profile) for code in codes):
            raise ValueError("feedbackCodes contains a value outside the review policy")
        return list(dict.fromkeys(codes))


class ModelReviewEnvelope(BaseModel):
    """Legacy complete-candidate envelope retained for non-handout short-response profiles."""

    model_config = ConfigDict(extra="forbid")

    candidate: Any
    review: "ModelReviewDecision"

    @field_validator("candidate")
    @classmethod
    def candidate_must_be_a_complete_json_container(cls, candidate: Any) -> Any:
        if not isinstance(candidate, (dict, list)) or not candidate:
            raise ValueError("candidate must be a non-empty JSON object or array")
        return candidate


class ModelReviewExhausted(ValueError):
    """Signals deterministic exhaustion without retaining model or validation text."""

    def __init__(self, node: str) -> None:
        self.node = node
        super().__init__(node)


@dataclass(frozen=True)
class ModelReviewMetadata:
    """Safe checkpoint/event metadata. It contains no candidate or review prose."""

    node: str
    turns: int
    approved: bool
    feedback_codes: tuple[str, ...]
    candidate_hash: str = ""
    input_fingerprint: str = ""
    status: str = "APPROVED"

    def checkpoint_value(self) -> dict[str, Any]:
        return {
            "stage": self.node,
            "turns": self.turns,
            "approved": self.approved,
            "feedbackCodes": list(self.feedback_codes),
            "candidateHash": self.candidate_hash,
            "inputFingerprint": self.input_fingerprint,
        }

    def event_value(self) -> dict[str, Any]:
        return {
            "node": self.node,
            "turn": self.turns,
            "status": self.status,
            "feedbackCodes": list(self.feedback_codes),
        }


class BoundedModelReviewController:
    """Runs a full draft, bounded patch repairs, then one mandatory final full draft.

    The controller keeps the active candidate private. Repair turns mutate that candidate with
    a narrow JSON-Pointer patch, which prevents the model from regenerating the same document
    repeatedly while deterministic validation remains the final gate.
    """

    def __init__(self, node: str, profile: str = "handout") -> None:
        policy = _profile(profile)
        if node not in policy["nodes"]:
            raise ValueError("node is not eligible for model self-review")
        self.node = node
        self.profile = profile
        self.max_turns = int(policy["budget"]["perNodeTurns"])
        repair = policy["repair"]
        self.normal_turns = int(repair.get("normalTurns", self.max_turns))
        self.forced_final_turn = bool(repair.get("forcedFinalTurn", False))
        self.use_patch_repairs = profile == "handout" and self.forced_final_turn
        self.include_bounded_candidate = bool(repair["includeBoundedCandidate"])
        self.max_candidate_chars = int(repair["maxCandidateChars"])
        if self.forced_final_turn and self.max_turns != self.normal_turns + 1:
            raise RuntimeError("forced final review policy must reserve exactly one final turn")

    def execute(
        self,
        invoke: Callable[[str, int], tuple[Any, Any]],
        prompt_for_turn: Callable[[int, str | None, str, tuple[str, ...]], str],
        validate_candidate: Callable[[Any], T],
        event: Callable[[ModelReviewMetadata], None] | None = None,
        input_fingerprint: str = "",
    ) -> tuple[T, list[Any], ModelReviewMetadata]:
        """Returns a validated candidate or a stable exhaustion signal.

        Turn one is a complete draft. Normal repair turns accept only patch envelopes. The
        reserved final turn accepts only a complete, self-approved candidate and is never
        skipped because an earlier repair was malformed.
        """
        active_candidate: Any | None = None
        active_hash = ""
        feedback_codes: tuple[str, ...] = ()
        usages: list[Any] = []
        seen_states: set[tuple[str, tuple[str, ...]]] = set()

        for turn in range(1, self.max_turns + 1):
            is_forced_final = self.forced_final_turn and turn == self.max_turns
            candidate_for_prompt = self._serialized_candidate(active_candidate)
            raw, usage = invoke(prompt_for_turn(turn, candidate_for_prompt, active_hash, feedback_codes), turn)
            usages.append(usage)
            try:
                if not self.use_patch_repairs:
                    envelope = ModelReviewEnvelope.model_validate(raw, context={"profile": self.profile})
                    candidate_value = envelope.candidate
                elif turn == 1 or is_forced_final or active_candidate is None:
                    envelope = ModelFullEnvelope.model_validate(raw, context={"profile": self.profile})
                    if is_forced_final and not envelope.review.approved:
                        raise ValueError("forced final candidate must approve itself")
                    candidate_value = envelope.candidate
                else:
                    envelope = ModelPatchEnvelope.model_validate(raw, context={"profile": self.profile})
                    if envelope.base_candidate_hash != active_hash:
                        raise ValueError("patch base hash does not match active candidate")
                    candidate_value = self._apply_patch(active_candidate, envelope.operations)
            except (ValidationError, ValueError, TypeError):
                feedback_codes = ("ENVELOPE_INVALID",)
                self._emit(event, turn, "RETRY", feedback_codes)
                continue

            candidate_hash = self._candidate_hash(candidate_value)
            codes = tuple(dict.fromkeys(envelope.review.feedback_codes))
            active_candidate, active_hash = candidate_value, candidate_hash
            if not envelope.review.approved:
                feedback_codes = tuple(dict.fromkeys(["REVIEW_NOT_APPROVED", *codes]))
                self._emit(event, turn, "RETRY", feedback_codes)
                if not is_forced_final and (candidate_hash, feedback_codes) in seen_states:
                    feedback_codes = tuple(dict.fromkeys([*feedback_codes, "CANDIDATE_MISMATCH"]))
                    self._emit(event, turn, "EARLY_STOP", feedback_codes)
                seen_states.add((candidate_hash, feedback_codes))
                continue

            try:
                candidate = validate_candidate(candidate_value)
            except (ValidationError, ValueError):
                feedback_codes = tuple(dict.fromkeys([self._candidate_error_code(candidate_value), *codes]))
                self._emit(event, turn, "RETRY", feedback_codes)
                if not is_forced_final and (candidate_hash, feedback_codes) in seen_states:
                    feedback_codes = tuple(dict.fromkeys([*feedback_codes, "CANDIDATE_MISMATCH"]))
                    self._emit(event, turn, "EARLY_STOP", feedback_codes)
                seen_states.add((candidate_hash, feedback_codes))
                continue

            metadata = ModelReviewMetadata(
                node=self.node,
                turns=turn,
                approved=True,
                feedback_codes=codes,
                candidate_hash=candidate_hash,
                input_fingerprint=input_fingerprint,
                status="APPROVED",
            )
            self._emit(event, turn, "APPROVED", metadata.feedback_codes)
            return candidate, usages, metadata

        raise ModelReviewExhausted(self.node)

    def _emit(self, event: Callable[[ModelReviewMetadata], None] | None, turn: int, status: str,
              feedback_codes: tuple[str, ...]) -> None:
        if event is not None:
            event(ModelReviewMetadata(
                node=self.node,
                turns=turn,
                approved=status == "APPROVED",
                feedback_codes=feedback_codes,
                status=status,
            ))

    def _serialized_candidate(self, candidate: Any | None) -> str | None:
        """Returns the complete active candidate only for a patch repair prompt."""
        if candidate is None or not self.include_bounded_candidate:
            return None
        rendered = self._canonical_json(candidate)
        if len(rendered) > self.max_candidate_chars:
            # A truncated base makes a JSON Patch unsafe because its hash cannot identify the
            # actual object. The next model turn must instead be the forced complete candidate.
            return None
        return rendered

    @classmethod
    def _candidate_hash(cls, candidate: Any) -> str:
        return hashlib.sha256(cls._canonical_json(candidate).encode("utf-8")).hexdigest()

    @staticmethod
    def _canonical_json(value: Any) -> str:
        return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)

    @classmethod
    def _apply_patch(cls, candidate: Any, operations: list[ModelPatchOperation]) -> Any:
        """Applies a small JSON Pointer patch atomically and rejects no-op mutations."""
        working = json.loads(cls._canonical_json(candidate))
        before_hash = cls._candidate_hash(working)
        paths: set[str] = set()
        for operation in operations:
            if operation.path in paths:
                raise ValueError("patch may not mutate one path twice")
            paths.add(operation.path)
            parent, token = cls._patch_parent(working, operation.path)
            if isinstance(parent, dict):
                if operation.op == "add":
                    parent[token] = operation.value
                elif operation.op == "replace":
                    if token not in parent:
                        raise ValueError("replace path is absent")
                    parent[token] = operation.value
                else:
                    if token not in parent:
                        raise ValueError("remove path is absent")
                    del parent[token]
                continue
            if not isinstance(parent, list):
                raise ValueError("patch parent is not a container")
            if token == "-":
                if operation.op != "add":
                    raise ValueError("dash array path supports add only")
                parent.append(operation.value)
                continue
            try:
                index = int(token)
            except ValueError as error:
                raise ValueError("array path is not numeric") from error
            if index < 0 or index > len(parent) or (operation.op != "add" and index == len(parent)):
                raise ValueError("array path is out of range")
            if operation.op == "add":
                parent.insert(index, operation.value)
            elif operation.op == "replace":
                parent[index] = operation.value
            else:
                parent.pop(index)
        if cls._candidate_hash(working) == before_hash:
            raise ValueError("patch did not change the candidate")
        return working

    @staticmethod
    def _patch_parent(candidate: Any, pointer: str) -> tuple[Any, str]:
        tokens = [item.replace("~1", "/").replace("~0", "~") for item in pointer[1:].split("/")]
        if not tokens or any("~" in item.replace("~0", "").replace("~1", "") for item in tokens):
            raise ValueError("patch pointer is invalid")
        parent = candidate
        for token in tokens[:-1]:
            if isinstance(parent, dict):
                if token not in parent:
                    raise ValueError("patch parent path is absent")
                parent = parent[token]
            elif isinstance(parent, list):
                try:
                    index = int(token)
                except ValueError as error:
                    raise ValueError("array parent path is not numeric") from error
                if index < 0 or index >= len(parent):
                    raise ValueError("array parent path is out of range")
                parent = parent[index]
            else:
                raise ValueError("patch traverses a scalar")
        return parent, tokens[-1]

    @staticmethod
    def _candidate_error_code(candidate: Any) -> str:
        if not isinstance(candidate, (dict, list)):
            return "CANDIDATE_INVALID"
        return "CANDIDATE_INCOMPLETE"
