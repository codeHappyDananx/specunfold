#!/usr/bin/env python3
"""Exercise the packaged stdio server across specification edits and process restarts."""
import json
import hashlib
from pathlib import Path
import queue
import subprocess
import threading
import uuid

ROOT = Path(__file__).resolve().parents[1]


class Session:
    def __init__(self, workspace, log):
        self.workspace = workspace
        self.stderr = log.open("w", encoding="utf-8")
        self.process = subprocess.Popen(["java", "-Dfile.encoding=UTF-8", "-Dspecunfold.home=" + str(ROOT),
                                         "-jar", str(ROOT / "mcp-server/target/specunfold.jar")],
                                        cwd=str(workspace), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=self.stderr, universal_newlines=True, encoding="utf-8")
        self.lines = queue.Queue()
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()
        self.sequence = 0
        result = self.rpc("initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                                         "clientInfo": {"name": "specunfold-smoke", "version": "1"}})
        assert result["protocolVersion"] == "2025-11-25"
        self.send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        tools = self.rpc("tools/list", {})["tools"]
        assert [tool["name"] for tool in tools] == ["workflow_ops"]

    def read(self):
        for line in self.process.stdout:
            self.lines.put(line)
        self.lines.put(None)

    def send(self, value):
        self.process.stdin.write(json.dumps(value) + "\n")
        self.process.stdin.flush()

    def rpc(self, method, params):
        self.sequence += 1
        self.send({"jsonrpc": "2.0", "id": self.sequence, "method": method, "params": params})
        line = self.lines.get(timeout=30)
        assert line is not None, "Server exited before replying"
        budget = params.get("arguments", {}).get("maxResponseBytes", 16384)
        assert len(line.rstrip("\r\n").encode("utf-8")) <= budget, "Response exceeded its UTF-8 byte budget"
        response = json.loads(line)
        assert response["id"] == self.sequence and "error" not in response, response
        return response["result"]

    def call(self, action, **arguments):
        arguments.update(action=action, workspaceRoot=str(self.workspace), profile="example")
        if action == "bootstrap":
            # This automated example simulates a user's explicit request to start a workflow.
            arguments.update(workflowConfirmedByUser=True, workflowConfirmationSource="user_explicit_request")
        result = self.rpc("tools/call", {"name": "workflow_ops", "arguments": arguments})
        payload = result["structuredContent"]
        fragments = []
        while payload.get("result", {}).get("kind") == "json_fragment":
            page = payload["result"]
            assert not page["executable"]
            fragments.append(page["fragment"])
            if page["complete"]:
                combined = "".join(fragments)
                assert hashlib.sha256(combined.encode("utf-8")).hexdigest() == page["sha256"]
                payload = json.loads(combined)
                break
            result = self.rpc("tools/call", {"name": "workflow_ops", "arguments": page["next"]})
            payload = result["structuredContent"]
        assert not result["isError"] and payload["success"], payload
        return payload["result"]

    def close(self):
        self.process.stdin.close()
        try:
            assert self.process.wait(timeout=10) == 0
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait(timeout=10)
            self.process.stdout.close()
            self.stderr.close()
            self.reader.join(timeout=5)


def run(session, current, **arguments):
    return session.call("run", executionId=current["executionId"], nodeId=current["nodeId"],
                        specTicket=current["specTicket"], status="DONE", **arguments)


def main():
    output = ROOT / ".build" / ("smoke-" + uuid.uuid4().hex)
    workspace = output / "workspace"
    workspace.mkdir(parents=True)
    subprocess.run(["node", str(ROOT / "bin/specunfold.js"), "install", "--workspace", str(workspace),
                    "--profile", "example"], check=True, stdout=subprocess.PIPE, universal_newlines=True)
    report = {"passed": False, "workspace": str(workspace)}
    session = None
    try:
        session = Session(workspace, output / "server-1.log")
        assert session.call("workspace_probe")["profileAvailable"]
        assert session.call("validate")["status"] == "VALID"
        idle = session.call("context", intent="delivery review")
        assert idle["status"] == "IDLE" and not (workspace / ".specunfold").exists()
        ordinary = session.call("spec", intent="delivery review", includeContent=True, maxResponseBytes=1024)
        assert ordinary["status"] == "STATELESS" and all(spec["content"] for spec in ordinary["specs"])
        current = session.call("bootstrap", intent="delivery review", currentState={"goal": "delivery review", "constraints": ["workspace only"]}, maxResponseBytes=1024)
        execution = current["executionId"]
        assert current["currentState"]["goal"] == "delivery review"
        assert current["runTemplate"]["specTicket"] == current["specTicket"]
        assert all(name not in current for name in ("facts", "evidence", "history", "artifactPaths"))
        assert current["nodeId"] == "prepare" and len(current["specs"]) == 2
        assert all("content" not in spec for spec in current["specs"])
        full = session.call("spec", executionId=execution, includeContent=True)
        assert all(spec["content"] for spec in full["specs"])
        known = {spec["path"]: spec["sha256"] for spec in full["specs"]}
        repeated = session.call("spec", executionId=execution, includeContent=True, contextEpoch="smoke-session-1", knownSpecHashes=known)
        assert all(spec["unchanged"] and "content" not in spec for spec in repeated["specs"])
        specification = workspace / ".agents/profiles/example/spec/prepare.md"
        specification.write_text(specification.read_text(encoding="utf-8") + "\nReview the prepared artifact.\n", encoding="utf-8")
        checked = session.call("spec_check", executionId=execution, nodeId=current["nodeId"], specTicket=current["specTicket"])
        assert checked["status"] == "BLOCKED"
        stale = run(session, current, facts={"prepared": True}, evidence={"note": "prepared"}, artifactPaths=[])
        assert stale["status"] == "BLOCKED"
        current = session.call("spec", executionId=execution, includeContent=True)
        assert current["specTicket"] != full["specTicket"]
        blocked = run(session, current, facts={"prepared": False}, evidence={"note": "artifact missing"}, artifactPaths=[])
        assert blocked["status"] == "BLOCKED" and blocked["nodeId"] == "prepare"
        (workspace / "delivery.txt").write_text("Example delivery artifact\n", encoding="utf-8")
        current = run(session, blocked, facts={"prepared": True}, evidence={"note": "prepared file"}, artifactPaths=["delivery.txt"])
        assert current["nodeId"] == "confirm"
        assert run(session, current)["status"] == "BLOCKED"
        current = run(session, current, decision={"approved": False})
        assert current["status"] == "BLOCKED" and current["nodeId"] == "confirm"
        session.close()
        session = Session(workspace, output / "server-2.log")
        resumed = session.call("next", executionId=execution)
        assert resumed["nodeId"] == "confirm" and resumed["specTicket"] == current["specTicket"]
        startup = session.call("context")
        assert startup["executionId"] == execution and startup["nodeId"] == "confirm"
        assert startup["currentState"]["goal"] == "delivery review"
        state = session.call("state", executionId=execution)
        assert "history" not in state["state"] and state["state"]["historyCount"] > 0
        current = run(session, resumed, decision={"approved": True})
        assert current["nodeId"] == "verify"
        script = session.call("run_profile_script", scriptId="check_artifact", input={"artifactPath": "delivery.txt"})
        assert script["ok"] and script["exists"]
        current = run(session, current, facts={"verified": False}, evidence={"note": "verification not complete"}, artifactPaths=["delivery.txt"])
        assert current["status"] == "BLOCKED"
        current = run(session, current, facts={"verified": True}, evidence={"note": "declared script checked the artifact"}, artifactPaths=["delivery.txt"])
        assert current["status"] == "DONE"
        session.close()
        session = Session(workspace, output / "server-3.log")
        assert session.call("state", executionId=execution)["status"] == "DONE"
        report.update(passed=True, executionId=execution, checks=["stdio initialize/tools", "compact disclosure", "content on demand",
                      "conditional specification", "stale ticket blocked", "fact and artifact gates", "user decision gate",
                      "process restart resumption", "declared script", "durable completion", "stateless startup and spec reading",
                      "current-state restart snapshot", "direct run template", "context epoch disclosure deduplication",
                      "explicit state detail", "1024-byte response cap and lossless pagination"])
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        if session is not None and session.process.poll() is None:
            session.close()
        (ROOT / ".build/smoke-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
