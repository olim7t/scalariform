package scalariform.formatter

import scalariform.lexer.Token
import scalariform.lexer.Tokens._
import scalariform.parser._
import scalariform.utils.Utils
import scalariform.utils.BooleanLang._
import scalariform.formatter.preferences._

trait ExprFormatter { self: HasFormattingPreferences with AnnotationFormatter with HasHiddenTokenInfo with TypeFormatter with TemplateFormatter with ScalaFormatter with XmlFormatter ⇒

  def format(expr: Expr)(implicit formatterState: FormatterState): FormatResult = format(expr.contents)

  private def format(exprElements: List[ExprElement])(implicit formatterState: FormatterState): FormatResult = {
    var formatResult: FormatResult = NoFormatResult

    var currentFormatterState = formatterState
    var expressionBreakIndentHappened = false

    for ((previousElementOption, element, nextElementOption) ← Utils.withPreviousAndNext(exprElements)) {
      for (previousElement ← previousElementOption) {
        val instructionOption = (previousElement, element) match {
          case (PrefixExprElement(_), _) ⇒ Some(Compact)
          case (_, PostfixExprElement(_)) ⇒ Some(CompactPreservingGap)
          case (InfixExprElement(_), _) | (_, InfixExprElement(_)) ⇒ Some(CompactEnsuringGap)
          case (_, ArgumentExprs(_)) if formattingPreferences(PreserveSpaceBeforeArguments) ⇒ Some(CompactPreservingGap)
          case _ ⇒ None
        }

        lazy val containsPriorFormatting = formatResult.predecessorFormatting contains element.firstToken // in particular, from the String concatenation code
        for (instruction ← instructionOption if not(containsPriorFormatting))
          formatResult = formatResult.before(element.firstToken, instruction)

        if (formattingPreferences(CompactStringConcatenation)) {
          val infixPlus = element match {
            case InfixExprElement(Token(PLUS, _, _, _)) ⇒ true
            case _ ⇒ false
          }
          val stringConcatenation = (previousElement, nextElementOption) match {
            case (GeneralTokens(tokens), _) if tokens.last.tokenType == STRING_LITERAL ⇒ true
            case (_, Some(GeneralTokens(tokens))) if tokens.head.tokenType == STRING_LITERAL ⇒ true
            case _ ⇒ false
          }
          if (infixPlus and stringConcatenation) {
            val Some(nextElement) = nextElementOption // Safe because current element is an infix operator
            formatResult = formatResult.before(element.firstToken, Compact)
            formatResult = formatResult.before(nextElement.firstToken, Compact)
          }
        }
      }

      formatResult ++= format(element)(currentFormatterState)

      val firstToken = exprElements.head.tokens.head // TODO: 
      element match {
        case GeneralTokens(_) | PrefixExprElement(_) | InfixExprElement(_) | PostfixExprElement(_) ⇒
          for (token ← element.tokens if token != firstToken)
            if (isInferredNewline(token))
              nextElementOption match {
                case Some(ArgumentExprs(_)) if token == element.tokens.last ⇒
                  () //  Don't allow expression breaks immediately before an argument expression
                case _ ⇒
                  if (not(expressionBreakIndentHappened)) {
                    currentFormatterState = currentFormatterState.indent
                    expressionBreakIndentHappened = true
                  }
                  formatResult = formatResult.formatNewline(token, currentFormatterState.currentIndentLevelInstruction)
              }
            else if (hiddenPredecessors(token).containsNewline) {
              if (not(expressionBreakIndentHappened)) {
                currentFormatterState = currentFormatterState.indent
                expressionBreakIndentHappened = true
              }
              formatResult = formatResult.before(token, currentFormatterState.currentIndentLevelInstruction)
            }
        case _ ⇒ // TODO:
      }
    }

    formatResult
  }

