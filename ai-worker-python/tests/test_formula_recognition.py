import unittest

from app.formula_recognition import FormulaRecognitionError, formula_transport_code, parse_formula_response


class FormulaRecognitionResponseTest(unittest.TestCase):
    def test_accepts_a_high_confidence_formula_only_when_all_required_fields_are_present(self):
        result = parse_formula_response(
            '{"status":"recognized","latex":"\\\\frac{x}{y}","plainText":"x/y","confidence":0.97}',
            minimum_confidence=0.9,
        )

        self.assertEqual(result.status, "recognized")
        self.assertEqual(result.latex, r"\frac{x}{y}")
        self.assertEqual(result.plain_text, "x/y")

    def test_rejects_low_confidence_result_instead_of_returning_model_guesses_as_evidence(self):
        with self.assertRaises(FormulaRecognitionError):
            parse_formula_response(
                '{"status":"recognized","latex":"x+y","plainText":"x+y","confidence":0.44}',
                minimum_confidence=0.9,
            )
    def test_transport_codes_are_stable_and_do_not_include_provider_response_text(self):
        self.assertEqual(formula_transport_code(401), "FORMULA_VISION_AUTH_FAILED")
        self.assertEqual(formula_transport_code(429), "FORMULA_VISION_RETRYABLE")
        self.assertEqual(formula_transport_code(503), "FORMULA_VISION_UNAVAILABLE")
        self.assertEqual(formula_transport_code(), "FORMULA_VISION_TRANSPORT_FAILED")


if __name__ == "__main__":
    unittest.main()
