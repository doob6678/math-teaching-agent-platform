import unittest

from app.sse import iter_sse_data_events


class SseDataEventsTest(unittest.TestCase):
    def test_joins_split_json_without_inserting_newline(self):
        class Response:
            def iter_lines(self, chunk_size=None, decode_unicode=True):
                self.chunk_size = chunk_size
                return iter([
                    'data: {"object":"chat.completion.',
                    'data: chunk","choices":[]}',
                    "data: [DONE]",
                ])

        response = Response()

        self.assertEqual(list(iter_sse_data_events(response)), [
            '{"object":"chat.completion.chunk","choices":[]}',
            "[DONE]",
        ])
        self.assertEqual(response.chunk_size, 1)

    def test_yields_consecutive_complete_json_events(self):
        class Response:
            def iter_lines(self, chunk_size=None, decode_unicode=True):
                self.chunk_size = chunk_size
                return iter([
                    'data: {"choices":[]}',
                    'data: {"usage":{"total_tokens":1}}',
                ])

        response = Response()
        self.assertEqual(list(iter_sse_data_events(response)), [
            '{"choices":[]}',
            '{"usage":{"total_tokens":1}}',
        ])
        self.assertEqual(response.chunk_size, 1)

    def test_rejects_malformed_json_before_next_event(self):
        class Response:
            def iter_lines(self, chunk_size=None, decode_unicode=True):
                return iter([
                    'data: {"choices":[BAD]}',
                    'data: {"choices":[]}',
                ])

        with self.assertRaises(ValueError):
            list(iter_sse_data_events(Response()))