  private def format(exprElement: ExprElement)(implicit formatterState: FormatterState): FormatResult = exprElement match {
    case ifExpr: IfExpr ⇒ format(ifExpr)
    case whileExpr: WhileExpr ⇒ format(whileExpr)
    case doExpr: DoExpr ⇒ format(doExpr)
    case blockExpr: BlockExpr ⇒ format(blockExpr)
    case forExpr: ForExpr ⇒ format(forExpr)
    case tryExpr: TryExpr ⇒ format(tryExpr)
    case template: Template ⇒ format(template)
    case statSeq: StatSeq ⇒ format(statSeq) // TODO: revisit
    case argumentExprs: ArgumentExprs ⇒ format(argumentExprs)

    case GeneralTokens(_) ⇒ NoFormatResult
    case PrefixExprElement(_) ⇒ NoFormatResult
    case InfixExprElement(_) ⇒ NoFormatResult
    case PostfixExprElement(_) ⇒ NoFormatResult
    case annotation: Annotation ⇒ format(annotation)
    case typeExprElement: TypeExprElement ⇒ format(typeExprElement.contents)
    case expr: Expr ⇒ format(expr.contents)
    case AnonymousFunctionStart(contents, arrow) ⇒ format(contents)
    case xmlExpr: XmlExpr ⇒ format(xmlExpr)
    case parenExpr: ParenExpr ⇒ format(parenExpr)
    case _ ⇒ NoFormatResult
  }

  def format(argumentExprs: ArgumentExprs)(implicit formatterState: FormatterState): FormatResult = format(argumentExprs.contents)

  private def format(parenExpr: ParenExpr)(implicit formatterState: FormatterState): FormatResult = format(parenExpr.contents)

  private def format(tryExpr: TryExpr)(implicit formatterState: FormatterState): FormatResult = {
    val TryExpr(tryToken: Token, body: Expr, catchClauseOption: Option[(Token, BlockExpr)], finallyClauseOption: Option[(Token, Expr)]) = tryExpr
    var formatResult: FormatResult = NoFormatResult

    // TODO: similar to first half of ifExpr, whileExpr etc

    val bodyIsABlock = isBlockExpr(body)

    val indentBody =
      if (hiddenPredecessors(body.firstToken).containsNewline) {
        if (bodyIsABlock) {
          formatResult = formatResult.before(body.firstToken, CompactEnsuringGap)
          false
        } else {
          formatResult = formatResult.before(body.firstToken, formatterState.nextIndentLevelInstruction)
          true
        }
      } else {
        formatResult = formatResult.before(body.firstToken, CompactEnsuringGap)
        false
      }
    val bodyFormatterState = if (indentBody) formatterState.indent else formatterState
    formatResult ++= format(body)(bodyFormatterState)

    // TODO: Simplified version of elseClause formatting
    for ((catchToken, catchBlock) ← catchClauseOption) {
      if (hiddenPredecessors(catchToken).containsNewline && !(isBlockExpr(body) && containsNewline(body)))
        formatResult = formatResult.before(catchToken, formatterState.currentIndentLevelInstruction)
      formatResult = formatResult.before(catchBlock.firstToken, CompactEnsuringGap)
      formatResult ++= format(catchBlock)
    }

    // TODO: See elseClause formatting
    for ((finallyToken, finallyBody) ← finallyClauseOption) {

      if ((hiddenPredecessors(finallyToken).containsNewline || containsNewline(body)) && catchClauseOption.isDefined)
        formatResult = formatResult.before(finallyToken, formatterState.currentIndentLevelInstruction)

      val indentFinallyBody =
        if (isBlockExpr(finallyBody))
          false
        else if (hiddenPredecessors(finallyBody.firstToken).containsNewline)
          true
        else
          false

      if (indentFinallyBody)
        formatResult = formatResult.before(finallyBody.firstToken, formatterState.nextIndentLevelInstruction)
      else
        formatResult = formatResult.before(finallyBody.firstToken, CompactEnsuringGap)

      val finallyBodyFormatterState = if (indentFinallyBody) formatterState.indent else formatterState
      formatResult ++= format(finallyBody)(finallyBodyFormatterState)
    }

    formatResult
  }

