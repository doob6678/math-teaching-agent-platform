package com.doob.mathagent.teacher.formula;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Extracts native Word Office Math (OMML) from one DOCX paragraph without asking an LLM to guess it.
 *
 * <p>OMML is already the authoritative mathematical structure in a DOCX. Keeping both the original fragment and a
 * conservative MathML projection means formula search can use text evidence while the UI has a lossless source for a
 * later renderer. LaTex is intentionally absent here: a partial converter that silently changes formula meaning is
 * worse than returning no LaTex at all.</p>
 */
public final class OmmlFormulaExtractor {

    private static final String MATH_NAMESPACE = "http://schemas.openxmlformats.org/officeDocument/2006/math";
    private static final String MATHML_NAMESPACE = "http://www.w3.org/1998/Math/MathML";

    private OmmlFormulaExtractor() {
    }

    /**
     * Finds every {@code m:oMath} occurrence inside the paragraph XML supplied by Apache POI.
     *
     * @param paragraphXml exact paragraph XML, not user-provided arbitrary XML
     * @return formulas in document order; malformed paragraphs return no formula instead of fabricated text
     */
    public static List<ExtractedFormula> extractFromParagraphXml(String paragraphXml) {
        if (paragraphXml == null || paragraphXml.isBlank()) {
            return List.of();
        }
        try {
            DocumentBuilderFactory factory = secureDocumentBuilderFactory();
            List<ExtractedFormula> formulas = new ArrayList<>();
            int cursor = 0;
            while (true) {
                int start = paragraphXml.indexOf("<m:oMath", cursor);
                if (start < 0) {
                    break;
                }
                int end = paragraphXml.indexOf("</m:oMath>", start);
                if (end < 0) {
                    break;
                }
                String omml = paragraphXml.substring(start, end + "</m:oMath>".length());
                String wrapped = "<formula-fragment xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
                        + " xmlns:m=\"" + MATH_NAMESPACE + "\">" + omml + "</formula-fragment>";
                Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(wrapped)));
                Element formula = (Element) document.getElementsByTagNameNS(MATH_NAMESPACE, "oMath").item(0);
                String plainText = normalizeFormulaText(formulaPlainText(formula));
                if (!plainText.isBlank()) {
                    formulas.add(new ExtractedFormula(
                            serialize(formula),
                            "<math xmlns=\"" + MATHML_NAMESPACE + "\"><mrow>" + toMathMlChildren(formula) + "</mrow></math>",
                            plainText,
                            null));
                }
                cursor = end + "</m:oMath>".length();
            }
            return List.copyOf(formulas);
        } catch (Exception ignored) {
            // A damaged DOCX must retain its original asset/text path; never invent a formula from broken XML.
            return List.of();
        }
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static String toMathMlChildren(Element element) {
        StringBuilder result = new StringBuilder();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element childElement) {
                result.append(toMathMl(childElement));
            }
        }
        return result.toString();
    }

    /**
     * Maps the common structural OMML containers. Unknown containers are recursively unwrapped so that their textual
     * content remains searchable rather than being silently discarded when a newer Word producer emits an extension.
     */
    private static String toMathMl(Element element) {
        String name = localName(element);
        return switch (name) {
            case "r" -> tokenMathMl(element);
            case "f" -> "<mfrac>" + childMathMl(element, "num") + childMathMl(element, "den") + "</mfrac>";
            case "sSup" -> "<msup>" + childMathMl(element, "e") + childMathMl(element, "sup") + "</msup>";
            case "sSub" -> "<msub>" + childMathMl(element, "e") + childMathMl(element, "sub") + "</msub>";
            case "sSubSup" -> "<msubsup>" + childMathMl(element, "e") + childMathMl(element, "sub")
                    + childMathMl(element, "sup") + "</msubsup>";
            case "rad" -> rootMathMl(element);
            case "nary" -> naryMathMl(element);
            case "d" -> "<mfenced>" + childMathMl(element, "e") + "</mfenced>";
            case "m" -> "<mtable>" + tableMathMl(element) + "</mtable>";
            case "mr" -> "<mtr><mtd>" + toMathMlChildren(element) + "</mtd></mtr>";
            case "ctrlPr", "rPr" -> "";
            default -> toMathMlChildren(element);
        };
    }

    private static String tokenMathMl(Element run) {
        String value = normalizeFormulaText(plainText(run));
        if (value.isBlank()) {
            return "";
        }
        if (value.matches("[+\\-*/=<>≤≥≠∑∏∫√∞]+")) {
            return "<mo>" + escapeXml(value) + "</mo>";
        }
        if (value.matches("[0-9.]+")) {
            return "<mn>" + escapeXml(value) + "</mn>";
        }
        return "<mi>" + escapeXml(value) + "</mi>";
    }

    private static String rootMathMl(Element radical) {
        String base = childMathMl(radical, "e");
        String degree = childMathMl(radical, "deg");
        return degree.isBlank() ? "<msqrt>" + base + "</msqrt>" : "<mroot>" + base + degree + "</mroot>";
    }

    private static String naryMathMl(Element nary) {
        String operator = textOfFirstChild(nary, "chr");
        String base = childMathMl(nary, "e");
        String sub = childMathMl(nary, "sub");
        String sup = childMathMl(nary, "sup");
        String symbol = operator.isBlank() ? "∑" : operator;
        String scripted = sub.isBlank() && sup.isBlank()
                ? "<mo>" + escapeXml(symbol) + "</mo>"
                : "<munderover><mo>" + escapeXml(symbol) + "</mo>" + emptyMathMl(sub) + emptyMathMl(sup) + "</munderover>";
        return scripted + base;
    }

    private static String emptyMathMl(String value) {
        return value.isBlank() ? "<mrow/>" : "<mrow>" + value + "</mrow>";
    }

    private static String tableMathMl(Element matrix) {
        StringBuilder rows = new StringBuilder();
        for (Node child = matrix.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element childElement && "mr".equals(localName(childElement))) {
                rows.append(toMathMl(childElement));
            }
        }
        return rows.toString();
    }

    private static String childMathMl(Element parent, String childName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element childElement && childName.equals(localName(childElement))) {
                return toMathMlChildren(childElement);
            }
        }
        return "";
    }

    private static String textOfFirstChild(Element parent, String childName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element childElement && childName.equals(localName(childElement))) {
                return normalizeFormulaText(plainText(childElement));
            }
        }
        return "";
    }

    private static String plainText(Node node) {
        StringBuilder result = new StringBuilder();
        if (node.getNodeType() == Node.TEXT_NODE) {
            return node.getNodeValue() == null ? "" : node.getNodeValue();
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            result.append(plainText(child));
        }
        return result.toString();
    }

    /** Preserves the minimal operators that carry meaning in searchable formula text. */
    private static String formulaPlainText(Element element) {
        String name = localName(element);
        return switch (name) {
            case "f" -> childPlainText(element, "num") + "/" + childPlainText(element, "den");
            case "sSup" -> childPlainText(element, "e") + "^" + childPlainText(element, "sup");
            case "sSub" -> childPlainText(element, "e") + "_" + childPlainText(element, "sub");
            case "sSubSup" -> childPlainText(element, "e") + "_" + childPlainText(element, "sub")
                    + "^" + childPlainText(element, "sup");
            default -> {
                StringBuilder result = new StringBuilder();
                boolean hasElementChild = false;
                for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                    hasElementChild |= child instanceof Element;
                }
                if (!hasElementChild) {
                    yield plainText(element);
                }
                for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (child instanceof Element childElement) {
                        result.append(formulaPlainText(childElement));
                    }
                }
                yield result.toString();
            }
        };
    }

    private static String childPlainText(Element parent, String childName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element childElement && childName.equals(localName(childElement))) {
                return formulaPlainText(childElement);
            }
        }
        return "";
    }

    private static String normalizeFormulaText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").strip();
    }

    private static String serialize(Node node) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(node), new StreamResult(writer));
        return writer.toString();
    }

    private static String localName(Element element) {
        return element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Structured, lossless formula material persisted inside the existing document_block.formula_refs JSON column. */
    public record ExtractedFormula(String omml, String mathMl, String plainText, String latex) {
    }
}
