import { describe, expect, it } from "vitest";
import { statusClass, statusTone } from "./App";

describe("runtime status classification", () => {
  it("does not render partial readiness as successful", () => {
    expect(statusTone("not_ready")).toBe("warn");
    expect(statusTone("ready_to_index")).toBe("warn");
    expect(statusClass("ready_to_index")).toBe("unknown");
  });

  it("renders explicit configuration failures as danger", () => {
    expect(statusTone("configuration_error")).toBe("danger");
    expect(statusClass("configuration_error")).toBe("failed");
  });

  it("renders proven ready states as good", () => {
    expect(statusTone("searchable")).toBe("good");
    expect(statusTone("process_ready")).toBe("good");
    expect(statusTone("deploy_ready")).toBe("good");
  });
});