  private def format(ifExpr: IfExpr)(implicit formatterState: FormatterState): FormatResult = {
    val IfExpr(ifToken: Token, condExpr: CondExpr, newlinesOpt: Option[Token], body: Expr, elseClauseOption: Option[ElseClause]) = ifExpr
    var formatResult: FormatResult = NoFormatResult

    formatResult ++= format(condExpr)

    val bodyIsABlock = isBlockExpr(body)

    val indentBody = newlinesOpt match {
      case Some(newlines) if bodyIsABlock ⇒ {
        formatResult = formatResult.formatNewline(newlines, CompactEnsuringGap)
        false
      }
      case Some(newlines) ⇒ {
        formatResult = formatResult.formatNewline(newlines, formatterState.nextIndentLevelInstruction)
        true
      }
      case None ⇒ {
        formatResult = formatResult.before(body.firstToken, CompactEnsuringGap)
        false
      }
    }

    val bodyFormatterState = if (indentBody) formatterState.indent else formatterState

    formatResult ++= format(body)(bodyFormatterState)

    // TODO: take into account pre-Else semi
    for (ElseClause(elseSemiOpt, elseToken, elseBody) ← elseClauseOption) {

      if (bodyIsABlock && containsNewline(body))
        formatResult = formatResult.before(elseToken, CompactEnsuringGap)
      else if (hiddenPredecessors(elseToken).containsNewline || containsNewline(body))
        formatResult = formatResult.before(elseToken, formatterState.currentIndentLevelInstruction)

      val indentElseBody =
        if (isBlockExpr(elseBody) || isIfExpr(elseBody))
          false
        else if (hiddenPredecessors(elseBody.firstToken).containsNewline)
          true
        else
          false

      if (indentElseBody)
        formatResult = formatResult.before(elseBody.firstToken, formatterState.nextIndentLevelInstruction)
      else
        formatResult = formatResult.before(elseBody.firstToken, CompactEnsuringGap)

      val elseBodyFormatterState = if (indentElseBody) formatterState.indent else formatterState
      formatResult ++= format(elseBody)(elseBodyFormatterState)
    }

    formatResult
  }

  private def format(condExpr: CondExpr)(implicit formatterState: FormatterState): FormatResult = {
    val CondExpr(lparen: Token, condition: Expr, rparen: Token) = condExpr
    format(condition)
  }

  private def isIfExpr(expr: Expr) = expr.contents.size == 1 && expr.contents(0).isInstanceOf[IfExpr]

  private def format(forExpr: ForExpr)(implicit formatterState: FormatterState): FormatResult = {
    val ForExpr(
      forToken: Token,
      lParenOrBrace: Token,
      enumerators: Enumerators,
      rParenOrBrace: Token,
      newlinesOpt: Option[Token],
      yieldOption: Option[Token],
      body: Expr) = forExpr
    var formatResult: FormatResult = NoFormatResult

    // TODO: similar to blockExpr
    val enumeratorsSectionContainsNewline = containsNewline(enumerators) ||
      hiddenPredecessors(rParenOrBrace).containsNewline ||
      hiddenPredecessors(enumerators.firstToken).containsNewline
    if (enumeratorsSectionContainsNewline) {
      formatResult = formatResult.before(enumerators.firstToken, formatterState.nextIndentLevelInstruction)
      formatResult ++= format(enumerators)(formatterState.indent)
      formatResult = formatResult.before(rParenOrBrace, formatterState.currentIndentLevelInstruction)
    } else
      formatResult ++= format(enumerators)(formatterState)

    // TODO: similar to whileExpr, first half of ifExpr
    val bodyIsABlock = isBlockExpr(body)

    val indentBody = newlinesOpt match {
      case Some(newlines) if bodyIsABlock || enumeratorsSectionContainsNewline ⇒ {
        formatResult = formatResult.formatNewline(newlines, CompactEnsuringGap)
        false
      }
      case Some(newlines) ⇒ {
        formatResult = formatResult.formatNewline(newlines, formatterState.nextIndentLevelInstruction)
        true
      }
      case None ⇒ {
        formatResult = formatResult.before(body.firstToken, CompactEnsuringGap)
        false
      }
    }

    val bodyFormatterState = if (indentBody) formatterState.indent else formatterState
    formatResult ++= format(body)(bodyFormatterState)

    formatResult
  }

