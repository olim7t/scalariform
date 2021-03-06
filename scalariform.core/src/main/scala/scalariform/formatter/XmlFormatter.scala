package scalariform.formatter

import scalariform.parser._
import scalariform.utils._
import scalariform.lexer._
import scalariform.formatter.preferences._
trait XmlFormatter { self: HasFormattingPreferences with ExprFormatter with ScalaFormatter ⇒

  def format(xmlExpr: XmlExpr)(implicit formatterState: FormatterState): FormatResult = {
    val XmlExpr(first: XmlContents, otherElements: List[XmlElement]) = xmlExpr
    var formatResult: FormatResult = NoFormatResult
    formatResult ++= format(first)
    for (element ← otherElements)
      formatResult ++= format(element)
    formatResult
  }

  def format(xmlContent: XmlContents)(implicit formatterState: FormatterState): FormatResult = {
    xmlContent match {
      case expr@Expr(_) ⇒ format(expr)
      case xmlNonEmpty@XmlNonEmptyElement(_, _, _) ⇒ format(xmlNonEmpty)
      case xmlEmpty@XmlEmptyElement(_, _, _, _, _) ⇒ format(xmlEmpty)
      case _ ⇒ NoFormatResult // TODO
    }
  }

  def format(xmlEmpty: XmlEmptyElement)(implicit formatterState: FormatterState): FormatResult = {
    val XmlEmptyElement(startOpen, name, attributes: List[(Option[Token], XmlAttribute)], whitespaceOption, emptyClose) = xmlEmpty
    var formatResult: FormatResult = NoFormatResult
    for ((whitespaceOption2, attribute) ← attributes) {
      for (whitespace ← whitespaceOption2 if formattingPreferences(FormatXml))
        formatResult = formatResult.replaceXml(whitespace, " ")
      formatResult ++= format(attribute)
    }
    for (whitespace ← whitespaceOption if formattingPreferences(FormatXml))
      formatResult = formatResult.replaceXml(whitespace, "")
    formatResult
  }

  // TODO: Dup with XmlEmptyElement
  def format(xmlStart: XmlStartTag)(implicit formatterState: FormatterState): FormatResult = {
    val XmlStartTag(startOpen, name, attributes: List[(Option[Token], XmlAttribute)], whitespaceOption, tagClose) = xmlStart
    var formatResult: FormatResult = NoFormatResult
    for ((whitespaceOption2, attribute) ← attributes) {
      for (whitespace ← whitespaceOption2 if formattingPreferences(FormatXml))
        formatResult = formatResult.replaceXml(whitespace, " ")
      formatResult ++= format(attribute)
    }
    for (whitespace ← whitespaceOption if formattingPreferences(FormatXml))
      formatResult = formatResult.replaceXml(whitespace, "")
    formatResult
  }

  def format(xmlAttribute: XmlAttribute)(implicit formatterState: FormatterState): FormatResult = {
    val XmlAttribute(name, whitespaceOption, equals, whitespaceOption2, valueOrEmbeddedScala: Either[Token, Expr]) = xmlAttribute
    var formatResult: FormatResult = NoFormatResult
    for (whitespace ← whitespaceOption if formattingPreferences(FormatXml))
      formatResult = formatResult.replaceXml(whitespace, "")
    for (embeddedScala ← valueOrEmbeddedScala.right)
      formatResult ++= format(embeddedScala)
    for (whitespace ← whitespaceOption2 if formattingPreferences(FormatXml))
      formatResult = formatResult.replaceXml(whitespace, "")
    formatResult
  }

  def format(xmlNonEmpty: XmlNonEmptyElement)(implicit formatterState: FormatterState): FormatResult = {
    val XmlNonEmptyElement(startTag: XmlStartTag, contents: List[XmlContents], endTag: XmlEndTag) = xmlNonEmpty
    var formatResult: FormatResult = NoFormatResult
    val multiline = contents exists {
      /* case x: XmlElement ⇒ true */
      case XmlPCDATA(Token(_, text, _, _)) if text contains '\n' ⇒ true
      case _ ⇒ false
    }
    formatResult ++= format(startTag)
    val nestedFormatterState = formatterState.alignWithToken(startTag.firstToken).indent
    var firstNonWhitespace = true
    for (previousAndThis ← Utils.pairWithPrevious(contents)) {
      previousAndThis match {
        case (_, xmlContent@XmlPCDATA(token@Token(_, text@Trimmed(prefix, infix, suffix), _, _))) ⇒
          if (infix.isEmpty)
            formatResult = formatResult.replaceXml(token, "")
          else {
            firstNonWhitespace = false
            formatResult = formatResult.replaceXml(token, infix)
            val withNewlines = (prefix contains '\n') || (suffix contains '\n')
            if (multiline)
              formatResult = formatResult.before(token, nestedFormatterState.currentIndentLevelInstruction)
          }
        case (_, xmlContent) if multiline && firstNonWhitespace =>
          firstNonWhitespace = false
          formatResult = formatResult.before(xmlContent.firstToken, nestedFormatterState.currentIndentLevelInstruction)
          formatResult ++= format(xmlContent)
        case (Some(XmlPCDATA(Token(_, Trimmed(prefix, infix, suffix), _, _))), xmlContent) if multiline && (suffix.contains('\n') || (infix.isEmpty && prefix.contains('\n'))) =>
          firstNonWhitespace = false
          formatResult = formatResult.before(xmlContent.firstToken, nestedFormatterState.currentIndentLevelInstruction)
          formatResult ++= format(xmlContent)
        case (Some(_), xmlContent@Expr(_)) =>
          firstNonWhitespace = false
          formatResult = formatResult.before(xmlContent.firstToken, Compact)
          formatResult ++= format(xmlContent)
        case (Some(Expr(_)), xmlContent) =>
          firstNonWhitespace = false
          formatResult = formatResult.before(xmlContent.firstToken, Compact)
          formatResult ++= format(xmlContent)
        case (_, xmlContent) ⇒
          firstNonWhitespace = false
          formatResult ++= format(xmlContent)
      }
    }
    if (multiline)
      formatResult = formatResult.before(endTag.firstToken, formatterState.alignWithToken(startTag.firstToken).currentIndentLevelInstruction)
    formatResult
  }

}

