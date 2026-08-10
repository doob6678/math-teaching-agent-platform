import unittest

from app.sse import iter_sse_data_events


class SseDataEventsTest(unittest.TestCase):
    def test_joins_split_json_without_inserting_newline(self):
        class Response:
            def iter_lines(self, decode_unicode=True):
                return iter([
                    'data: {"object":"chat.completion.',
                    'data: chunk","choices":[]}',
                    "data: [DONE]",
                ])

        self.assertEqual(list(iter_sse_data_events(Response())), [
            '{"object":"chat.completion.chunk","choices":[]}',
            "[DONE]",
        ])

    def test_yields_consecutive_complete_json_events(self):
        class Response:
            def iter_lines(self, decode_unicode=True):
                return iter([
                    'data: {"choices":[]}',
                    'data: {"usage":{"total_tokens":1}}',
                ])

        self.assertEqual(list(iter_sse_data_events(Response())), [
            '{"choices":[]}',
            '{"usage":{"total_tokens":1}}',
        ])

    def test_rejects_malformed_json_before_next_event(self):
        class Response:
            def iter_lines(self, decode_unicode=True):
                return iter([
                    'data: {"choices":[BAD]}',
                    'data: {"choices":[]}',
                ])

        with self.assertRaises(ValueError):
            list(iter_sse_data_events(Response()))