  private def format(enumerators: Enumerators)(implicit formatterState: FormatterState): FormatResult = {
    val Enumerators(initialGenerator: Generator, rest: List[(Token, Enumerator)]) = enumerators
    var formatResult: FormatResult = NoFormatResult

    formatResult ++= format(initialGenerator)
    // TODO: Pretty similar to statSeq
    for ((semi, otherEnumerator) ← rest) {

      if (isInferredNewline(semi))
        formatResult = formatResult.formatNewline(semi, formatterState.currentIndentLevelInstruction)

      if (!isInferredNewline(semi)) {
        val firstToken = otherEnumerator.firstToken
        val instruction = if (hiddenPredecessors(firstToken).containsNewline)
          formatterState.currentIndentLevelInstruction
        else
          CompactEnsuringGap
        formatResult = formatResult.before(firstToken, instruction)
      }
      formatResult ++= format(otherEnumerator)
    }

    formatResult
  }

  private def format(enumerator: Enumerator)(implicit formatterState: FormatterState): FormatResult = {
    enumerator match {
      case expr@Expr(_) ⇒ format(expr)
      case generator@Generator(_, _, _, _, _) ⇒ format(generator)
      case guard@Guard(_, _) ⇒ format(guard: Guard)
    }
  }

  private def format(generator: Generator)(implicit formatterState: FormatterState): FormatResult = {
    val Generator(valOption: Option[Token], pattern: Expr, equalsOrArrowToken: Token, expr: Expr, guards: List[Guard]) = generator
    var formatResult: FormatResult = NoFormatResult
    formatResult ++= format(expr)
    formatResult ++= format(pattern)
    for (guard ← guards)
      formatResult ++= format(guard)
    formatResult
  }

  private def format(guard: Guard)(implicit formatterState: FormatterState): FormatResult = {
    val Guard(ifToken: Token, expr: Expr) = guard
    format(expr)
  }

  private def format(whileExpr: WhileExpr)(implicit formatterState: FormatterState): FormatResult = {
    // TODO: Same as first half of ifExpr
    val WhileExpr(whileToken: Token, condExpr: CondExpr, newlinesOpt: Option[Token], body: Expr) = whileExpr
    var formatResult: FormatResult = NoFormatResult

    formatResult ++= format(condExpr)

    val bodyIsABlock = isBlockExpr(body)

    val indentBody = newlinesOpt match {
      case Some(newlines) if bodyIsABlock ⇒ {
        formatResult = formatResult.formatNewline(newlines, CompactEnsuringGap)
        false
      }
      case Some(newlines) ⇒ {
        formatResult = formatResult.formatNewline(newlines, formatterState.nextIndentLevelInstruction)
        true
      }
      case None ⇒ {
        formatResult = formatResult.before(body.firstToken, CompactEnsuringGap)
        false
      }
    }

    val bodyFormatterState = if (indentBody) formatterState.indent else formatterState
    formatResult ++= format(body)(bodyFormatterState)

    formatResult
  }

  private def format(doExpr: DoExpr)(implicit formatterState: FormatterState): FormatResult = {
    var formatResult: FormatResult = NoFormatResult
    val DoExpr(doToken: Token, body: Expr, statSepOpt: Option[Token], whileToken: Token, condExpr: CondExpr) = doExpr

    val bodyIsABlock = isBlockExpr(body)
    val indentBody = !bodyIsABlock && hiddenPredecessors(body.firstToken).containsNewline
    val instruction =
      if (indentBody) formatterState.nextIndentLevelInstruction
      else CompactEnsuringGap
    formatResult = formatResult.before(body.firstToken, instruction)

    val bodyFormatterState = if (indentBody) formatterState.indent else formatterState
    formatResult ++= format(body)(bodyFormatterState)

    formatResult = statSepOpt match {
      case Some(semi) if isInferredNewline(semi) ⇒ {
        val instruction =
          if (indentBody)
            formatterState.currentIndentLevelInstruction
          else if (bodyIsABlock && containsNewline(body))
            CompactEnsuringGap
          else
            formatterState.currentIndentLevelInstruction
        formatResult.formatNewline(semi, instruction)
      }
      case Some(_) | None ⇒ {
        val instruction =
          if (indentBody)
            formatterState.currentIndentLevelInstruction
          else if (bodyIsABlock && containsNewline(body))
            CompactEnsuringGap
          else if (hiddenPredecessors(whileToken).containsNewline)
            formatterState.currentIndentLevelInstruction
          else
            CompactEnsuringGap
        formatResult.before(whileToken, instruction)
      }
    }

    formatResult ++= format(condExpr)

    formatResult
  }

