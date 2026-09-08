import importlib.util
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).with_name("evaluate-agent.py")
SPEC = importlib.util.spec_from_file_location("evaluate_agent", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
EVALUATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVALUATOR)


class ToolEvaluationTest(unittest.TestCase):

    def test_correct_tool_name_passes(self):
        failures = self.evaluate(self.expected_access(), self.actual_access())

        self.assertEqual([], failures)

    def test_wrong_tool_name_fails(self):
        actual = self.actual_access()
        actual["toolCalls"][0]["toolName"] = "createTicket"

        failures = self.evaluate(self.expected_access(), actual)

        self.assertTrue(any("toolName" in failure for failure in failures))

    def test_correct_argument_passes(self):
        failures = self.evaluate(self.expected_access(), self.actual_access())

        self.assertFalse(any("toolArguments" in failure for failure in failures))

    def test_resource_mismatch_fails(self):
        actual = self.actual_access()
        actual["toolCalls"][0]["toolArguments"]["resource"] = "PROD_DB"

        failures = self.evaluate(self.expected_access(), actual)

        self.assertTrue(any("toolArguments" in failure for failure in failures))

    def test_ticket_type_mismatch_fails(self):
        expected = self.expected_ticket()
        actual = self.actual_ticket()
        actual["toolCalls"][0]["toolArguments"]["type"] = "INCIDENT"

        failures = self.evaluate(expected, actual)

        self.assertTrue(any("toolArguments" in failure for failure in failures))

    def test_execution_failure_fails(self):
        actual = self.actual_access()
        actual["toolCalls"][0]["success"] = False

        failures = self.evaluate(self.expected_access(), actual)

        self.assertTrue(any("success" in failure for failure in failures))

    def test_no_tool_expected_with_empty_calls_passes(self):
        expected = {"toolUsed": False}
        actual = {"answer": "정책 응답", "sources": [], "toolUsed": False, "toolCalls": []}

        self.assertEqual([], self.evaluate(expected, actual))

    def test_no_tool_expected_with_actual_call_fails(self):
        expected = {"toolUsed": False}
        actual = self.actual_access()
        actual["toolUsed"] = False

        failures = self.evaluate(expected, actual)

        self.assertTrue(any("expected empty list" in failure for failure in failures))

    def test_zero_latency_passes(self):
        actual = self.actual_access()
        actual["toolCalls"][0]["latencyMs"] = 0

        failures = self.evaluate(self.expected_access(), actual)

        self.assertFalse(any("latencyMs" in failure for failure in failures))

    def test_extra_actual_argument_is_ignored_and_summary_counts_are_dynamic(self):
        expected = self.expected_access()
        actual = self.actual_access()
        actual["toolCalls"][0]["toolArguments"]["futureField"] = "ignored"
        failures = self.evaluate(expected, actual)
        results = [{
            "category": "ACCESS",
            "passed": not failures,
            "turns": [{
                "expected": expected,
                "actual": actual,
                "latencyMs": 0,
                "passed": not failures,
                "failures": failures,
            }],
        }]

        summary = EVALUATOR.summarize(results)

        self.assertEqual([], failures)
        self.assertEqual((1, 1, 100.0), (
            summary["toolSelectionPassed"],
            summary["toolSelectionTotal"],
            summary["toolSelectionAccuracy"],
        ))
        self.assertEqual((1, 1, 100.0), (
            summary["toolArgumentPassed"],
            summary["toolArgumentTotal"],
            summary["toolArgumentAccuracy"],
        ))
        self.assertEqual((1, 1, 100.0), (
            summary["toolExecutionPassed"],
            summary["toolExecutionTotal"],
            summary["toolExecutionSuccessRate"],
        ))

    def evaluate(self, expected, actual):
        return EVALUATOR.evaluate_turn(expected, 200, actual)

    def expected_access(self):
        return {
            "toolUsed": True,
            "expectedToolCalls": [{
                "toolName": "getAccessStatus",
                "arguments": {"resource": "DEV_DB"},
                "success": True,
            }],
        }

    def actual_access(self):
        return {
            "answer": "DEV_DB 권한 상태는 APPROVED입니다.",
            "sources": [],
            "toolUsed": True,
            "toolCalls": [{
                "toolName": "getAccessStatus",
                "toolArguments": {"resource": "DEV_DB"},
                "toolResult": "DEV_DB 권한 상태는 APPROVED입니다.",
                "success": True,
                "latencyMs": 1,
            }],
        }

    def expected_ticket(self):
        return {
            "toolUsed": True,
            "expectedToolCalls": [{
                "toolName": "createTicket",
                "arguments": {"type": "ACCESS_REQUEST", "resource": "DEV_DB"},
                "success": True,
            }],
        }

    def actual_ticket(self):
        return {
            "answer": "DEV_DB 요청을 티켓으로 접수했습니다.",
            "sources": [],
            "toolUsed": True,
            "toolCalls": [{
                "toolName": "createTicket",
                "toolArguments": {
                    "type": "ACCESS_REQUEST",
                    "resource": "DEV_DB",
                },
                "toolResult": "DEV_DB 요청을 티켓으로 접수했습니다.",
                "success": True,
                "latencyMs": 1,
            }],
        }


if __name__ == "__main__":
    unittest.main()
