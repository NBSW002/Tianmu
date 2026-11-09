import unittest
from summarize_model_trace import summarize


class TraceSummaryTest(unittest.TestCase):
    prices = {"currency": "USD", "models": {"fixture": {"minimum": {"cache_hit": .1, "cache_miss": 1, "output": 2},
                                                      "maximum": {"cache_hit": .2, "cache_miss": 2, "output": 4}}}}
    def span(self, id, **attrs):
        return dict(traceId="trace", spanId=id, parentSpanId="root", name="model.request", startTimeUnixNano=0, endTimeUnixNano=100,
                    attributes={"run.id": "run", "model.name": "fixture", "model.dispatched": True, **attrs})

    def test_cache_price_duplicate_delivery_and_root_totals(self):
        call = self.span("a", **{"model.input_tokens": 100, "model.output_tokens": 10, "model.cache_hit_tokens": 60, "model.cache_miss_tokens": 40})
        root = {**self.span("root"), "name": "agent.run", "attributes": {"run.id": "run", "usage.input_tokens": 99999}}
        result = summarize([call, call, root], self.prices)["runs"][0]
        self.assertEqual(1, result["calls"])
        self.assertEqual(100, result["tokens_reported"]["input_tokens"])
        self.assertEqual(["0.000066", "0.000132"], result["estimated_cost_known_calls_range"])

    def test_failure_and_unknown_model_are_not_free(self):
        result = summarize([self.span("failure"), self.span("unknown", **{"model.name": "other", "model.input_tokens": 100, "model.output_tokens": 10})], self.prices)["runs"][0]
        self.assertFalse(result["usage_complete"])
        self.assertEqual(2, result["unpriced_calls"])
        self.assertIsNone(result["estimated_cost_known_calls_range"])
        self.assertIsNone(result["timing_sums_ms"]["io.feedback_database_ms"])

    def test_missing_cache_uses_price_bounds_and_invalid_split_remains_unpriced(self):
        result = summarize([self.span("a", **{"model.input_tokens": 100, "model.output_tokens": 10}),
                            self.span("b", **{"model.input_tokens": 100, "model.output_tokens": 10, "model.cache_hit_tokens": 101})], self.prices)["runs"][0]
        self.assertEqual(["0.00003", "0.00024"], result["estimated_cost_known_calls_range"])
        self.assertEqual(1, result["unpriced_calls"])

    def test_context_rejection_is_not_a_paid_request_and_conflicts_fail_closed(self):
        call = self.span("a", **{"model.dispatched": False})
        self.assertEqual([], summarize([call], self.prices)["calls"])
        with self.assertRaises(ValueError):
            summarize([call, self.span("a")], self.prices)

    def test_ttft_requires_visible_text_and_layout_comes_from_the_request(self):
        model = {**self.span("root"), "name": "agent.model",
                 "attributes": {"run.id": "run", "context.projection_version": "chat_native_v2"}}
        roles = ["system", "user", "assistant", "tool", "system"]
        calls = summarize([model, self.span("a", **{"model.first_token_state": "RECEIVED", "model.first_token_ms": 400,
                                                  "message.ordered_roles": roles}),
                           self.span("b", **{"model.first_token_state": "PENDING", "model.first_token_ms": 0})], self.prices)["calls"]
        self.assertEqual(("chat_native_v2", roles, 400), (calls[0]["context_projection"], calls[0]["message_roles_in_order"], calls[0]["ttft_ms"]))
        self.assertIsNone(calls[1]["ttft_ms"])


if __name__ == "__main__":
    unittest.main()