  private def isBlockExpr(expr: Expr) = expr.contents.size == 1 && expr.contents(0).isInstanceOf[BlockExpr]

  def format(blockExpr: BlockExpr)(implicit formatterState: FormatterState): FormatResult = {
    val BlockExpr(lbrace: Token, caseClausesOrStatSeq: Either[CaseClauses, StatSeq], rbrace: Token) = blockExpr
    var formatResult: FormatResult = NoFormatResult
    val singleLineBlock = !containsNewline(blockExpr)
    val newFormatterState = formatterState.copy(inSingleLineBlock = singleLineBlock)
    caseClausesOrStatSeq match {
      case Left(caseClauses) ⇒ // TODO: Duplication
        if (!singleLineBlock) {
          formatResult = formatResult.before(caseClauses.firstToken, newFormatterState.nextIndentLevelInstruction)
          formatResult ++= format(caseClauses)(newFormatterState.indent)
          formatResult = formatResult.before(rbrace, newFormatterState.currentIndentLevelInstruction)
        } else
          formatResult ++= format(caseClauses)(newFormatterState)

      case Right(statSeq) ⇒ {
        if (!singleLineBlock && statSeq.firstTokenOption.isDefined) {
          statSeq.firstStatOpt match {
            case Some(Expr(List(AnonymousFunctionStart(params, _), subStatSeq))) ⇒ {
              formatResult = formatResult.before(statSeq.firstToken, CompactEnsuringGap)
              for (firstToken ← subStatSeq.firstTokenOption)
                formatResult = formatResult.before(firstToken, newFormatterState.nextIndentLevelInstruction)
              formatResult ++= format(params)
              formatResult ++= format(subStatSeq)(newFormatterState.indent)
            }
            case _ ⇒ {
              val instruction =
                if (statSeq.selfReferenceOpt.isDefined)
                  CompactEnsuringGap
                else
                  newFormatterState.nextIndentLevelInstruction
              formatResult = formatResult.before(statSeq.firstToken, instruction)
              formatResult ++= format(statSeq)(newFormatterState.indent)
            }
          }
          formatResult = formatResult.before(rbrace, newFormatterState.currentIndentLevelInstruction)
        } else
          formatResult ++= format(statSeq)(newFormatterState)
      }
    }
    formatResult
  }

  private def format(caseClauses: CaseClauses)(implicit formatterState: FormatterState): FormatResult = {
    var formatResult: FormatResult = NoFormatResult
    for ((previousOption, caseClause) ← Utils.pairWithPrevious(caseClauses.caseClauses)) {
      if (previousOption.isDefined && hiddenPredecessors(caseClause.firstToken).containsNewline)
        formatResult = formatResult.before(caseClause.caseToken, formatterState.currentIndentLevelInstruction)
      formatResult ++= format(caseClause)
    }
    formatResult
  }

  private def format(caseClause: CaseClause)(implicit formatterState: FormatterState): FormatResult = {
    val CaseClause(caseToken: Token, pattern: Expr, guardOption: Option[Guard], arrow: Token, statSeq: StatSeq) = caseClause
    var formatResult: FormatResult = NoFormatResult
    formatResult ++= format(pattern)
    for (guard ← guardOption)
      formatResult ++= format(guard)
    val indentBlock = statSeq.firstTokenOption.isDefined && hiddenPredecessors(statSeq.firstToken).containsNewline
    if (indentBlock)
      formatResult = formatResult.before(statSeq.firstToken, formatterState.nextIndentLevelInstruction)

    val blockFormatterState = if (indentBlock) formatterState.indent else formatterState
    formatResult ++= format(statSeq)(blockFormatterState)
    formatResult
  }

