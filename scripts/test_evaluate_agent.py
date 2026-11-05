import importlib.util
import json
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


class AnswerKnowledgeBoundaryTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        scenarios_path = SCRIPT_PATH.parent.parent / "evaluation" / "agent_scenarios.json"
        cls.scenarios = json.loads(scenarios_path.read_text(encoding="utf-8"))
        cls.ag10 = next(scenario for scenario in cls.scenarios if scenario["id"] == "AG-10")
        cls.expected = cls.ag10["turns"][0]["expected"]

    def evaluate(self, answer):
        return EVALUATOR.evaluate_turn(
            self.expected, 200,
            {"answer": answer, "toolUsed": False, "toolCalls": [], "sources": []},
        )

    def test_saved_live_answer_passes_semantic_evidence_boundary(self):
        old_live_answer = (
            "죄송하지만, 제공된 문서에는 GPU 서버 야간 사용 정책에 대한 정보가 "
            "포함되어 있지 않습니다. 해당 정책에 대한 질문은 답변할 수 없습니다."
        )

        self.assertEqual([], self.evaluate(old_live_answer))

    def test_equivalent_source_boundaries_with_different_wording_pass(self):
        examples = [
            "제공된 자료만으로는 GPU 서버 야간 이용 여부를 확인할 수 없습니다.",
            "GPU 서버 야간 사용 규정은 현재 문서에서 찾을 수 없어 답변 근거가 없습니다.",
            "문서에는 GPU 서버의 야간 정책이 안내되어 있지 않아 확인된 기준을 말씀드릴 수 없습니다.",
        ]

        for answer in examples:
            with self.subTest(answer=answer):
                self.assertEqual([], self.evaluate(answer))

    def test_holdout_wording_passes_without_copying_the_saved_response(self):
        answer = "GPU 서버의 야간 접속 규정은 제공된 근거에서 확인되지 않습니다."

        self.assertEqual([], self.evaluate(answer))

    def test_confident_unsupported_policy_claim_fails(self):
        answer = "GPU 서버는 야간에 오후 10시부터 자유롭게 이용할 수 있습니다."

        self.assertTrue(any("evidence" in failure or "unsupported" in failure
                            for failure in self.evaluate(answer)))

    def test_evidence_disclaimer_does_not_excuse_a_hallucinated_claim(self):
        answer = (
            "제공된 문서에는 GPU 서버 야간 정책이 없습니다. "
            "하지만 야간 사용은 오후 10시부터 허용됩니다."
        )

        self.assertTrue(any("unsupported policy claim" in failure for failure in self.evaluate(answer)))

    def test_unrelated_answer_with_absence_words_fails(self):
        answer = "GPU 서버 야간 이용은 안정적입니다. VPN 문서에는 관련 정보가 없습니다."

        self.assertTrue(any("no sentence links" in failure for failure in self.evaluate(answer)))

    def test_keyword_presence_without_evidence_boundary_fails(self):
        for answer in (
            "GPU 서버 야간 사용 정책을 확인했습니다.",
            "GPU 서버 야간 정책은 없습니다.",
        ):
            with self.subTest(answer=answer):
                self.assertTrue(any("no sentence links" in failure for failure in self.evaluate(answer)))

    def test_only_ag10_has_the_new_rubric_and_scenario_count_is_unchanged(self):
        turns = [turn for scenario in self.scenarios for turn in scenario["turns"]]

        self.assertEqual(15, len(self.scenarios))
        self.assertEqual(17, len(turns))
        self.assertIn("answerKnowledgeBoundary", self.expected)
        self.assertNotIn("answerContainsAny", self.expected)
        self.assertTrue(all(
            "answerKnowledgeBoundary" not in turn["expected"]
            for scenario in self.scenarios if scenario["id"] != "AG-10"
            for turn in scenario["turns"]
        ))


if __name__ == "__main__":
    unittest.main()
