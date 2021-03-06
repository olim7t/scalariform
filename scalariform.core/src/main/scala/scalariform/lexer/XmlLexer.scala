package scalariform.lexer

import scala.annotation.{ switch, tailrec }
import scalariform.lexer.Tokens._
import scalariform.utils.Utils

trait XmlLexer extends Lexer {

  import ScalaLexer._
  import CharConstants._
  private def xmlMode: XmlMode = modeStack.head.asInstanceOf[XmlMode]

  abstract sealed trait TagState
  case object InStartTag extends TagState
  case object InEndTag extends TagState
  case object Normal extends TagState

  private def tagMode = xmlMode.isTagMode
  private def tagMode_=(isTagMode: Boolean) {
    xmlMode.isTagMode = isTagMode
  }

  protected def isXmlMode = modeStack.head.isInstanceOf[XmlMode]

  class XmlMode(private var tagNestLevel: Int = 0, var isTagMode: Boolean = false, var tagState: TagState = Normal) extends LexerMode {
    def nestTag() { tagNestLevel += 1 }
    def unnestTag(): Int = {
      tagNestLevel -= 1
      tagNestLevel
    }
    def nestingLevel = tagNestLevel
  }

  protected def fetchXmlToken() {
    (ch: @switch) match {
      case '<' ⇒ {
        if (ch(1) == '/') {
          nextChar()
          nextChar()
          token(XML_END_OPEN)
          xmlMode.isTagMode = true
          xmlMode.tagState = InEndTag
        } else if (ch(1) == '!') {
          if (ch(2) == '-') {
            getXmlComment()
            if (xmlMode.nestingLevel == 0)
              modeStack.pop() // Go back to Scala; TODO -- scan for more elems
          } else if (ch(2) == '[') {
            getXmlCDATA()
            if (xmlMode.nestingLevel == 0)
              modeStack.pop() // Go back to Scala; TODO -- scan for more elems
          } else
            throw new ScalaLexerException("Bad XML")
        } else if (ch(1) == '?') {
          getXmlProcessingInstruction()
          if (xmlMode.nestingLevel == 0)
            modeStack.pop() // Go back to Scala; TODO -- scan for more elems
        } else if (lookaheadIs("<xml:unparsed")) {
          getXmlUnparsed()
          if (xmlMode.nestingLevel == 0)
            modeStack.pop() // Go back to Scala; TODO -- scan for more elems
        } else {
          nextChar()
          token(XML_START_OPEN)
          xmlMode.isTagMode = true
          xmlMode.tagState = InStartTag
        }
        // xmlMode.nestTag()
      }
      case '/' ⇒ {
        if (tagMode) {
          if (ch(1) == '>') {
            nextChar()
            nextChar()
            token(XML_EMPTY_CLOSE)
            xmlMode.isTagMode = false
            xmlMode.tagState = Normal
            if (xmlMode.nestingLevel == 0)
              modeStack.pop() // Go back to Scala; TODO -- scan for more elems
          } else
            getXmlCharData()
        } else
          getXmlCharData()
      }
      case '>' ⇒
        if (tagMode) {
          nextChar()
          token(XML_TAG_CLOSE)
          xmlMode.isTagMode = false
          xmlMode.tagState match {
            case InStartTag ⇒ xmlMode.nestTag()
            case InEndTag ⇒ {
              val nestingLevel = xmlMode.unnestTag()
              if (nestingLevel == 0)
                modeStack.pop() // Go back to Scala; TODO -- scan for more elems
            }
            case Normal ⇒ throw new AssertionError("shouldn't reach here")
          }
        } else
          getXmlCharData()

      case '=' ⇒
        if (tagMode) {
          nextChar()
          token(XML_ATTR_EQ)
        } else {
          getXmlCharData()
        }
      case '\'' ⇒
        if (tagMode) {
          getXmlAttributeValue('\'')
        } else {
          getXmlCharData()
        }
      case '"' ⇒
        if (tagMode) {
          getXmlAttributeValue('"')
        } else {
          getXmlCharData()
        }
      case '{' ⇒
        if (ch(1) != '{')
          switchToScalaModeAndFetchToken
        else
          getXmlCharData() // TODO: tagMode?
      case EOF_CHAR ⇒ token(EOF)
      case _ ⇒
        if (tagMode && isNameStart(ch.asInstanceOf[Char])) {
          getXmlName()
        } else if (tagMode && isSpace(ch.asInstanceOf[Char])) {
          getXmlSpace()
        } else {
          getXmlCharData()
          // throw new ScalaLexerException("illegal character in xml: " + Character.valueOf(ch.asInstanceOf[Char]))
          // TODO
        }

    }
  }

  private def getXmlCDATA() {
    munch("<![CDATA[")
    var continue = true
    while (continue) {
      if (lookaheadIs("]]>")) {
        munch("]]>")
        continue = false
      } else if (ch == EOF_CHAR)
        throw new ScalaLexerException("Malformed XML CDATA")
      else
        nextChar()
    }
    token(XML_CDATA)
  }

  private def getXmlComment() {
    munch("<!--")
    var continue = true
    while (continue) {
      if (ch == '-' && ch(1) == '-') {
        nextChar()
        nextChar()
        if (ch != '>')
          throw new ScalaLexerException("Malformed XML comment")
        nextChar()
        continue = false
      } else if (ch == EOF_CHAR)
        throw new ScalaLexerException("Malformed XML comment")
      else
        nextChar()
    }
    token(XML_COMMENT)
  }

  private def getXmlCharData() {
    var continue = true
    while (continue) {
      if (ch == EOF_CHAR || ch == '<')
        continue = false
      else if (ch == '{')
        if (ch(1) == '{') {
          nextChar(); nextChar()
        } else
          continue = false
      else
        nextChar()
    }
    token(XML_PCDATA)
  }

  // S
  private def getXmlSpace() {
    require(isSpace(ch.asInstanceOf[Char]))
    nextChar()
    while (ch != EOF_CHAR && isSpace(ch.asInstanceOf[Char]))
      nextChar()
    token(XML_WHITESPACE)
  }

  private def getXmlName() {
    require(isNameStart(ch.asInstanceOf[Char]))
    nextChar()
    while (ch != EOF_CHAR && isNameChar(ch.asInstanceOf[Char]))
      nextChar()
    // TODO endswith colon?
    token(XML_NAME)
  }

  private def getXmlAttributeValue(quote: Int) {
    require(quote == '\'' || quote == '\"')
    require(ch == quote)
    nextChar()
    while (ch != quote) {
      if (ch == EOF_CHAR) // TODO: line seps etc
        throw new ScalaLexerException("Unterminated attribute value")
      else
        nextChar()
    }
    require(ch == quote)
    nextChar()
    token(XML_ATTR_VALUE)
  }

  private def getXmlUnparsed() {
    munch("<xml:unparsed")
    var continue = true
    while (continue) {
      if (lookaheadIs("</xml:unparsed>")) {
        munch("</xml:unparsed>")
        continue = false
      } else
        nextChar()
    }
    token(XML_UNPARSED)
  }

  private def getXmlProcessingInstruction() {
    munch("<?")
    var continue = true
    while (continue) {
      if (lookaheadIs("?>")) {
        munch("?>")
        continue = false
      } else
        nextChar()
    }
    token(XML_PROCESSING_INSTRUCTION)
  }

}