  def format(statSeq: StatSeq)(implicit formatterState: FormatterState): FormatResult = {
    val StatSeq(selfReferenceOpt: Option[(Expr, Token)], statOption: Option[Stat], otherStats: List[(Token, Option[Stat])]) = statSeq
    var formatResult: FormatResult = NoFormatResult

    for ((selfReference, arrow) ← selfReferenceOpt) {
      formatResult ++= format(selfReference)
      for (stat ← statOption if hiddenPredecessors(stat.firstToken).containsNewline)
        formatResult = formatResult.before(stat.firstToken, formatterState.currentIndentLevelInstruction)
    }

    for (stat ← statOption) {
      formatResult ++= format(stat)
    }

    for ((semi, otherStatOption) ← otherStats) {

      if (isInferredNewline(semi))
        formatResult = formatResult.formatNewline(semi, formatterState.currentIndentLevelInstruction)

      for (otherStat ← otherStatOption) {
        if (!isInferredNewline(semi)) {
          val firstToken = otherStat.firstToken
          val instruction = if (hiddenPredecessors(firstToken).containsNewline)
            formatterState.currentIndentLevelInstruction
          else
            CompactEnsuringGap
          formatResult = formatResult.before(firstToken, instruction)
        }
        formatResult ++= format(otherStat)
      }

    }

    formatResult
  }

  private def format(stat: Stat)(implicit formatterState: FormatterState): FormatResult =
    stat match {
      case expr: Expr ⇒ format(expr)
      case fullDefOrDcl: FullDefOrDcl ⇒ format(fullDefOrDcl)
      case import_ : ImportClause ⇒ format(import_)
      case packageBlock: PackageBlock ⇒ format(packageBlock)
      case _ ⇒ NoFormatResult // TODO
    }

  def format(packageBlock: PackageBlock)(implicit formatterState: FormatterState): FormatResult = {
    val PackageBlock(packageToken: Token, name: List[Token], newlineOpt: Option[Token], lbrace: Token, topStats: StatSeq, rbrace: Token) = packageBlock

    var formatResult: FormatResult = NoFormatResult
    newlineOpt match {
      case Some(newline) ⇒ {
        formatResult = formatResult.formatNewline(newline, CompactEnsuringGap)
      }
      case None ⇒ {
        formatResult = formatResult.before(lbrace, CompactEnsuringGap)
      }
    }

    val dummyBlock = BlockExpr(lbrace, Right(topStats), rbrace)
    formatResult ++= format(dummyBlock)
    formatResult
  }

  def format(fullDefOrDcl: FullDefOrDcl)(implicit formatterState: FormatterState): FormatResult = {
    val FullDefOrDcl(annotations: List[Annotation], modifiers: List[Modifier], defOrDcl: DefOrDcl) = fullDefOrDcl
    var formatResult: FormatResult = NoFormatResult
    val preAnnotationFormattingInstruction =
      if (formatterState.inSingleLineBlock)
        CompactEnsuringGap
      else
        formatterState.currentIndentLevelInstruction
    for ((previousOption, annotation, nextOption) ← Utils.withPreviousAndNext(annotations)) {
      formatResult ++= format(annotation)
      if (previousOption.isDefined)
        formatResult = formatResult.before(annotation.firstToken, preAnnotationFormattingInstruction)
      if (nextOption.isEmpty) {
        val firstPostAnnotationToken = modifiers match {
          case Nil ⇒ defOrDcl.firstToken
          case (modifier :: rest) ⇒ modifier.firstToken
        }
        formatResult = formatResult.before(firstPostAnnotationToken, preAnnotationFormattingInstruction)
      }
    }
    formatResult ++= format(defOrDcl)
    formatResult
  }

  private def format(defOrDcl: DefOrDcl)(implicit formatterState: FormatterState): FormatResult = defOrDcl match {
    case patDefOrDcl: PatDefOrDcl ⇒ format(patDefOrDcl)
    case typeDefOrDcl: TypeDefOrDcl ⇒ format(typeDefOrDcl)
    case funDefOrDcl: FunDefOrDcl ⇒ format(funDefOrDcl)
    case tmplDef: TmplDef ⇒ format(tmplDef)
    case _ ⇒ NoFormatResult // TODO
  }

