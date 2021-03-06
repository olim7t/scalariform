package scalariform.formatter

import scalariform.parser._
import scalariform.formatter._
import scalariform.formatter.preferences._

// format: OFF
class XmlExpressionFormatterTest extends AbstractExpressionFormatterTest {

  "<a b = 'c'/>" ==> "<a b='c'/>"

  "<a>{foo}</a>" ==> "<a>{ foo }</a>"

  "<a>{foo}{bar}</a>" ==> "<a>{ foo }{ bar }</a>"
  
  """<a>
    |{foo}
    |</a>""" ==>
  """<a>
    |  { foo }
    |</a>"""

  """<a>
    |foo
    |{bar}
    |baz
    |</a>""" ==>
  """<a>
    |  foo
    |  { bar }
    |  baz
    |</a>"""

  """<a>42<c/>
    |</a>""" ==>
  """<a>
    |  42<c/>
    |</a>"""

  """b(<c d={e + 
    |"f"}/>)""" ==>
  """b(<c d={
    |  e +
    |    "f"
    |}/>)"""

  """b(<c d={e + 
    |"f"}></c>)""" ==>
  """b(<c d={
    |  e +
    |    "f"
    |}></c>)"""

  "<a>1</a>" ==> "<a>1</a>"
  "<a> 1 </a>" ==> "<a>1</a>"

  """<a>
    |1</a>""" ==>
  """<a>
    |  1
    |</a>"""

  """<a><b>1</b>
    |<b>2</b>
    |<b>3</b>
    |</a>""" ==>
  """<a>
    |  <b>1</b>
    |  <b>2</b>
    |  <b>3</b>
    |</a>"""

  """{
    |<html>{
    |println("Foo")
    |}</html>
    |}""" ==>
  """{
    |  <html>{
    |    println("Foo")
    |  }</html>
    |}"""
    
  """{
    |    <package>
    |    <name>{ name.get }</name>
    |    <version>{ version.get }</version></package>
    |}""" ==>    
  """{
    |  <package>
    |    <name>{ name.get }</name>
    |    <version>{ version.get }</version>
    |  </package>
    |}"""

  """{
    |val b = <c>
    |<d/></c>
    |}""" ==>
  """{
    |  val b = <c>
    |            <d/>
    |          </c>
    |}"""

  """{
    |
    |  <p>{ 1 } { 1 }</p>;
    |
    |  <p>{ 1 }{ 1 }</p>
    |
    |}""" ==>
  """{
    |
    |  <p>{ 1 }{ 1 }</p>;
    |
    |  <p>{ 1 }{ 1 }</p>
    |
    |}"""


  """{
    |{ <a><b/></a> }
    |  { <a>
    |      <b/>
    |    </a> }
    |}""" ==>
  """{
    |  { <a><b/></a> }
    |  {
    |    <a>
    |      <b/>
    |    </a>
    |  }
    |}"""

  override val debug = false

  // implicit val formattingPreferences = FormattingPreferences.setPreference(FormatXml, true)

}
