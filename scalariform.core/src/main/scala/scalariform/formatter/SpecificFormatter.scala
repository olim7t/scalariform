package scalariform.formatter

import scalariform.lexer.Tokens._
import scalariform.lexer._
import scalariform.parser._
import scalariform.utils._
import scalariform.formatter.preferences._

trait SpecificFormatter {

  def debug = false

  type Result <: AstNode

  def getParser(parser: ScalaCombinatorParser): ScalaCombinatorParser#Parser[Result]

  def format(formatter: ScalaFormatter, result: Result): FormatResult

  def getTokens(s: String): List[Token] = ScalaLexer.tokenise(s)

  @throws(classOf[ScalaParserException])
  def format(source: String, lineDelimiter: Option[String] = None)(baseFormattingPreferences: IFormattingPreferences): String = {
    val (edits, _) = fullFormat(source, lineDelimiter)(baseFormattingPreferences)
    TextEditProcessor.runEdits(source, edits)
  }

  @throws(classOf[ScalaParserException])
  def fullFormat(source: String, lineDelimiter: Option[String] = None)(baseFormattingPreferences: IFormattingPreferences): (List[TextEdit], FormatResult) = {
    import scalariform.parser._
    import scala.util.parsing.input._
    import scala.util.parsing.combinator._

    val startTime = System.currentTimeMillis
    val (lexer, tokens) = ScalaLexer.tokeniseFull(source)
    if (debug) {
      println
      println(source)
      println("Tokens:")
      tokens foreach println
      println("Hidden:")
      println("hiddenPredecessors: " + lexer.getHiddenPredecessors)
      println("hiddenSuccessors: " + lexer.getHiddenSuccessors)
      println("inferredNewlines: " + lexer.getInferredNewlines)
    }
    val parser = new ScalaCombinatorParser
    val rawParseResult = getParser(parser)(new ScalaLexerReader(tokens))
    if (!rawParseResult.successful) { throw new ScalaParserException("Parse failed: " + rawParseResult) }
    val parseResult = rawParseResult.get
    //     println("parseResult: ")
    //     for (token <- parseResult.tokens)
    //       println(token)
    //     println("original tokenise: ")
    //     for (token <- tokens)
    //       println(token)

    var actualFormattingPreferences = baseFormattingPreferences
    for {
      hiddenTokens ← (lexer.getHiddenPredecessors.valuesIterator ++ lexer.getInferredNewlines.valuesIterator)
      hiddenToken ← hiddenTokens
      ToggleOption(onOrOff, optionName) ← FormatterDirectiveParser.getDirectives(hiddenToken.getText)
      rawPreference ← AllPreferences.preferencesByKey.get(optionName)
      if rawPreference.preferenceType == BooleanPreference
      val preference = BooleanPreference.cast(rawPreference)
    } actualFormattingPreferences = actualFormattingPreferences.setPreference(preference, onOrOff)

    require(parseResult.tokens == tokens.init, "Parse tokens differ from expected. Actual = " + parseResult.tokens + ", expected = " + tokens.init + ", parseResult = " + parseResult) // dropped EOF
    if (debug) { println("Parse result: " + parseResult) }
    val elapsedTime = System.currentTimeMillis - startTime
    //     if (debug) 
    //       println("Parse time = " + elapsedTime + "ms")
    val newlineSequence_ = lineDelimiter.getOrElse(if (source contains "\r\n") "\r\n" else "\n")

    val formatter = new ScalaFormatter() {

      def isInferredNewline(token: Token): Boolean = lexer.getInferredNewlines.get(token).isDefined

      def inferredNewlines(token: Token): HiddenTokens = lexer.getInferredNewlines(token)

      def hiddenPredecessors(token: Token): HiddenTokens = lexer.getHiddenPredecessors(token)

      val formattingPreferences: IFormattingPreferences = actualFormattingPreferences

      val newlineSequence = newlineSequence_

    }

    val formatResult = format(formatter, parseResult)
    if (debug) println("Format result: " + formatResult)
    val edits = formatter.writeTokens(source, tokens, formatResult)
    (edits, formatResult)
  }

}