  private def format(patDefOrDcl: PatDefOrDcl)(implicit formatterState: FormatterState): FormatResult = {
    var formatResult: FormatResult = NoFormatResult
    val PatDefOrDcl(valOrVarToken: Token, pattern: Expr, otherPatterns: List[(Token, Expr)], typedOpt: Option[(Token, Type)], equalsClauseOption: Option[(Token, Expr)]) = patDefOrDcl
    formatResult ++= format(pattern)
    for ((comma, otherPattern) ← otherPatterns)
      formatResult ++= format(otherPattern)
    for ((colon, type_) ← typedOpt)
      formatResult ++= format(type_)
    for ((equals, body) ← equalsClauseOption) {
      // TODO: Copy and paste from format(FunDefOrDcl)
      val bodyToken = body.firstToken
      val (formatInstruction, exprFormatterState) =
        if (hiddenPredecessors(bodyToken).containsNewline)
          (formatterState.nextIndentLevelInstruction, formatterState.indent)
        else
          (CompactEnsuringGap, formatterState)
      formatResult = formatResult.before(bodyToken, formatInstruction)
      formatResult ++= format(body)(exprFormatterState)
    }
    formatResult
  }

  private def format(typeDefOrDcl: TypeDefOrDcl)(implicit formatterState: FormatterState): FormatResult = format(typeDefOrDcl.contents)

  def format(funDefOrDcl: FunDefOrDcl)(implicit formatterState: FormatterState): FormatResult = {
    // TODO: Lots
    var formatResult: FormatResult = NoFormatResult
    val FunDefOrDcl(defToken: Token, nameToken: Token, typeParamClauseOpt: Option[TypeParamClause], paramClauses: ParamClauses,
      returnTypeOpt: Option[(Token, Type)], funBodyOpt: Option[FunBody]) = funDefOrDcl
    for (typeParamClause ← typeParamClauseOpt)
      formatResult ++= format(typeParamClause.contents)
    for ((colon, type_) ← returnTypeOpt)
      formatResult ++= format(type_)
    formatResult ++= formatParamClauses(paramClauses)
    for (funBody ← funBodyOpt) {
      funBody match {
        case ExprFunBody(equals: Token, body: Expr) ⇒ {
          // TODO: see format(PatDefOrDcl)
          val bodyToken = body.firstToken
          val (formatInstruction, exprFormatterState) =
            if (hiddenPredecessors(bodyToken).containsNewline)
              (formatterState.nextIndentLevelInstruction, formatterState.indent)
            else
              (CompactEnsuringGap, formatterState)
          formatResult = formatResult.before(bodyToken, formatInstruction)
          formatResult ++= format(body)(exprFormatterState)
        }
        case ProcFunBody(newlineOpt: Option[Token], bodyBlock: BlockExpr) ⇒ {
          for (newline ← newlineOpt)
            formatResult = formatResult.formatNewline(newline, CompactEnsuringGap)
          if (newlineOpt.isEmpty)
            formatResult = formatResult.before(bodyBlock.firstToken, CompactEnsuringGap)
          // TODO: else?
          formatResult ++= format(bodyBlock)
        }
      }
    }
    formatResult
  }

  def formatParamClauses(paramClauses: ParamClauses, doubleIndentParams: Boolean = false)(implicit formatterState: FormatterState): FormatResult = {
    val ParamClauses(initialNewlineOpt, paramClausesAndNewlines) = paramClauses
    var formatResult: FormatResult = NoFormatResult
    for ((paramClause, newlineOption) ← paramClausesAndNewlines) // TODO: Newlines
      formatResult ++= formatParamClause(paramClause, doubleIndentParams)
    formatResult
  }

  private def formatParamClause(paramClause: ParamClause, doubleIndentParams: Boolean = false)(implicit formatterState: FormatterState): FormatResult = {
    val ParamClause(lparen, implicitOption, firstParamOption, otherParams, rparen) = paramClause
    val paramIndent = if (doubleIndentParams) 2 else 1
    val relativeToken = paramClause.tokens(1) // TODO
    var formatResult: FormatResult = NoFormatResult
    var paramFormatterState = formatterState
    for (firstParam ← firstParamOption) {
      val token = implicitOption getOrElse firstParam.firstToken
      if (hiddenPredecessors(token).containsNewline) {
        formatResult = formatResult.before(token, formatterState.indent(paramIndent).currentIndentLevelInstruction)
        paramFormatterState = if (formattingPreferences(AlignParameters)) formatterState.alignWithToken(relativeToken) else formatterState.indent(paramIndent)
      } else if (containsNewline(firstParam) && formattingPreferences(AlignParameters))
        paramFormatterState = formatterState.alignWithToken(relativeToken)
      formatResult ++= format(firstParam)(paramFormatterState)
    }

    for ((comma, param) ← otherParams) {
      val token = param.firstToken
      if (hiddenPredecessors(token).containsNewline) {
        paramFormatterState = if (formattingPreferences(AlignParameters)) formatterState.alignWithToken(relativeToken) else formatterState.indent(paramIndent)
        formatResult = formatResult.before(token, paramFormatterState.currentIndentLevelInstruction)
      }
      formatResult ++= format(param)(paramFormatterState)
    }
    formatResult
  }

  private def format(param: Param)(implicit formatterState: FormatterState): FormatResult = {
    val Param(annotations: List[Annotation], modifiers: List[Modifier], valOrVarOpt: Option[Token], id: Token, paramTypeOpt: Option[(Token, Type)], defaultValueOpt: Option[(Token, Expr)]) = param
    var formatResult: FormatResult = NoFormatResult
    for (annotation ← annotations)
      formatResult ++= format(annotation)
    for ((colon, paramType) ← paramTypeOpt)
      formatResult ++= format(paramType)
    for ((equals, expr) ← defaultValueOpt)
      formatResult ++= format(expr)
    formatResult
  }

  protected def format(import_ : ImportClause)(implicit formatterState: FormatterState): FormatResult = {
    val ImportClause(importToken: Token, importExpr: ImportExpr, otherImportExprs: List[(Token, ImportExpr)]) = import_
    var formatResult: FormatResult = NoFormatResult
    formatResult ++= format(importExpr)
    for ((comma, otherImportExpr) ← otherImportExprs)
      formatResult ++= format(otherImportExpr)
    formatResult
  }

  private def format(importExpr: ImportExpr)(implicit formatterState: FormatterState): FormatResult = importExpr match {
    case expr@Expr(_) ⇒ format(expr)
    case blockImportExpr@BlockImportExpr(_, _) ⇒ format(blockImportExpr)
  }

  private def format(blockImportExpr: BlockImportExpr)(implicit formatterState: FormatterState): FormatResult = {
    val BlockImportExpr(prefixExpr, importSelectors@ImportSelectors(lbrace, firstImportSelector: Expr, otherImportSelectors: List[(Token, Expr)], rbrace)) = blockImportExpr
    var formatResult: FormatResult = NoFormatResult
    formatResult ++= format(prefixExpr)

    val singleLineBlock = !containsNewline(importSelectors)
    val newFormatterState = formatterState.copy(inSingleLineBlock = singleLineBlock)

    if (singleLineBlock) {
      formatResult ++= format(firstImportSelector)
      for ((comma, otherImportSelector) ← otherImportSelectors)
        formatResult ++= format(otherImportSelector)
    } else {
      formatResult = formatResult.before(firstImportSelector.firstToken, formatterState.nextIndentLevelInstruction)
      formatResult ++= format(firstImportSelector)
      for ((comma, otherImportSelector) ← otherImportSelectors) {
        formatResult = formatResult.before(otherImportSelector.firstToken, formatterState.nextIndentLevelInstruction)
        formatResult ++= format(otherImportSelector)
      }
      formatResult = formatResult.before(rbrace, formatterState.currentIndentLevelInstruction)
    }
    formatResult
  }

}
